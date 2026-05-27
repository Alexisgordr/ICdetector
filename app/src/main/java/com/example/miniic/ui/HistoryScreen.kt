package com.example.miniic.ui

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
import com.example.miniic.models.HistoryRecord
import com.example.miniic.storage.CellDbHelper
import com.example.miniic.utils.ExportUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HistoryPanel(dbHelper: CellDbHelper, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<HistoryRecord>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            items = dbHelper.getRecords()
        }
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text("HISTORIAL DE ANTENAS", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            TextButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        dbHelper.clear()
                        items = emptyList()
                    }
                },
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFCF6679))
            ) {
                Text("LIMPIAR", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
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
                        val fileName = "miniic_history_${System.currentTimeMillis()}.csv"
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
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(record.timestamp, color = Color(0xFF666666), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                            Text("${record.dbm} dBm (${record.netType})", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text("MNC: ${record.mnc} | TAC: ${record.tac}", color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                            val scoreColor = if (record.score >= 90) Color(0xFF4CAF50) else if (record.score >= 70) Color(0xFFFFA000) else Color(0xFFCF6679)
                                            Text("🛡️ ${record.score}%", color = scoreColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                        }
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
            AuthorSignature()
        }
    }
}
