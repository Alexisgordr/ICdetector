package com.alexisgordr.icdetector.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexisgordr.icdetector.core.CellGeometry
import com.alexisgordr.icdetector.R
import com.alexisgordr.icdetector.models.CellTransitionSummary
import com.alexisgordr.icdetector.models.MobilityCellPresentation
import com.alexisgordr.icdetector.models.MobilityGeometrySnapshot
import com.alexisgordr.icdetector.forensics.GeometryExporter
import com.alexisgordr.icdetector.storage.CellDbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val MIN_GRAPH_SCALE = 1f
private const val MAX_GRAPH_SCALE = 20f

/**
 * Mantiene el lienzo dentro de su área visible. A escala 1 el grafo está completamente encajado;
 * al ampliar permite recorrerlo, pero nunca arrastrarlo hasta perder todos los datos fuera de la
 * pantalla.
 */
private fun constrainedGraphPan(pan: Offset, scale: Float, size: IntSize, margin: Float): Offset {
    if (scale <= MIN_GRAPH_SCALE || size == IntSize.Zero) return Offset.Zero
    val contentWidth = (size.width - margin * 2f).coerceAtLeast(0f)
    val contentHeight = (size.height - margin * 2f).coerceAtLeast(0f)
    val maxX = contentWidth * (scale - 1f) / 2f
    val maxY = contentHeight * (scale - 1f) / 2f
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}

private fun transformedGraphPoint(point: Offset, size: IntSize, scale: Float, pan: Offset): Offset {
    val centre = Offset(size.width / 2f, size.height / 2f)
    return centre + (point - centre) * scale + pan
}

/**
 * Pestaña GEOMETRÍA — el grafo de handovers dibujado sobre las posiciones que ESTE teléfono
 * observó, y las comprobaciones de coherencia que se derivan de ellas.
 *
 * Tres decisiones que conviene no deshacer sin pensarlo:
 *
 *  1. **No hay mapa.** Ni tiles, ni SDK, ni una sola petición de red. Un mapa pediría imágenes
 *     al servidor y cada petición revelaría dónde vive el usuario — un agujero absurdo en una
 *     app cuyo objetivo es precisamente la privacidad de la localización. El lienzo se dibuja con
 *     una proyección equirectangular sobre el rectángulo que ocupan los datos, y ya.
 *  2. **No hay base de datos externa.** Ni OpenCellID ni WiGLE. Ver [CellGeometry] para el porqué.
 *  3. **Esto no puntúa.** Es diagnóstico puro: no modifica el score, no abre casos forenses y no
 *     enseña rutas al baseline. Solo enseña lo que ya hay.
 */
@Composable
fun GeometryScreen(dbHelper: CellDbHelper, modifier: Modifier = Modifier) {

    data class Node(
        val identity: String,
        val profile: CellGeometry.Profile,
        val enodeb: Long?,
        val sector: Int?
    )

    data class Snapshot(
        val nodes: List<Node>,
        val routes: List<CellTransitionSummary>,
        val sites: List<CellGeometry.SiteCheck>,
        val routeChecks: List<CellGeometry.RouteCheck>,
        val bounds: CellGeometry.Bounds?,
        val cellsSeen: Int,
        val mobility: MobilityGeometrySnapshot
    )

    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var graphScale by remember { mutableFloatStateOf(MIN_GRAPH_SCALE) }
    var graphPan by remember { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmExport by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            exportMessage = "Exportando geometría…"
            val result = withContext(Dispatchers.IO) { runCatching { GeometryExporter.export(context, dbHelper, uri) } }
            exportMessage = if (result.isSuccess) "Geometría exportada correctamente" else "Error: ${result.exceptionOrNull()?.message}"
        }
    }

    if (confirmExport) AlertDialog(
        onDismissRequest = { confirmExport = false },
        title = { Text("EXPORTAR GEOMETRÍA") },
        text = { Text("El ZIP contiene identidades celulares y patrones asociados a lugares o rutas frecuentados. No incluye coordenadas GPS precisas. Revísalo antes de compartirlo públicamente.") },
        confirmButton = { TextButton(onClick = {
            confirmExport = false
            exportLauncher.launch("ICD-geometry-${System.currentTimeMillis()}.zip")
        }) { Text("EXPORTAR") } },
        dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("CANCELAR") } }
    )

    LaunchedEffect(Unit) {
        snapshot = withContext(Dispatchers.IO) {
            val samples = dbHelper.getAllCellLocationSamples()
            val profiles = samples.mapNotNull { (identity, list) ->
                CellGeometry.profileOrNull(list)?.let { identity to it }
            }.toMap()
            val mobility = dbHelper.getMobilityGeometrySnapshot(limit = 400)
            val routes = mobility.transitions
            Snapshot(
                nodes = profiles.map { (identity, p) ->
                    Node(identity, p, CellGeometry.enodebOf(identity), CellGeometry.sectorOf(identity))
                }.sortedByDescending { it.profile.sampleCount },
                routes = routes,
                sites = CellGeometry.siteCoherence(profiles),
                routeChecks = CellGeometry.routeCoherence(
                    routes.map {
                        Triple(it.fromIdentity, it.toIdentity, it.observations to it.trustedObservations)
                    },
                    profiles
                ),
                bounds = CellGeometry.boundsOf(profiles.values),
                cellsSeen = samples.size,
                mobility = mobility
            )
        }
    }

    val snap = snapshot
    if (snap == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.geometry_calculating),
                color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 11.sp
            )
        }
        return
    }

    if (snap.nodes.size < 2 || snap.bounds == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.geometry_insufficient_format, snap.nodes.size, snap.cellsSeen, CellGeometry.MIN_SAMPLES_FOR_PROFILE),
                color = Color(0xFF555555), fontFamily = FontFamily.Monospace,
                fontSize = 11.sp, lineHeight = 16.sp
            )
        }
        return
    }

    val bounds = snap.bounds ?: return
    val mobilityById = snap.mobility.cells.associateBy { it.identity }
    val incoherentSites = snap.sites.count { !it.coherent }
    val incoherentRoutes = snap.routeChecks.count { !it.coherent }
    val density = LocalDensity.current

    // Posiciones en pantalla. Se calculan una sola vez por (datos, tamaño) y las usan tanto el
    // dibujo como la detección de toques, para que nunca puedan discrepar.
    val margin = with(density) { 22.dp.toPx() }
    val fittedLayout: Map<String, Offset> = remember(snap.nodes, canvasSize) {
        if (canvasSize.width == 0 || canvasSize.height == 0) emptyMap()
        else {
            val w = canvasSize.width - margin * 2
            val h = canvasSize.height - margin * 2
            snap.nodes.associate { node ->
                node.identity to Offset(
                    margin + bounds.relativeX(node.profile.longitude) * w,
                    margin + bounds.relativeY(node.profile.latitude) * h
                )
            }
        }
    }
    val layout: Map<String, Offset> = remember(fittedLayout, canvasSize, graphScale, graphPan) {
        fittedLayout.mapValues { (_, point) ->
            transformedGraphPoint(point, canvasSize, graphScale, graphPan)
        }
    }

    fun resetViewport() {
        graphScale = MIN_GRAPH_SCALE
        graphPan = Offset.Zero
    }

    fun changeScale(target: Float, focalPoint: Offset = Offset(canvasSize.width / 2f, canvasSize.height / 2f)) {
        val oldScale = graphScale
        val newScale = target.coerceIn(MIN_GRAPH_SCALE, MAX_GRAPH_SCALE)
        if (canvasSize == IntSize.Zero || oldScale == newScale) return
        val centre = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
        val ratio = newScale / oldScale
        val anchoredPan = graphPan * ratio + (focalPoint - centre) * (1f - ratio)
        graphScale = newScale
        graphPan = constrainedGraphPan(anchoredPan, newScale, canvasSize, margin)
    }

    // Píxeles por metro, para que el círculo del radio P90 signifique algo y no sea decorativo.
    val pxPerMeter = remember(canvasSize, bounds) {
        if (canvasSize.height == 0) 0f
        else {
            val spanMeters = bounds.spanLat * 111_320.0
            if (spanMeters <= 0.0) 0f else ((canvasSize.height - margin * 2) / spanMeters).toFloat()
        }
    }

    Column(modifier) {
        Text(
            stringResource(R.string.geometry_disclaimer),
            color = Color(0xFF777777), fontFamily = FontFamily.Monospace, fontSize = 8.sp,
            lineHeight = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        MobilityPanel(snap.mobility, onExport = { confirmExport = true })
        exportMessage?.let { Text(it, color = Color(0xFF80CBC4), fontFamily = FontFamily.Monospace, fontSize = 8.sp) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GeometryMetric(stringResource(R.string.profiled), snap.nodes.size.toString(), Modifier.weight(1f))
            GeometryMetric(stringResource(R.string.sites), snap.sites.size.toString(), Modifier.weight(1f))
            GeometryMetric(stringResource(R.string.routes), snap.routeChecks.size.toString(), Modifier.weight(1f))
            GeometryMetric(
                stringResource(R.string.review), (incoherentSites + incoherentRoutes).toString(), Modifier.weight(1f),
                value = if (incoherentSites + incoherentRoutes > 0) Color(0xFFCF6679) else Color(0xFF4CAF50)
            )
        }
        Spacer(Modifier.height(8.dp))

        Surface(color = Color(0xFF0B0B0B), shape = RoundedCornerShape(8.dp)) {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                Canvas(
                    Modifier
                        .matchParentSize()
                        .onSizeChanged {
                            canvasSize = it
                            graphPan = constrainedGraphPan(graphPan, graphScale, it, margin)
                        }
                        .pointerInput(canvasSize, margin) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val oldScale = graphScale
                                val newScale = (oldScale * zoom).coerceIn(MIN_GRAPH_SCALE, MAX_GRAPH_SCALE)
                                val centre = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
                                val ratio = newScale / oldScale
                                // Mantiene bajo los dedos el mismo punto del grafo mientras se
                                // amplía, que es lo que hace que el pellizco resulte natural.
                                val anchoredPan = graphPan * ratio +
                                    (centroid - centre) * (1f - ratio) + pan
                                graphScale = newScale
                                graphPan = constrainedGraphPan(anchoredPan, newScale, canvasSize, margin)
                            }
                        }
                        .pointerInput(layout, graphScale) {
                            detectTapGestures(
                                onDoubleTap = { tap ->
                                    if (graphScale > 1.05f) resetViewport()
                                    else changeScale(2.5f, tap)
                                },
                                onTap = { tap ->
                                    // Radio en dp, no en píxeles físicos: seleccionar un nodo debe
                                    // ser igual de fácil en pantallas de densidad distinta.
                                    val hitRadius = 30.dp.toPx()
                                    selected = layout.minByOrNull { (_, o) -> hypot(o.x - tap.x, o.y - tap.y) }
                                        ?.takeIf { hypot(it.value.x - tap.x, it.value.y - tap.y) <= hitRadius }
                                        ?.key
                                }
                            )
                        }
                ) {
                    // Retícula discreta: da referencia visual al navegar sin fingir que es un mapa.
                    val gridColor = Color(0xFF1A2423)
                    repeat(3) { index ->
                        val fraction = (index + 1) / 4f
                        drawLine(gridColor, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height))
                        drawLine(gridColor, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
                    }
                // Aristas primero, para que los nodos queden por encima.
                snap.routeChecks.forEach { route ->
                    val a = layout[route.fromIdentity] ?: return@forEach
                    val b = layout[route.toIdentity] ?: return@forEach
                    val trustRatio =
                        if (route.observations > 0) route.trustedObservations.toFloat() / route.observations else 0f
                    val connectedToSelection = selected != null &&
                        (route.fromIdentity == selected || route.toIdentity == selected)
                    val color = when {
                        !route.coherent -> Color(0xFFCF6679)
                        connectedToSelection -> Color(0xFFFFB300)
                        trustRatio > 0f -> Color(0xFF4CAF50).copy(alpha = 0.35f + 0.5f * trustRatio)
                        else -> Color(0xFF4A4A4A)
                    }
                    drawLine(
                        color = color,
                        start = a,
                        end = b,
                        strokeWidth = (if (connectedToSelection) 2.5f else 1f) +
                            route.observations.coerceAtMost(20) * 0.25f
                    )
                    // Punta de flecha: un punto grueso junto al destino marca el sentido sin
                    // tener que calcular un triángulo rotado.
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val len = hypot(dx, dy)
                    if (len > 1f) {
                        drawCircle(color, radius = 3.5f, center = Offset(b.x - dx / len * 12f, b.y - dy / len * 12f))
                    }
                }

                snap.nodes.forEach { node ->
                    val center = layout[node.identity] ?: return@forEach
                    val isSelected = node.identity == selected
                    // El círculo exterior ES el radio P90 a escala real; el mínimo solo evita que
                    // una celda muy concentrada quede invisible.
                    val radiusPx = (node.profile.radiusP90 * pxPerMeter * graphScale).toFloat().coerceIn(6f, 180f)
                    drawCircle(
                        color = Color(0xFF80CBC4).copy(alpha = 0.10f),
                        radius = radiusPx, center = center
                    )
                    drawCircle(
                        color = Color(0xFF80CBC4).copy(alpha = 0.30f),
                        radius = radiusPx, center = center, style = Stroke(width = 1f)
                    )
                    val mobilityColor = when (mobilityById[node.identity]?.familiarity?.name) {
                        "KNOWN_ON_ROUTE" -> Color(0xFF29B6F6)
                        "OBSERVED_ON_ROUTE" -> Color(0xFFAB47BC)
                        else -> Color(0xFF78909C)
                    }
                    drawCircle(
                        color = if (isSelected) Color(0xFFFFA000) else mobilityColor,
                        radius = if (isSelected) 8f else 5f, center = center
                    )
                    if (isSelected) {
                        drawCircle(Color(0xFFFFD54F), radius = 13f, center = center, style = Stroke(width = 2f))
                    }
                }
                }

                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    color = Color(0xDD151515),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        stringResource(R.string.geometry_zoom_format, (graphScale * 100).roundToInt()),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        color = Color(0xFFB0BEC5), fontFamily = FontFamily.Monospace, fontSize = 9.sp
                    )
                }

                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    GraphControlButton(
                        icon = Icons.Default.ZoomOut,
                        description = stringResource(R.string.geometry_zoom_out),
                        enabled = graphScale > MIN_GRAPH_SCALE,
                        onClick = { changeScale(graphScale / 1.5f) }
                    )
                    GraphControlButton(
                        icon = Icons.Default.CenterFocusStrong,
                        description = stringResource(R.string.geometry_fit_all),
                        enabled = graphScale > MIN_GRAPH_SCALE || graphPan != Offset.Zero,
                        onClick = { resetViewport() }
                    )
                    GraphControlButton(
                        icon = Icons.Default.ZoomIn,
                        description = stringResource(R.string.geometry_zoom_in),
                        enabled = graphScale < MAX_GRAPH_SCALE,
                        onClick = { changeScale(graphScale * 1.5f) }
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.geometry_legend_interactive),
            color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 8.sp,
            lineHeight = 11.sp
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            selected?.let { id ->
                val node = snap.nodes.firstOrNull { it.identity == id }
                if (node != null) {
                    item {
                        NodeCard(
                            identity = node.identity,
                            sampleCount = node.profile.sampleCount,
                            radiusP90 = node.profile.radiusP90,
                            enodeb = node.enodeb,
                            sector = node.sector,
                            incoming = snap.routes.count { it.toIdentity == id },
                            outgoing = snap.routes.count { it.fromIdentity == id },
                            mobility = mobilityById[id],
                            onClose = { selected = null }
                        )
                    }
                }
            }

            if (snap.sites.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.site_coherence)) }
                item {
                    Text(
                        stringResource(R.string.site_coherence_help),
                        color = Color(0xFF555555), fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp, lineHeight = 11.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                items(snap.sites) { site -> SiteCard(site) }
            }

            val worstRoutes = snap.routeChecks.take(12)
            if (worstRoutes.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.route_coherence)) }
                item {
                    Text(
                        stringResource(R.string.route_coherence_help),
                        color = Color(0xFF555555), fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp, lineHeight = 11.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                items(worstRoutes) { route ->
                    RouteGeometryCard(route, snap.routes.firstOrNull { it.fromIdentity==route.fromIdentity && it.toIdentity==route.toIdentity })
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun GraphControlButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color(0xDD263230),
            contentColor = Color(0xFFB2DFDB),
            disabledContainerColor = Color(0x88202020),
            disabledContentColor = Color(0xFF555555)
        )
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun GeometryMetric(
    label: String,
    text: String,
    modifier: Modifier = Modifier,
    value: Color = Color.White
) {
    Surface(modifier, color = Color(0xFF111111), shape = RoundedCornerShape(3.dp)) {
        Column(
            Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text, color = value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(label, color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 7.sp, maxLines = 1)
        }
    }
}

@Composable
private fun MobilityPanel(snapshot: MobilityGeometrySnapshot, onExport: () -> Unit) {
    val trip = snapshot.openTrip
    val current = snapshot.cells.firstOrNull { it.identity == trip?.lastServing }
    val transitionTotal = snapshot.transitions.sumOf { it.observations }
    val historicalTrips = snapshot.transitions.maxOfOrNull { it.tripCount } ?: 0
    Surface(color = Color(0xFF111A1D), shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("MOVILIDAD · MEMORIA CONTEXTUAL", color=Color(0xFF80CBC4),fontFamily=FontFamily.Monospace,fontSize=10.sp,fontWeight=FontWeight.Bold)
                TextButton(onClick=onExport, contentPadding=PaddingValues(horizontal=5.dp,vertical=0.dp)) {
                    Icon(Icons.Default.Share,null,Modifier.size(13.dp));Spacer(Modifier.width(3.dp));Text("EXPORTAR GEOMETRÍA",fontSize=8.sp)
                }
            }
            GeometryLine("Viaje", if(trip==null)"INACTIVO" else "ACTIVO · ${trip.tripId.take(8)}")
            GeometryLine("Movimiento", if(trip?.hadMoving==true)"MOVING OBSERVADO" else "SIN EVIDENCIA")
            GeometryLine("Celdas / edges distintos", "${trip?.distinctCells ?: 0} / ${trip?.edgeCount ?: 0}")
            GeometryLine("Celda actual", current?.familiarity?.name ?: "UNKNOWN_ON_ROUTE")
            GeometryLine("Good edges previos", "${current?.goodEdges ?: 0}/2 · K=3")
            GeometryLine("Transiciones observadas acumuladas", transitionTotal.toString())
            GeometryLine("Máx. viajes históricos por arista", historicalTrips.toString())
            if(trip!=null && snapshot.pendingEdges.isNotEmpty()) {
                Text("Las ${snapshot.pendingEdges.size} aristas abiertas aún NO cuentan en trip_count. Si el viaje cierra válido: n → n+1.",color=Color(0xFFFFB300),fontFamily=FontFamily.Monospace,fontSize=8.sp,lineHeight=11.sp)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = Color(0xFF80CBC4), fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold, fontSize = 10.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun NodeCard(
    identity: String,
    sampleCount: Int,
    radiusP90: Double,
    enodeb: Long?,
    sector: Int?,
    incoming: Int,
    outgoing: Int,
    mobility: MobilityCellPresentation?,
    onClose: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    identity, color = Color(0xFFFFA000), fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClose, contentPadding = PaddingValues(4.dp)) {
                    Text(stringResource(R.string.close), fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Color(0xFF666666))
                }
            }
            Spacer(Modifier.height(4.dp))
            GeometryLine(stringResource(R.string.geolocated_samples), sampleCount.toString())
            GeometryLine(stringResource(R.string.observation_p90), "${radiusP90.roundToInt()} m")
            GeometryLine(
                stringResource(R.string.site_enodeb),
                if (enodeb != null) stringResource(R.string.sector_format, enodeb.toString(), sector.toString()) else stringResource(R.string.not_deducible_lte)
            )
            GeometryLine(stringResource(R.string.incoming_outgoing), "$incoming / $outgoing")
            GeometryLine("LocalCellTrust", mobility?.localTrustState ?: "NO DISPONIBLE")
            GeometryLine("Mobility", mobility?.familiarity?.name ?: "UNKNOWN_ON_ROUTE")
            GeometryLine("Good edges", "${mobility?.goodEdges ?: 0}/2")
            GeometryLine("Viajes relevantes", (mobility?.relevantTripCount ?: 0).toString())
            GeometryLine("Transiciones entrada/salida", "${mobility?.incomingTransitions ?: 0} / ${mobility?.outgoingTransitions ?: 0}")
            GeometryLine("Trusted entrada/salida", "${mobility?.trustedIncoming ?: 0} / ${mobility?.trustedOutgoing ?: 0}")
        }
    }
}

@Composable
private fun SiteCard(site: CellGeometry.SiteCheck) {
    val color = if (site.coherent) Color(0xFF4CAF50) else Color(0xFFCF6679)
    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "eNodeB ${site.enodeb}", color = Color.White,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    if (site.coherent) stringResource(R.string.coherent) else stringResource(R.string.review),
                    color = color, fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold
                )
            }
            Text(
                stringResource(R.string.site_spread_format, site.identities.size, site.minSpreadM.roundToInt(), site.maxSpreadM.roundToInt()),
                color = Color(0xFF888888), fontFamily = FontFamily.Monospace, fontSize = 9.sp
            )
            if (!site.coherent) {
                Text(
                    stringResource(R.string.site_warning),
                    color = Color(0xFFCF6679), fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp, lineHeight = 11.sp, modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun RouteGeometryCard(route: CellGeometry.RouteCheck, summary: CellTransitionSummary?) {
    val color = if (route.coherent) Color(0xFF4CAF50) else Color(0xFFCF6679)
    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                "${shortId(route.fromIdentity)} → ${shortId(route.toIdentity)}",
                color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.route_distance_format, route.centerDistanceM.roundToInt(), route.unexplainedGapM.roundToInt()),
                    color = Color(0xFF888888), fontFamily = FontFamily.Monospace, fontSize = 9.sp
                )
                Text(
                    "Transiciones ${route.observations} · Viajes ${summary?.tripCount ?: 0}",
                    color = color, fontFamily = FontFamily.Monospace, fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun GeometryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        Text(value, color = Color(0xFFE0E0E0), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
    }
}

/** `214-07-31601-79362070-LTE` → `…79362070`, para que quepa en una línea. */
private fun shortId(identity: String): String {
    val parts = identity.split('-')
    return if (parts.size >= 5) parts[parts.size - 2] else identity
}
