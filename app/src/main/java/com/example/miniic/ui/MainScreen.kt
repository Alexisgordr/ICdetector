package com.example.miniic.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.miniic.MainActivity
import com.example.miniic.R
import com.example.miniic.models.*
import com.example.miniic.service.MiniICService
import com.example.miniic.storage.CellDbHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun MainLayout(context: Context, dbHelper: CellDbHelper, service: MiniICService?) {
    var hasLoc by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasPhone by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotif by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLoc = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        hasPhone = permissions[Manifest.permission.READ_PHONE_STATE] ?: false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotif = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        }
        if (hasLoc && hasPhone) {
            (context as? MainActivity)?.startAndBindService()
        }
    }

    if (!hasLoc || !hasPhone || !hasNotif) {
        Box(modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "ICdetection requiere permisos de localización, teléfono y notificaciones para funcionar.",
                    color = Color(0xFF888888),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
                Button(
                    onClick = {
                        val arr = mutableListOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.READ_PHONE_STATE
                        ).apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                        launcher.launch(arr)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                ) {
                    Text("Otorgar", color = Color.White)
                }
            }
        }
    } else {
        MainScreenContent(dbHelper = dbHelper, service = service)
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun MainScreenContent(dbHelper: CellDbHelper, service: MiniICService?) {
    val cellList by (service?.cellFlow ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    val dbmHistory by (service?.dbmHistory ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    val geoHistory by (service?.geoHistory ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    
    var refreshing by remember { mutableStateOf(value = false) }
    val scope = rememberCoroutineScope()
    val pullRefreshState = rememberPullRefreshState(refreshing = refreshing, onRefresh = {
        scope.launch { refreshing = true; service?.forceRefresh(); delay(800); refreshing = false }
    })

    val active = cellList.firstOrNull { it.isRegistered }
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(service) {
        if (service != null) {
            delay(500)
            service.forceRefresh()
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ICdetection",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.White
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Row {
                IconButton(onClick = { showSettings = !showSettings; showHistory = false }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = if (showSettings) Color.White else Color(0xFF888888))
                }
                TextButton(
                    onClick = { showHistory = !showHistory; showSettings = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF888888))
                ) {
                    Text(if (showHistory) "MONITOR" else "HISTORIAL", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }

        if (showSettings) {
            SettingsPanel(service) { showSettings = false }
        } else if (showHistory) {
            HistoryPanel(dbHelper = dbHelper) { showHistory = false }
        } else {
            Box(modifier = Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val (statusText, statusColor) = when {
                        active == null -> "BUSCANDO SEÑAL..." to Color(0xFF888888)
                        active.isSuspicious -> "SISTEMA EN COMPROMISO" to Color(0xFFCF6679)
                        active.verified == VerificationStatus.VERIFIED -> "ENTORNO SEGURO" to Color(0xFF4CAF50)
                        else -> "MONITORIZANDO RED" to Color(0xFFFFA000)
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("ESTADO DEL SECTOR", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                Text(statusText, color = statusColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp)
                            }
                            if (active?.isSuspicious == true) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(24.dp))
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                        border = BorderStroke(0.5.dp, Color(0xFF222222)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                DataBox(modifier = Modifier.weight(1f), label = "TECNOLOGÍA", value = active?.networkType ?: "N/A")
                                DataBox(modifier = Modifier.weight(1f), label = "POTENCIA", value = if (active == null) "N/A" else "${active.dbm} dBm")
                            }
                            
                            HorizontalDivider(color = Color(0xFF1A1A1A))

                            Row(modifier = Modifier.fillMaxWidth()) {
                                DataBox(modifier = Modifier.weight(1f), label = "CELL ID", value = active?.cellId ?: "N/A", highlight = true)
                                DataBox(modifier = Modifier.weight(1f), label = "TAC / LAC", value = active?.tac ?: "N/A")
                                DataBox(modifier = Modifier.weight(1f), label = "MNC", value = active?.mnc ?: "N/A")
                            }
                        }
                    }

                    if (active != null) {
                        SecurityScorePanel(
                            active = active,
                            dbmHistory = dbmHistory,
                            geoHistory = geoHistory,
                            service = service,
                        )
                    }

                    if (active != null) {
                        SignalVisualizer(
                            active = active,
                            neighbors = cellList.filter { !it.isRegistered },
                        )
                    }

                    if (service != null) {
                        val terminalLogs by service.liveLogs.collectAsState()
                        LiveTerminalPanel(logs = terminalLogs)
                    }

                    AuthorSignature()
                }
                
                PullRefreshIndicator(
                    refreshing = refreshing,
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    backgroundColor = Color(0xFF111111),
                    contentColor = Color(0xFF4CAF50)
                )
            }
        }
    }
}

@Composable
fun SecurityScorePanel(active: CellData, dbmHistory: List<Int>, geoHistory: List<Float>, service: MiniICService?) {
    var viewMode by remember { mutableStateOf("NONE") } // NONE, GRAPH, GEO, HEUR
    val auditStatus by (service?.auditStatus ?: MutableStateFlow("Iniciando...")).collectAsState()
    
    val scoreColor = if (active.securityScore >= 90) Color(0xFF4CAF50) else if (active.securityScore >= 70) Color(0xFFFFA000) else Color.Red

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)), border = BorderStroke(0.5.dp, Color(0xFF222222)), shape = RoundedCornerShape(4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("AUDITORÍA DE SEGURIDAD", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (auditStatus.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(auditStatus, color = Color(0xFF00FF00), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${active.securityScore}%", color = scoreColor, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            AnimatedVisibility(visible = viewMode != "NONE") {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    when(viewMode) {
                        "GRAPH" -> TelemetryGraph(dbmHistory)
                        "GEO" -> GeoGraph(active, geoHistory)
                        "HEUR" -> {
                            Text("AUDITORÍA DE PARÁMETROS", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            HeuristicItem("Análisis de Celda Aislada", active.heuristicReport.isolatedCellPassed)
                            HeuristicItem("Estabilidad de Potencia (Anti-Gap)", active.heuristicReport.powerJumpPassed)
                            HeuristicItem("Consistencia de MCC", active.heuristicReport.mccConsistencyPassed)
                            HeuristicItem("Límite de Redes MNC", active.heuristicReport.mncCountPassed)
                            HeuristicItem("Validación Regional TAC", active.heuristicReport.tacDeviationPassed)
                            HeuristicItem("Coherencia Geométrica (TA)", active.heuristicReport.taDistancePassed)
                            HeuristicItem("Espectro de Vecinos (Anti-Ghost)", active.heuristicReport.ghostNeighborsPassed)
                            HeuristicItem("Cifrado de Enlace (Hardware)", active.heuristicReport.hardwareCipheringPassed)
                            HeuristicItem("Estabilidad de Conexión (Anti Ping-Pong)", active.heuristicReport.pingPongPassed)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { viewMode = if(viewMode == "GRAPH") "NONE" else "GRAPH" }, 
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if(viewMode=="GRAPH") Color(0xFF444444) else Color(0xFF222222),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Text("SEÑAL", fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = { viewMode = if(viewMode == "GEO") "NONE" else "GEO" }, 
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if(viewMode=="GEO") Color(0xFF444444) else Color(0xFF222222),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Text("GEOM", fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = { viewMode = if(viewMode == "HEUR") "NONE" else "HEUR" }, 
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if(viewMode=="HEUR") Color(0xFF444444) else Color(0xFF222222),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Text("HEUR", fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
