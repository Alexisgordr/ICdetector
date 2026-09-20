package com.alexisgordr.icdetector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import com.alexisgordr.icdetector.storage.CellDbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

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
        val cellsSeen: Int
    )

    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(Unit) {
        snapshot = withContext(Dispatchers.IO) {
            val samples = dbHelper.getAllCellLocationSamples()
            val profiles = samples.mapNotNull { (identity, list) ->
                CellGeometry.profileOrNull(list)?.let { identity to it }
            }.toMap()
            val routes = dbHelper.getCellTransitions(limit = 400)
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
                cellsSeen = samples.size
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
    val incoherentSites = snap.sites.count { !it.coherent }
    val incoherentRoutes = snap.routeChecks.count { !it.coherent }
    val density = LocalDensity.current

    // Posiciones en pantalla. Se calculan una sola vez por (datos, tamaño) y las usan tanto el
    // dibujo como la detección de toques, para que nunca puedan discrepar.
    val margin = with(density) { 22.dp.toPx() }
    val layout: Map<String, Offset> = remember(snap.nodes, canvasSize) {
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

        Surface(color = Color(0xFF0B0B0B), shape = RoundedCornerShape(4.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(layout) {
                        detectTapGestures { tap ->
                            selected = layout.minByOrNull { (_, o) -> hypot(o.x - tap.x, o.y - tap.y) }
                                ?.takeIf { hypot(it.value.x - tap.x, it.value.y - tap.y) < 60f }
                                ?.key
                        }
                    }
            ) {
                // Aristas primero, para que los nodos queden por encima.
                snap.routeChecks.forEach { route ->
                    val a = layout[route.fromIdentity] ?: return@forEach
                    val b = layout[route.toIdentity] ?: return@forEach
                    val trustRatio =
                        if (route.observations > 0) route.trustedObservations.toFloat() / route.observations else 0f
                    val color = when {
                        !route.coherent -> Color(0xFFCF6679)
                        trustRatio > 0f -> Color(0xFF4CAF50).copy(alpha = 0.35f + 0.5f * trustRatio)
                        else -> Color(0xFF4A4A4A)
                    }
                    drawLine(
                        color = color,
                        start = a,
                        end = b,
                        strokeWidth = (1f + route.observations.coerceAtMost(20) * 0.25f)
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
                    val radiusPx = (node.profile.radiusP90 * pxPerMeter).toFloat().coerceIn(6f, 140f)
                    drawCircle(
                        color = Color(0xFF80CBC4).copy(alpha = 0.10f),
                        radius = radiusPx, center = center
                    )
                    drawCircle(
                        color = Color(0xFF80CBC4).copy(alpha = 0.30f),
                        radius = radiusPx, center = center, style = Stroke(width = 1f)
                    )
                    drawCircle(
                        color = if (isSelected) Color(0xFFFFA000) else Color(0xFF4CAF50),
                        radius = if (isSelected) 7f else 4.5f, center = center
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.geometry_legend),
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
                items(worstRoutes) { route -> RouteGeometryCard(route) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
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
private fun RouteGeometryCard(route: CellGeometry.RouteCheck) {
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
                    "${route.trustedObservations}/${route.observations}",
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
