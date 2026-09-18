package com.alexisgordr.icdetector.ui

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
import com.alexisgordr.icdetector.forensics.ForensicExporter
import com.alexisgordr.icdetector.forensics.TopologyExporter
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

@Composable
fun HistoryPanel(dbHelper: CellDbHelper, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<HistoryRecord>>(emptyList()) }
    var incidents by remember { mutableStateOf<List<IncidentRecord>>(emptyList()) }
    var showIncidents by remember { mutableStateOf(false) }
    var showForensics by remember { mutableStateOf(false) }
    var showTopology by remember { mutableStateOf(false) }
    var forensicCases by remember { mutableStateOf<List<ForensicCase>>(emptyList()) }
    var transitions by remember { mutableStateOf<List<CellTransitionSummary>>(emptyList()) }
    val showDeleteConfirm = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            items = dbHelper.getRecords()
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
        AlertDialog(
            onDismissRequest = { showDeleteConfirm.value = false },
            title = { Text("¿Borrar Historial?", color = Color.White, fontFamily = FontFamily.Monospace) },
            text = { Text("Esta acción eliminará permanentemente todos los registros de antenas y geolocalización. ¿Continuar?", color = Color(0xFF888888), fontFamily = FontFamily.Monospace) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            dbHelper.clear()
                            items = emptyList()
                            withContext(Dispatchers.Main) { showDeleteConfirm.value = false }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFCF6679))
                ) {
                    Text("BORRAR TODO", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm.value = false }) {
                    Text("CANCELAR", color = Color.White, fontFamily = FontFamily.Monospace)
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

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(when {
                showForensics -> "LABORATORIO FORENSE"
                showTopology -> "TOPOLOGÍA DE HANDOVERS"
                showIncidents -> "CAJA NEGRA DE INCIDENTES"
                else -> "HISTORIAL DE ANTENAS"
            }, color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = !showIncidents && !showForensics && !showTopology, onClick = { showIncidents = false; showForensics = false; showTopology = false }, label = { Text("ANTENAS") })
            FilterChip(selected = showIncidents, onClick = { showIncidents = true; showForensics = false; showTopology = false }, label = { Text("INCIDENTES") })
            FilterChip(selected = showForensics, onClick = { showForensics = true; showIncidents = false; showTopology = false }, label = { Text("FORENSE") })
            FilterChip(selected = showTopology, onClick = { showTopology = true; showIncidents = false; showForensics = false }, label = { Text("TOPOLOGÍA") })
        }

        if (showForensics) {
            ForensicCaseList(dbHelper, forensicCases, Modifier.fillMaxWidth().weight(1f))
            return@Column
        }
        if (showIncidents) {
            IncidentList(incidents = incidents, modifier = Modifier.fillMaxWidth().weight(1f))
            return@Column
        }
        if (showTopology) {
            TopologyPanel(transitions, Modifier.fillMaxWidth().weight(1f))
            return@Column
        }

        if (items.isEmpty()) {
            Box(modifier = Modifier
                .fillMaxWidth()
                .weight(1f), contentAlignment = Alignment.Center) {
                Text("HISTORIAL VACÍO", color = Color(0xFF444444), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val context = LocalContext.current
                val exportLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/csv")
                ) { uri ->
                    uri?.let {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                ExportUtils.exportToCsv(context, items, it)
                            }
                            result.fold(
                                onSuccess = {
                                    Toast.makeText(context, "✅ CSV exportado con éxito", Toast.LENGTH_LONG).show()
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

                TextButton(
                    onClick = {
                        val fileName = "icdetector_history_${System.currentTimeMillis()}.csv"
                        exportLauncher.launch(fileName)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF4CAF50))
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("EXPORTAR CSV", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groupedItems, key = { it.first }) { (identity, records) ->
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
                                    Text("CONEXIONES: ${records.size}", color = Color(0xFF888888), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
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
                                records.forEach { record ->
                                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                        // Fila 1: Timestamp y dBm
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(record.timestamp, color = Color(0xFF666666), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                            Text("${record.dbm} dBm (${record.netType})", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        }
                                        
                                        // Fila 2: identidad de red (MCC/MNC/TAC) y Score
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "MCC/MNC: ${record.mcc}/${record.mnc} | TAC: ${record.tac}",
                                                color = Color(0xFF888888),
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1
                                            )
                                            // Mismo criterio que el monitor: el historial no puede
                                            // pintar de otro color el mismo score.
                                            val scoreColor = securityScoreColor(record.score)
                                            Text("🛡️ ${record.score}%", color = scoreColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                        }
                                        
                                        // NUEVO: Fila 3 - Coordenadas y botón de mapa (si existen)
                                        if (record.lat != null && record.lon != null) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "📍 ${String.format(java.util.Locale.ROOT, "%.4f", record.lat)}, ${String.format(java.util.Locale.ROOT, "%.4f", record.lon)}",
                                                    color = Color(0xFF666666),
                                                    fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                                LocationButton(record.lat, record.lon, record.cid)
                                            }
                                        }
                                        
                                        // Fallos de heurísticas (si los hay). v2.1: las
                                        // observaciones sub-umbral (heurísticas que fallaron sin
                                        // llegar a alarma) se registran desde esta versión y se
                                        // muestran en gris, claramente separadas de una alarma
                                        // real en rojo. Antes no se guardaban: la fila decía "OK".
                                        if ((record.failedHeuristics.isNotBlank()) && (record.failedHeuristics != "OK")) {
                                            Spacer(Modifier.height(4.dp))
                                            val isSubThreshold = record.failedHeuristics.startsWith(SUBTHRESHOLD_PREFIX)
                                            Text(
                                                if (isSubThreshold) "· ${record.failedHeuristics.removePrefix(SUBTHRESHOLD_PREFIX).trim()}"
                                                else "⚠️ Fallo: ${record.failedHeuristics}",
                                                color = if (isSubThreshold) Color(0xFF777777) else Color(0xFFCF6679),
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                    if (record != records.last()) {
                                        HorizontalDivider(color = Color(0xFF1A1A1A), thickness = 0.5.dp)
                                    }
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
                    Text("LIMPIAR BASE DE DATOS", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            AuthorSignature()
        }
    }
}

@Composable
private fun TopologyPanel(
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
    if (confirmExport) AlertDialog(
        onDismissRequest = { confirmExport = false },
        title = { Text("Exportar topología") },
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
        }) { Text("EXPORTAR") } },
        dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("CANCELAR") } }
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
            FilterChip(filter == "ALL", { filter = "ALL" }, label = { Text("TODAS") })
            FilterChip(filter == "TRUSTED", { filter = "TRUSTED" }, label = { Text("APRENDIDAS") })
            FilterChip(filter == "REVIEW", { filter = "REVIEW" }, label = { Text("REVISAR") })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { confirmExport = true },
                enabled = transitions.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF4CAF50))
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("EXPORTAR TOPOLOGÍA", fontFamily = FontFamily.Monospace, fontSize = 9.sp)
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
                Text("SIN RUTAS PARA ESTE FILTRO", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
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
                Text("RUTA DE HANDOVER", color = Color(0xFF777777), fontFamily = FontFamily.Monospace, fontSize = 8.sp)
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
private fun ForensicCaseList(dbHelper: CellDbHelper, cases: List<ForensicCase>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedForExport by remember { mutableStateOf<ForensicCase?>(null) }
    var confirmExport by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
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
        title = { Text("Exportar caso forense") },
        text = { Text("El paquete puede contener ubicación GPS precisa, datos de radio, modelo del dispositivo y registros técnicos. Revísalo antes de compartirlo; nunca contiene claves de API, IMSI ni IMEI.") },
        confirmButton = { TextButton(onClick = {
            confirmExport = false
            selectedForExport?.let { launcher.launch("${it.caseCode}.zip") }
        }) { Text("EXPORTAR") } },
        dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("CANCELAR") } }
    )
    Column(modifier) {
        Text(
            "Ventana automática: 60 s antes · episodio completo · 60 s después",
            color = Color(0xFF777777), fontFamily = FontFamily.Monospace, fontSize = 9.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        exportMessage?.let { Text(it, color = Color(0xFF80CBC4), fontFamily = FontFamily.Monospace, fontSize = 9.sp) }
        if (cases.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("SIN CASOS FORENSES\nLa captura comienza automáticamente en 1/3", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
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
                        Text("Fase máxima ${fc.highestPhase}/3 · ${fc.sampleCount} muestras", color = Color(0xFFAAAAAA), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                        Text("${fc.createdAt} → ${fc.closedAt ?: "EN CURSO"}", color = Color(0xFF777777), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                        if (fc.state == ForensicCaseState.READY || fc.state == ForensicCaseState.INTERRUPTED) {
                            OutlinedButton(onClick = {
                                selectedForExport = fc
                                confirmExport = true
                            }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp)) {
                                Icon(Icons.Default.Share, null, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                                Text("EXPORTAR CASO FORENSE", fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            }
                        } else Text("CAPTURA AUTOMÁTICA EN CURSO", color = color, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun IncidentList(incidents: List<IncidentRecord>, modifier: Modifier = Modifier) {
    if (incidents.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("SIN INCIDENTES REGISTRADOS", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
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
                        Text("${incident.highestPhase}/${incident.requiredPhases} ${incident.state.name}", color = stateColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                    Text("${incident.startedAt} → ${incident.endedAt ?: "EN CURSO"}", color = Color(0xFF777777), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    Text("Score ${incident.score}% · confianza ${String.format(java.util.Locale.ROOT, "%.1f", incident.anomalyConfidence)}%", color = Color(0xFFAAAAAA), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    if (expanded) {
                        HorizontalDivider(color = Color(0xFF222222))
                        Text(incident.reason, color = Color(0xFFCCCCCC), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                        Text(incident.heuristicSnapshot, color = Color(0xFF777777), fontFamily = FontFamily.Monospace, fontSize = 8.sp)
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
            Text("SIN DATOS AÚN", color = Color(0xFF444444),
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
                Text("PERÍODO", color = Color(0xFF555555), fontSize = 7.sp,
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
                Text("MÁS VISTA", color = Color(0xFF555555),
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
                Text("REDES", color = Color(0xFF555555),
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
