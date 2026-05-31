package com.alexisgordr.icdetector.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.storage.CellDbHelper
import com.alexisgordr.icdetector.utils.ExportUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HistoryPanel(dbHelper: CellDbHelper, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<HistoryRecord>>(emptyList()) }
    val showDeleteConfirm = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            items = dbHelper.getRecords()
        }
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
        val orderedCids = mutableListOf<String>()
        items.forEach { record ->
            if (!groups.containsKey(record.cid)) {
                orderedCids.add(record.cid)
                groups[record.cid] = mutableListOf()
            }
            groups[record.cid]!!.add(record)
        }
        orderedCids.map { cid -> cid to groups[cid]!! }
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
            Text("HISTORIAL DE ANTENAS", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
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
                    uri?.let { ExportUtils.exportToCsv(context, items, it) }
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
                items(groupedItems, key = { it.first }) { (cid, records) ->
                    val isExpanded = expandedCids.contains(cid)
                    val first = records.first()
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                        shape = RoundedCornerShape(4.dp),
                        onClick = {
                            expandedCids = if (isExpanded) expandedCids - cid else expandedCids + cid
                        }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("CELL ID: $cid", color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    Text("CONEXIONES: ${records.size}", color = Color(0xFF888888), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    VerificationBadge(first.verified)
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = Color(0xFF666666)
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
                                        
                                        // Fila 2: MNC/TAC y Score
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text("MNC: ${record.mnc} | TAC: ${record.tac}", color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                            val scoreColor = if (record.score >= 90) Color(0xFF4CAF50) else if (record.score >= 70) Color(0xFFFFA000) else Color(0xFFCF6679)
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
                                        
                                        // Fallos de heurísticas (si los hay)
                                        if ((record.failedHeuristics.isNotBlank()) && (record.failedHeuristics != "OK")) {
                                            Spacer(Modifier.height(4.dp))
                                            Text("⚠️ Fallo: ${record.failedHeuristics}", color = Color(0xFFCF6679), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
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
        val orderedCids = mutableListOf<String>()
        items.forEach { record ->
            if (!groups.containsKey(record.cid)) {
                orderedCids.add(record.cid)
                groups[record.cid] = mutableListOf()
            }
            groups[record.cid]!!.add(record)
        }
        orderedCids.map { cid -> cid to groups[cid]!! }
    }

    // Calcular estadísticas
    val totalCells = groupedItems.size
    val totalConnections = items.size
    val avgScore = items.asSequence().map { it.score }.average()
    val suspiciousEvents = items.count { it.score < 70 }
    val verifiedCells = groupedItems.count { (_, r) -> r.any { it.verified.name == "VERIFIED" } }
    val notFoundCells = groupedItems.count { (_, r) -> r.any { it.verified.name == "NOT_FOUND" } }
    val recordsWithGps = items.count { it.lat != null && it.lon != null }
    val mostSeenCell = groupedItems.maxByOrNull { it.second.size }
    val anomalousCells = groupedItems.count { (_, r) ->
        r.any { it.failedHeuristics.isNotBlank() && it.failedHeuristics != "OK" }
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
                Modifier.weight(1f), "SOSPECHOSAS", suspiciousEvents.toString(),
                if (suspiciousEvents > 0) Color(0xFFCF6679) else Color(0xFF4CAF50)
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

