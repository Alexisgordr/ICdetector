package com.alexisgordr.icdetector.ui

import android.annotation.SuppressLint
import com.alexisgordr.icdetector.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.models.IncidentRecord
import com.alexisgordr.icdetector.models.IncidentState
import com.alexisgordr.icdetector.models.ForensicCase
import com.alexisgordr.icdetector.models.ForensicCaseState
import com.alexisgordr.icdetector.models.ForensicCaseOrigin
import com.alexisgordr.icdetector.forensics.ForensicExporter
import com.alexisgordr.icdetector.forensics.TopologyExporter
import com.alexisgordr.icdetector.forensics.StableSiteExporter
import com.alexisgordr.icdetector.models.SUBTHRESHOLD_PREFIX
import com.alexisgordr.icdetector.models.identityKey
import com.alexisgordr.icdetector.models.CellTransitionSummary
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.storage.CellDbHelper
import com.alexisgordr.icdetector.utils.ExportUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private const val HISTORY_UI_RECORD_LIMIT = 2_000

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
fun HistoryPanel(
    dbHelper: CellDbHelper,
    // v2.10.4 — Opcional: la pestaña RADIO muestra datos en vivo del servicio si está conectado.
    service: com.alexisgordr.icdetector.service.MiniICService? = null,
    onBack: () -> Unit
) {
    var items by remember { mutableStateOf<List<HistoryRecord>>(emptyList()) }
    var totalRecordCount by remember { mutableIntStateOf(0) }
    var incidents by remember { mutableStateOf<List<IncidentRecord>>(emptyList()) }
    var showIncidents by remember { mutableStateOf(false) }
    var showForensics by remember { mutableStateOf(false) }
    var showTopology by remember { mutableStateOf(false) }
    // v2.5 — Pestaña de geometría: grafo de handovers sobre las posiciones observadas por este
    // propio teléfono, sin mapas ni bases externas. Ver GeometryScreen.
    var showGeometry by remember { mutableStateOf(false) }
    // v2.10.4 — Pestaña de contexto de radio. Ver RadioScreen.
    var showRadio by remember { mutableStateOf(false) }
    var forensicCases by remember { mutableStateOf<List<ForensicCase>>(emptyList()) }
    var transitions by remember { mutableStateOf<List<CellTransitionSummary>>(emptyList()) }
    val showDeleteConfirm = remember { mutableStateOf(false) }
    // v2.3.3 — Borrar el historial es irreversible y no hay copia (allowBackup="false"). Durante
    // una campaña de meses, un toque de más al final de esta misma pantalla destruye el trabajo
    // entero, así que el diálogo exige escribir la palabra a mano.
    var deleteConfirmText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            totalRecordCount = dbHelper.getRecordCount()
            items = dbHelper.getRecords(limit = HISTORY_UI_RECORD_LIMIT)
            incidents = dbHelper.getIncidents()
            forensicCases = dbHelper.getForensicCases()
            transitions = dbHelper.getCellTransitions()
        }
    }

    LaunchedEffect(showForensics) {
        while (showForensics) {
            forensicCases = withContext(Dispatchers.IO) { dbHelper.getForensicCases() }
            delay(2_000L)
        }
    }

    LaunchedEffect(showTopology) {
        if (showTopology) transitions = withContext(Dispatchers.IO) { dbHelper.getCellTransitions() }
    }

    if (showDeleteConfirm.value) {
        val deleteArmed = deleteConfirmText.trim().equals("BORRAR", ignoreCase = false)
        AlertDialog(
            onDismissRequest = { showDeleteConfirm.value = false; deleteConfirmText = "" },
            title = { Text(stringResource(R.string.history_delete_title), color = Color.White, fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    Text(
                        "Esta acción eliminará permanentemente $totalRecordCount registros de antenas, " +
                            "sus incidentes y sus casos forenses. No hay copia de seguridad y no se puede deshacer.",
                        color = Color(0xFF888888), fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.history_delete_prompt), color = Color(0xFFCF6679), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    OutlinedTextField(
                        value = deleteConfirmText,
                        onValueChange = { deleteConfirmText = it },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = deleteArmed,
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            dbHelper.clear()
                            withContext(Dispatchers.Main) {
                                items = emptyList()
                                totalRecordCount = 0
                                showDeleteConfirm.value = false
                                deleteConfirmText = ""
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFCF6679))
                ) {
                    Text(stringResource(R.string.delete_all), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm.value = false; deleteConfirmText = "" }) {
                    Text(stringResource(R.string.cancel), color = Color.White, fontFamily = FontFamily.Monospace)
                }
            },
            containerColor = Color(0xFF111111)
        )
    }

    val groupedItems = remember(items) {
        val groups = mutableMapOf<String, MutableList<HistoryRecord>>()
        val orderedIdentities = mutableListOf<String>()
        items.forEach { record ->
            val identity = record.identityKey
            if (!groups.containsKey(identity)) {
                orderedIdentities.add(identity)
                groups[identity] = mutableListOf()
            }
            groups[identity]!!.add(record)
        }
        orderedIdentities.map { identity -> identity to groups[identity]!! }
    }

    var expandedCids by remember { mutableStateOf(setOf<String>()) }
    var antennaQuery by remember { mutableStateOf("") }
    var openDetailSection by remember { mutableStateOf<Map<String, AntennaSection>>(emptyMap()) }
    val filteredGroups = remember(groupedItems, antennaQuery) {
        val terms = antennaQuery.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (terms.isEmpty()) groupedItems else groupedItems.filter { (_, records) ->
            val searchable = records.flatMap { record ->
                listOf(record.cid, record.mcc, record.mnc, record.tac, record.pci?.toString(),
                    record.arfcn?.toString(), record.radio.name, record.netType)
            }.filterNotNull().joinToString(" ").lowercase()
            terms.all(searchable::contains)
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
            }
            Text(when {
                showForensics -> "LABORATORIO FORENSE"
                showTopology -> "TOPOLOGÍA DE HANDOVERS"
                showGeometry -> "GEOMETRÍA DE CELDAS"
                showRadio -> stringResource(R.string.radio_title)
                showIncidents -> "CAJA NEGRA DE INCIDENTES"
                else -> "HISTORIAL DE ANTENAS"
            }, color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // v2.10.4 — Una sola función fija qué pestaña está activa: al añadir RADIO había que
            // tocar las cinco lambdas a mano, y una olvidada deja dos pestañas activas a la vez.
            fun select(tab: String) {
                showIncidents = tab == "incidents"
                showForensics = tab == "forensics"
                showTopology = tab == "topology"
                showGeometry = tab == "geometry"
                showRadio = tab == "radio"
            }
            FilterChip(selected = !showIncidents && !showForensics && !showTopology && !showGeometry && !showRadio, onClick = { select("antennas") }, label = { Text(stringResource(R.string.tab_antennas)) })
            FilterChip(selected = showIncidents, onClick = { select("incidents") }, label = { Text(stringResource(R.string.tab_incidents)) })
            FilterChip(selected = showForensics, onClick = { select("forensics") }, label = { Text(stringResource(R.string.tab_forensics)) })
            FilterChip(selected = showTopology, onClick = { select("topology") }, label = { Text(stringResource(R.string.tab_topology)) })
            FilterChip(selected = showGeometry, onClick = { select("geometry") }, label = { Text(stringResource(R.string.tab_geometry)) })
            FilterChip(selected = showRadio, onClick = { select("radio") }, label = { Text(stringResource(R.string.tab_radio)) })
        }

        if (showForensics) {
            ForensicCaseList(dbHelper, forensicCases, Modifier.fillMaxWidth().weight(1f)) { deletedId ->
                forensicCases = forensicCases.filterNot { it.id == deletedId }
            }
            return@Column
        }
        if (showIncidents) {
            IncidentList(dbHelper, incidents, Modifier.fillMaxWidth().weight(1f)) { deletedId ->
                incidents = incidents.filterNot { it.id == deletedId }
            }
            return@Column
        }
        if (showTopology) {
            TopologyPanel(dbHelper, transitions, Modifier.fillMaxWidth().weight(1f))
            return@Column
        }
        if (showGeometry) {
            GeometryScreen(dbHelper, Modifier.fillMaxWidth().weight(1f))
            return@Column
        }
        if (showRadio) {
            RadioPanel(dbHelper, service, Modifier.fillMaxWidth().weight(1f))
            return@Column
        }

        if (totalRecordCount == 0) {
            Box(modifier = Modifier
                .fillMaxWidth()
                .weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.empty_history), color = Color(0xFF444444), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        } else {
            if (totalRecordCount > items.size) {
                Text(
                    "Mostrando las ${items.size} observaciones más recientes de $totalRecordCount. " +
                        "La exportación incluye el historial completo.",
                    color = Color(0xFF888888),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val context = LocalContext.current
                val exportLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/csv")
                ) { uri ->
                    uri?.let {
                        scope.launch {
                            // v2.3.3 — Export en streaming desde el cursor y con recuento
                            // verificado: ni se carga el historial entero en memoria ni se da por
                            // bueno un fichero truncado.
                            val result = withContext(Dispatchers.IO) {
                                ExportUtils.exportToCsv(
                                    context = context,
                                    uri = it,
                                    streamRecords = { emit -> dbHelper.forEachRecord(emit) }
                                )
                            }
                            result.fold(
                                onSuccess = { filas ->
                                    Toast.makeText(context, context.getString(R.string.csv_exported_format, filas), Toast.LENGTH_LONG).show()
                                },
                                onFailure = { error ->
                                    Toast.makeText(
                                        context,
                                        "❌ Error al exportar: ${error.message ?: "desconocido"}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = antennaQuery,
                    onValueChange = { antennaQuery = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.antenna_search_hint), fontSize = 10.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                    trailingIcon = if (antennaQuery.isNotEmpty()) {{ IconButton(onClick = { antennaQuery = "" }) { Icon(Icons.Default.Close, stringResource(R.string.clear_search), Modifier.size(18.dp)) } }} else null,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    shape = RoundedCornerShape(8.dp)
                )
                TextButton(
                    onClick = {
                        val fileName = "icdetector_history_${System.currentTimeMillis()}.csv"
                        exportLauncher.launch(fileName)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF4CAF50))
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.export_csv), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                }
                if (antennaQuery.isNotBlank()) {
                    val suggestions = filteredGroups.take(5).map { (_, records) -> records.first() }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { record ->
                            SuggestionChip(
                                onClick = { antennaQuery = record.cid },
                                label = { Text("CID ${record.cid} · ${record.radio.name}", fontFamily = FontFamily.Monospace, fontSize = 9.sp) }
                            )
                        }
                        if (suggestions.isEmpty()) Text(stringResource(R.string.antenna_search_empty), color = Color(0xFF888888), fontSize = 10.sp)
                    }
                }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredGroups, key = { it.first }) { (identity, records) ->
                    val isExpanded = expandedCids.contains(identity)
                    val first = records.first()
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                        shape = RoundedCornerShape(4.dp),
                        onClick = {
                            expandedCids = if (isExpanded) expandedCids - identity else expandedCids + identity
                        }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 6.dp)
                                ) {
                                    Text(
                                        "CELL ID: ${first.cid} · ${first.radio.name}",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(stringResource(R.string.connections_format, records.size), color = Color(0xFF888888), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                                Row(
                                    modifier = Modifier.wrapContentWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    VerificationBadge(first.verified, compact = true)
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = Color(0xFF666666),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            if (isExpanded) {
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider(color = Color(0xFF222222))
                                AntennaDetailAccordions(identity, records, transitions, openDetailSection[identity]) { section ->
                                    openDetailSection = if (openDetailSection[identity] == section) openDetailSection - identity
                                    else openDetailSection + (identity to section)
                                }
                            }
                        }
                    }
                }
            }

            // BOTÓN LIMPIAR AL FINAL
            if (items.isNotEmpty()) {
                OutlinedButton(
                    onClick = { showDeleteConfirm.value = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCF6679)),
                    border = BorderStroke(1.dp, Color(0xFFCF6679).copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(stringResource(R.string.clear_database), fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            AuthorSignature()
        }
    }
}

private enum class AntennaSection { GPS, HANDOVERS, TECHNICAL }

@Composable
private fun AntennaDetailAccordions(
    identity: String,
    records: List<HistoryRecord>,
    transitions: List<CellTransitionSummary>,
    open: AntennaSection?,
    onSelect: (AntennaSection) -> Unit
) {
    val routes = remember(identity, transitions) { transitions.filter { it.fromIdentity == identity || it.toIdentity == identity } }
    AntennaAccordionHeader(stringResource(R.string.antenna_gps), records.count { it.lat != null && it.lon != null }, open == AntennaSection.GPS) { onSelect(AntennaSection.GPS) }
    if (open == AntennaSection.GPS) {
        records.filter { it.lat != null && it.lon != null }.forEach { record ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(record.timestamp, color = Color(0xFFAAAAAA), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("${record.verified.name} · ${"%.5f".format(java.util.Locale.ROOT, record.lat)}, ${"%.5f".format(java.util.Locale.ROOT, record.lon)}", color = Color(0xFF777777), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                LocationButton(record.lat!!, record.lon!!, record.cid)
            }
        }
        if (records.none { it.lat != null && it.lon != null }) DetailText(stringResource(R.string.antenna_no_gps))
    }
    AntennaAccordionHeader(stringResource(R.string.antenna_handovers), routes.size, open == AntennaSection.HANDOVERS) { onSelect(AntennaSection.HANDOVERS) }
    if (open == AntennaSection.HANDOVERS) {
        routes.forEach { route ->
            val direction = if (route.fromIdentity == identity) "→ ${route.toIdentity}" else "← ${route.fromIdentity}"
            DetailText("$direction\n${route.observations}× · confianza ${(route.trustRatio * 100).toInt()}% · ${route.lastStatus.name} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(route.lastSeenMs))}")
        }
        if (routes.isEmpty()) DetailText(stringResource(R.string.antenna_no_handovers))
    }
    AntennaAccordionHeader(stringResource(R.string.antenna_technical), records.size, open == AntennaSection.TECHNICAL) { onSelect(AntennaSection.TECHNICAL) }
    if (open == AntennaSection.TECHNICAL) {
        val latest = records.first()
        val oldest = records.last()
        DetailText("CID ${latest.cid} · MCC/MNC ${latest.mcc}/${latest.mnc} · TAC ${latest.tac}\n${latest.radio.name} · PCI ${latest.pci ?: "N/A"} · ARFCN ${latest.arfcn ?: "N/A"}\n${latest.dbm} dBm · RSRQ ${latest.rsrq ?: "N/A"} · SINR ${latest.sinr ?: "N/A"} · TA ${latest.timingAdvance ?: "N/A"} ${latest.timingAdvanceUnit.name}\n${latest.verified.name} · score ${latest.score}% · anomalía ${"%.1f".format(java.util.Locale.ROOT, latest.anomalyConfidence)}%\n${records.size} muestras · ${oldest.timestamp} — ${latest.timestamp}")
    }
}

@Composable
private fun AntennaAccordionHeader(title: String, count: Int, expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(title, Modifier.weight(1f), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(count.toString(), color = Color(0xFF777777), fontFamily = FontFamily.Monospace)
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = Color(0xFF777777))
    }
}

@Composable
private fun DetailText(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), color = Color(0xFF999999), fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 13.sp)
}

@Composable
private fun TopologyPanel(
    dbHelper: CellDbHelper,
    transitions: List<CellTransitionSummary>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmExport by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) scope.launch {
            exportMessage = "Exportando topología…"
            val result = withContext(Dispatchers.IO) {
                runCatching { TopologyExporter.export(context, transitions, uri) }
            }
            exportMessage = if (result.isSuccess) "Topología exportada correctamente" else
                "Error: ${result.exceptionOrNull()?.message}"
        }
    }
    val stableSiteExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri!=null) scope.launch {
            exportMessage="Exportando Stable-Site…"
            val result=withContext(Dispatchers.IO){runCatching{StableSiteExporter.export(context,dbHelper,uri)}}
            exportMessage=if(result.isSuccess)"Stable-Site exportado correctamente" else "Error: ${result.exceptionOrNull()?.message}"
        }
    }
    if (confirmExport) AlertDialog(
        onDismissRequest = { confirmExport = false },
        title = { Text(stringResource(R.string.export_topology_title)) },
        text = {
            Text(
                "El paquete contiene identidades celulares y rutas de handover que pueden " +
                    "revelar patrones habituales de movimiento. No incluye IMSI, IMEI, teléfono " +
                    "ni credenciales. Revísalo antes de compartirlo."
            )
        },
        confirmButton = { TextButton(onClick = {
            confirmExport = false
            exportLauncher.launch("ICD-topology-${System.currentTimeMillis()}.zip")
        }) { Text(stringResource(R.string.export)) } },
        dismissButton = { TextButton(onClick = { confirmExport = false }) { Text(stringResource(R.string.cancel)) } }
    )
    var filter by remember { mutableStateOf("ALL") }
    val visible = remember(transitions, filter) {
        when (filter) {
            "TRUSTED" -> transitions.filter { it.trustedObservations > 0 }
            "REVIEW" -> transitions.filter { it.lastStatus != HeuristicStatus.PASSED }
            else -> transitions
        }.sortedWith(compareByDescending<CellTransitionSummary> { it.observations }.thenByDescending { it.lastSeenMs })
    }
    val uniqueCells = remember(transitions) {
        transitions.flatMap { listOf(it.fromIdentity, it.toIdentity) }.toSet().size
    }
    val totalHandovers = transitions.sumOf { it.observations }
    val trustedRoutes = transitions.count { it.trustedObservations > 0 }

    Column(modifier) {
        Text(
            "MAPA LÓGICO LOCAL · NO MODIFICA EL SCORE",
            color = Color(0xFF777777), fontFamily = FontFamily.Monospace, fontSize = 9.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TopologyMetric("CELDAS", uniqueCells.toString(), Modifier.weight(1f))
            TopologyMetric("RUTAS", transitions.size.toString(), Modifier.weight(1f))
            TopologyMetric("HANDOVERS", totalHandovers.toString(), Modifier.weight(1f))
            TopologyMetric("FIABLES", trustedRoutes.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(filter == "ALL", { filter = "ALL" }, label = { Text(stringResource(R.string.filter_all)) })
            FilterChip(filter == "TRUSTED", { filter = "TRUSTED" }, label = { Text(stringResource(R.string.filter_learned)) })
            FilterChip(filter == "REVIEW", { filter = "REVIEW" }, label = { Text(stringResource(R.string.filter_review)) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { confirmExport = true },
                enabled = transitions.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF4CAF50))
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.export_topology), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            }
            TextButton(
                onClick = { stableSiteExportLauncher.launch("ICD-stable-site-${System.currentTimeMillis()}.zip") },
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF80CBC4))
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.export_stable_site), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            }
        }
        exportMessage?.let {
            Text(it, color = Color(0xFF80CBC4), fontFamily = FontFamily.Monospace, fontSize = 8.sp)
        }
        Spacer(Modifier.height(6.dp))

        if (transitions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "SIN RUTAS TODAVÍA\nLa topología aparecerá tras los primeros handovers",
                    color = Color(0xFF555555), fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, lineHeight = 16.sp
                )
            }
        } else if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_routes_filter), color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { "${it.fromIdentity}>${it.toIdentity}" }) { route ->
                    TopologyRouteCard(route)
                }
            }
        }
    }
}

@Composable
private fun TopologyMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color(0xFF111111), shape = RoundedCornerShape(3.dp)) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(label, color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 7.sp, maxLines = 1)
        }
    }
}

@Composable
private fun TopologyRouteCard(route: CellTransitionSummary) {
    val (statusText, statusColor) = when (route.lastStatus) {
        HeuristicStatus.PASSED -> "COHERENTE" to Color(0xFF4CAF50)
        HeuristicStatus.FAILED -> "REVISAR" to Color(0xFFCF6679)
        HeuristicStatus.NOT_EVALUATED -> "APRENDIENDO" to Color(0xFFFFA000)
    }
    val lastSeen = remember(route.lastSeenMs) {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(route.lastSeenMs))
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.handover_route), color = Color(0xFF777777), fontFamily = FontFamily.Monospace, fontSize = 8.sp)
                Text(statusText, color = statusColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            }
            Text(
                "${topologyIdentityLabel(route.fromIdentity)}  →  ${topologyIdentityLabel(route.toIdentity)}",
                color = Color.White, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, fontSize = 10.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(
                progress = { route.trustRatio.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Color(0xFF4CAF50),
                trackColor = Color(0xFF252525)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${route.observations} observaciones · ${route.trustedObservations} fiables",
                    color = Color(0xFF999999), fontFamily = FontFamily.Monospace, fontSize = 8.sp
                )
                Text(lastSeen, color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 8.sp)
            }
        }
    }
}

/** Convierte MCC-MNC-TAC-CID-RADIO a una etiqueta compacta sin perder la identidad completa. */
private fun topologyIdentityLabel(identity: String): String {
    val parts = identity.split('-')
    if (parts.size < 5) return identity
    val radio = parts.last()
    val cid = parts.subList(3, parts.lastIndex).joinToString("-")
    return "CID $cid · $radio · ${parts[0]}/${parts[1]} · TAC ${parts[2]}"
}

@Composable
private fun TypedDeleteDialog(
    title: String,
    description: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var confirmation by remember { mutableStateOf("") }
    val armed = confirmation == "BORRAR"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(description, color = Color(0xFFAAAAAA), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text(stringResource(R.string.delete_type_prompt), color = Color(0xFFCF6679), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = armed, onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = if (armed) Color(0xFFCF6679) else Color(0xFF555555))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = Color(0xFF111111)
    )
}

@Composable
private fun ForensicCaseList(
    dbHelper: CellDbHelper,
    cases: List<ForensicCase>,
    modifier: Modifier = Modifier,
    onDeleted: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedForExport by remember { mutableStateOf<ForensicCase?>(null) }
    var confirmExport by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var selectedForDelete by remember { mutableStateOf<ForensicCase?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val selected = selectedForExport
        if (uri != null && selected != null) scope.launch {
            exportMessage = "Exportando ${selected.caseCode}…"
            val result = withContext(Dispatchers.IO) { runCatching { ForensicExporter.export(context, dbHelper, selected, uri) } }
            exportMessage = if (result.isSuccess) "Caso exportado correctamente" else "Error: ${result.exceptionOrNull()?.message}"
        }
    }
    if (confirmExport) AlertDialog(
        onDismissRequest = { confirmExport = false },
        title = { Text(stringResource(R.string.export_forensic_title)) },
        text = { Text(stringResource(R.string.forensic_privacy_warning)) },
        confirmButton = { TextButton(onClick = {
            confirmExport = false
            selectedForExport?.let { launcher.launch("${it.caseCode}.zip") }
        }) { Text(stringResource(R.string.export)) } },
        dismissButton = { TextButton(onClick = { confirmExport = false }) { Text(stringResource(R.string.cancel)) } }
    )
    selectedForDelete?.let { selected ->
        TypedDeleteDialog(
            title = stringResource(R.string.delete_forensic_title),
            description = stringResource(R.string.delete_forensic_description, selected.caseCode),
            onDismiss = { selectedForDelete = null },
            onConfirm = {
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) { dbHelper.deleteForensicCase(selected.id) }
                    if (deleted) onDeleted(selected.id)
                    selectedForDelete = null
                }
            }
        )
    }
    Column(modifier) {
        Text(
            "Ventana automática: 60 s antes · episodio completo · 60 s después",
            color = Color(0xFF777777), fontFamily = FontFamily.Monospace, fontSize = 9.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        exportMessage?.let { Text(it, color = Color(0xFF80CBC4), fontFamily = FontFamily.Monospace, fontSize = 9.sp) }
        if (cases.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_forensic_cases), color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cases, key = { it.id }) { fc ->
                val color = when (fc.state) {
                    ForensicCaseState.CAPTURING -> Color(0xFFCF6679)
                    ForensicCaseState.POST_CAPTURE -> Color(0xFFFFA000)
                    ForensicCaseState.READY -> Color(0xFF4CAF50)
                    ForensicCaseState.INTERRUPTED -> Color(0xFF888888)
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)), border = BorderStroke(1.dp, color.copy(alpha=.55f))) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(fc.caseCode, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Text(fc.state.name, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                        }
                        Text(stringResource(R.string.forensic_phase_format, fc.highestPhase, fc.sampleCount), color = Color(0xFFAAAAAA), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                        if (fc.origin == ForensicCaseOrigin.TRUST_CONTRADICTION) {
                            Text(
                                stringResource(R.string.forensic_trust_contradiction),
                                color = Color(0xFF42A5F5),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text("${fc.createdAt} → ${fc.closedAt ?: stringResource(R.string.in_progress)}", color = Color(0xFF777777), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                        if (fc.state == ForensicCaseState.READY || fc.state == ForensicCaseState.INTERRUPTED) {
                            OutlinedButton(onClick = {
                                selectedForExport = fc
                                confirmExport = true
                            }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp)) {
                                Icon(Icons.Default.Share, null, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.export_forensic_case), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            }
                            OutlinedButton(
                                onClick = { selectedForDelete = fc },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCF6679)),
                                border = BorderStroke(1.dp, Color(0xFFCF6679).copy(alpha = .45f)),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Icon(Icons.Default.Delete, null, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.delete_forensic_case), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            }
                        } else Text(stringResource(R.string.automatic_capture_running), color = color, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun IncidentList(
    dbHelper: CellDbHelper,
    incidents: List<IncidentRecord>,
    modifier: Modifier = Modifier,
    onDeleted: (Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedForDelete by remember { mutableStateOf<IncidentRecord?>(null) }
    selectedForDelete?.let { selected ->
        TypedDeleteDialog(
            title = stringResource(R.string.delete_incident_title),
            description = stringResource(R.string.delete_incident_description, selected.cid),
            onDismiss = { selectedForDelete = null },
            onConfirm = {
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) { dbHelper.deleteIncident(selected.id) }
                    if (deleted) onDeleted(selected.id)
                    selectedForDelete = null
                }
            }
        )
    }
    if (incidents.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_incidents), color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        return
    }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(incidents, key = { it.id }) { incident ->
            var expanded by remember(incident.id) { mutableStateOf(false) }
            val stateColor = when (incident.state) {
                IncidentState.CONFIRMED -> Color(0xFFCF6679)
                IncidentState.OBSERVING -> Color(0xFFFFA000)
                IncidentState.RECOVERED -> Color(0xFF4CAF50)
                IncidentState.INTERRUPTED -> Color(0xFF888888)
            }
            Card(
                onClick = { expanded = !expanded },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                border = BorderStroke(1.dp, stateColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(4.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("CID ${incident.cid} · ${incident.radio.name}", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        val stateLabel = when (incident.state) {
                            com.alexisgordr.icdetector.models.IncidentState.OBSERVING -> stringResource(R.string.incident_observing)
                            com.alexisgordr.icdetector.models.IncidentState.CONFIRMED -> stringResource(R.string.incident_confirmed)
                            com.alexisgordr.icdetector.models.IncidentState.RECOVERED -> stringResource(R.string.incident_recovered)
                            com.alexisgordr.icdetector.models.IncidentState.INTERRUPTED -> stringResource(R.string.incident_interrupted)
                        }
                        Text("${incident.highestPhase}/${incident.requiredPhases} $stateLabel", color = stateColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                    Text("${incident.startedAt} → ${incident.endedAt ?: stringResource(R.string.in_progress)}", color = Color(0xFF777777), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    Text(stringResource(R.string.score_confidence_format, incident.score, String.format(java.util.Locale.ROOT, "%.1f", incident.anomalyConfidence)), color = Color(0xFFAAAAAA), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    if (expanded) {
                        HorizontalDivider(color = Color(0xFF222222))
                        Text(incident.reason, color = Color(0xFFCCCCCC), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                        Text(incident.heuristicSnapshot, color = Color(0xFF777777), fontFamily = FontFamily.Monospace, fontSize = 8.sp)
                        if (incident.endedAt != null) {
                            OutlinedButton(
                                onClick = { selectedForDelete = incident },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCF6679)),
                                border = BorderStroke(1.dp, Color(0xFFCF6679).copy(alpha = .45f)),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Icon(Icons.Default.Delete, null, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.delete_incident), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IntelPanel(dbHelper: CellDbHelper) {
    var items by remember { mutableStateOf<List<HistoryRecord>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            items = dbHelper.getRecords()
        }
    }

    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_data_yet), color = Color(0xFF444444),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        return
    }

    val groupedItems = remember(items) {
        val groups = mutableMapOf<String, MutableList<HistoryRecord>>()
        val orderedIdentities = mutableListOf<String>()
        items.forEach { record ->
            val identity = record.identityKey
            if (!groups.containsKey(identity)) {
                orderedIdentities.add(identity)
                groups[identity] = mutableListOf()
            }
            groups[identity]!!.add(record)
        }
        orderedIdentities.map { identity -> identity to groups[identity]!! }
    }

    // Calcular estadísticas
    val totalCells = groupedItems.size
    val totalConnections = items.size
    val avgScore = items.asSequence().map { it.score }.average()
    val suspiciousCells = groupedItems.count { (_, records) -> records.any { it.score < 70 } }
    val verifiedCells = groupedItems.count { (_, r) -> r.any { it.verified.name == "VERIFIED" } }
    val notFoundCells = groupedItems.count { (_, r) -> r.any { it.verified.name == "NOT_FOUND" } }
    val recordsWithGps = items.count { it.lat != null && it.lon != null }
    val mostSeenCell = groupedItems.maxByOrNull { it.second.size }
    // "Celdas anómalas" cuenta SOLO alarmas reales. Las observaciones sub-umbral que v2.1 empieza
    // a registrar son material de análisis, no anomalías: incluirlas aquí inflaría el contador
    // sin que hubiera pasado nada nuevo en la red.
    val anomalousCells = groupedItems.count { (_, r) ->
        r.any {
            it.failedHeuristics.isNotBlank() &&
                it.failedHeuristics != "OK" &&
                !it.failedHeuristics.startsWith(SUBTHRESHOLD_PREFIX)
        }
    }
    val networkTypes = items.groupBy { it.netType }
        .mapValues { it.value.size }
        .entries.sortedByDescending { it.value }
    val dateFrom = items.lastOrNull()?.timestamp?.take(10) ?: "N/A"
    val dateTo = items.firstOrNull()?.timestamp?.take(10) ?: "N/A"

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        HorizontalDivider(color = Color(0xFF1A1A1A))
        Spacer(Modifier.height(8.dp))

        Text(
            "RESUMEN DE INTELIGENCIA RF",
            color = Color(0xFF555555),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        // Grid 2x3 uniforme
        Row(modifier = Modifier.fillMaxWidth()) {
            IntelCell(Modifier.weight(1f), "CELDAS", totalCells.toString())
            IntelCell(Modifier.weight(1f), "CONEXIONES", totalConnections.toString())
            IntelCell(Modifier.weight(1f), "SCORE MEDIO", String.format(java.util.Locale.ROOT, "%.1f%%", avgScore))
        }

        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = Color(0xFF1A1A1A))
        Spacer(Modifier.height(6.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            IntelCell(Modifier.weight(1f), "VERIFIED", verifiedCells.toString(), Color(0xFF4CAF50))
            IntelCell(Modifier.weight(1f), "NOT FOUND", notFoundCells.toString(), Color(0xFFFFA000))
            IntelCell(
                Modifier.weight(1f), "SOSPECHOSAS", suspiciousCells.toString(),
                if (suspiciousCells > 0) Color(0xFFCF6679) else Color(0xFF4CAF50)
            )
        }

        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = Color(0xFF1A1A1A))
        Spacer(Modifier.height(6.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            IntelCell(Modifier.weight(1f), "CON GPS", recordsWithGps.toString())
            IntelCell(
                Modifier.weight(1f), "ANOMALÍAS", anomalousCells.toString(),
                if (anomalousCells > 0) Color(0xFFFFA000) else Color(0xFF4CAF50)
            )
            // Período en 2 líneas para que no se corte
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.period), color = Color(0xFF555555), fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace)
                Text(dateFrom, color = Color.White, fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(dateTo, color = Color(0xFF888888), fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = Color(0xFF1A1A1A))
        Spacer(Modifier.height(6.dp))

        // MÁS VISTA — fila completa centrada
        mostSeenCell?.let { (cid, records) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.most_seen), color = Color(0xFF555555),
                    fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text(cid, color = Color.White,
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold)
                Text("${records.size}x", color = Color(0xFF4CAF50),
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = Color(0xFF1A1A1A))
            Spacer(Modifier.height(6.dp))
        }

        // REDES
        if (networkTypes.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.networks), color = Color(0xFF555555),
                    fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                networkTypes.take(3).forEach { (type, count) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(type, color = Color(0xFF888888),
                            fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                        Text(":$count", color = Color.White,
                            fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun IntelCell(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    valueColor: Color = Color.White
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = Color(0xFF555555), fontSize = 7.sp,
            fontFamily = FontFamily.Monospace)
        Text(value, color = valueColor, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}
