package com.alexisgordr.icdetector.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.alexisgordr.icdetector.MainActivity
import com.alexisgordr.icdetector.R
import com.alexisgordr.icdetector.models.*
import com.alexisgordr.icdetector.service.MiniICService
import com.alexisgordr.icdetector.storage.CellDbHelper
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

    // DISPARADOR AUTOMÁTICO DE PERMISOS / INICIO DE SERVICIO
    LaunchedEffect(hasLoc, hasPhone) {
        if (!hasLoc || !hasPhone || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotif)) {
            val arr = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
            launcher.launch(arr)
        } else {
            // Si ya tenemos los permisos críticos, iniciamos el servicio de forma segura
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
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
    val rsrqHistory by (service?.rsrqHistory ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    val geoHistory by (service?.geoHistory ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    
    var refreshing by remember { mutableStateOf(false) }
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
                Column(modifier = Modifier.fillMaxSize()) {
                    val (statusText, statusColor) = when {
                        active == null -> "BUSCANDO SEÑAL..." to Color(0xFF888888)
                        active.isSuspicious -> "SISTEMA EN COMPROMISO" to Color(0xFFCF6679)
                        active.verified == VerificationStatus.VERIFIED -> "ENTORNO SEGURO" to Color(0xFF4CAF50)
                        else -> "MONITORIZANDO — ${active.securityScore}%" to Color(0xFFFFA000)
                    }

                    // STICKY: Card de estado
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
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

                    // SCROLLABLE: Todo lo demás
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                            border = BorderStroke(0.5.dp, Color(0xFF222222)),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {

                                // Fila 1 — TECNOLOGÍA y POTENCIA
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    // TECNOLOGÍA
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 16.dp, vertical = 14.dp)
                                    ) {
                                        Text(
                                            "TECNOLOGÍA",
                                            color = Color(0xFF555555),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            active?.networkType ?: "N/A",
                                            color = Color.White,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    // Divisor vertical
                                    Box(
                                        modifier = Modifier
                                            .width(0.5.dp)
                                            .height(40.dp)
                                            .background(Color(0xFF222222))
                                    )

                                    // POTENCIA
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 16.dp, vertical = 14.dp)
                                    ) {
                                        Text(
                                            "POTENCIA",
                                            color = Color(0xFF555555),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            if (active == null) "N/A" else "${active.dbm} dBm",
                                            color = if (active != null && active.dbm >= -70) Color(0xFFCF6679)
                                            else if (active != null && active.dbm >= -85) Color(0xFFFFA000)
                                            else Color.White,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                HorizontalDivider(color = Color(0xFF1A1A1A))

                                // Fila 2 — CELL ID, TAC/LAC, MNC
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {

                                    // CELL ID
                                    Column(
                                        modifier = Modifier
                                            .weight(1.4f)
                                            .padding(horizontal = 16.dp, vertical = 14.dp)
                                    ) {
                                        Text(
                                            "CELL ID",
                                            color = Color(0xFF555555),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            active?.cellId ?: "N/A",
                                            color = Color(0xFF4CAF50),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }

                                    // Divisor vertical
                                    Box(
                                        modifier = Modifier
                                            .width(0.5.dp)
                                            .height(40.dp)
                                            .background(Color(0xFF222222))
                                    )

                                    // TAC / LAC
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 16.dp, vertical = 14.dp)
                                    ) {
                                        Text(
                                            "TAC / LAC",
                                            color = Color(0xFF555555),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            active?.tac ?: "N/A",
                                            color = Color.White,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    // Divisor vertical
                                    Box(
                                        modifier = Modifier
                                            .width(0.5.dp)
                                            .height(40.dp)
                                            .background(Color(0xFF222222))
                                    )

                                    // MNC
                                    Column(
                                        modifier = Modifier
                                            .weight(0.6f)
                                            .padding(horizontal = 16.dp, vertical = 14.dp)
                                    ) {
                                        Text(
                                            "MNC",
                                            color = Color(0xFF555555),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            active?.mnc ?: "N/A",
                                            color = Color.White,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        if (active != null) {
                            SecurityScorePanel(active, dbmHistory, rsrqHistory, geoHistory, dbHelper, service)
                        }

                        if (active != null) {
                            SignalVisualizer(active, cellList.filter { !it.isRegistered })
                        }

                        if (service != null) {
                            val terminalLogs by service.liveLogs.collectAsState()
                            LiveTerminalPanel(logs = terminalLogs)
                        }

                        AuthorSignature()
                    }
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
fun SecurityScorePanel(active: CellData, dbmHistory: List<Int>, rsrqHistory: List<Int>, geoHistory: List<Float>, dbHelper: CellDbHelper, service: MiniICService?) {
    var viewMode by remember { mutableStateOf("NONE") } // NONE, GRAPH, GEO, HEUR
    val context = LocalContext.current

    // GPS status — lee el valor cacheado, sin activar hardware
    val hasGps = remember(active) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED) {
                
                val now = System.currentTimeMillis()
                val maxAge = 120000L
                val maxAccuracy = 100f

                val gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val netLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                val validGps = gpsLoc?.let {
                    it.accuracy < maxAccuracy && (now - it.time) < maxAge
                } ?: false

                val validNet = netLoc?.let {
                    it.accuracy < maxAccuracy && (now - it.time) < maxAge
                } ?: false

                validGps || validNet
            } else false
        } catch (_: Exception) { false }
    }
    
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

                    // Mostrar ciclos de confirmación si está acumulando
                    val streakText = active.suspiciousReason
                    if (streakText != null && streakText.contains("ciclos confirmando")) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            streakText.substringBefore("]") + "]",
                            color = Color(0xFFFFA000),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("${active.securityScore}%", color = scoreColor, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)

                    // Probabilidad Bayesiana de amenaza
                    val threatColor = when {
                        active.threatProbability >= 50f -> Color(0xFFCF6679)
                        active.threatProbability >= 20f -> Color(0xFFFFA000)
                        else -> Color(0xFF555555)
                    }
                    Text(
                        "Amenaza estimada: ${String.format(java.util.Locale.ROOT, "%.1f", active.threatProbability)}%",
                        color = threatColor,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // DERECHA — minigráficas RF en vivo
                Column(horizontalAlignment = Alignment.End) {
                    MiniRsrpGraph(dbmHistory, active.dbm)
                    Spacer(Modifier.height(4.dp))
                    MiniRsrqGraph(rsrqHistory, active.rsrq)
                }
            }

            AnimatedVisibility(visible = viewMode != "NONE") {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    when(viewMode) {
                        "GRAPH" -> IntelPanel(dbHelper)
                        "GEO" -> GeoGraph(active, geoHistory)
                        "HEUR" -> {
                            Text("AUDITORÍA DE PARÁMETROS", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            HeuristicItem("1. Análisis de Celda Aislada", active.heuristicReport.isolatedCellPassed)
                            HeuristicItem("2. Estabilidad de Potencia (Anti-Gap)", active.heuristicReport.powerJumpPassed)
                            HeuristicItem("3. Consistencia de MCC", active.heuristicReport.mccConsistencyPassed)
                            HeuristicItem("4. Límite de Redes MNC", active.heuristicReport.mncCountPassed)
                            HeuristicItem("5. Validación Regional TAC", active.heuristicReport.tacDeviationPassed)
                            HeuristicItem("6. Coherencia Geométrica (TA)", active.heuristicReport.taDistancePassed)
                            HeuristicItem("7. Espectro de Vecinos (Anti-Ghost)", active.heuristicReport.ghostNeighborsPassed)
                            HeuristicItem("8. Sanidad de Frecuencia (ARFCN)", active.heuristicReport.arfcnSanityPassed)

                            if (active.heuristicReport.hardwareCipheringAvailable) {
                                HeuristicItem("9. Cifrado de Enlace (Hardware)", active.heuristicReport.hardwareCipheringPassed)
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("⚠", color = Color(0xFFFFA000), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("9. Cifrado de Enlace (Hardware) — NO DISPONIBLE", color = Color(0xFFFFA000), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }

                            HeuristicItem("10. Estabilidad de Conexión (Anti Ping-Pong)", active.heuristicReport.pingPongPassed)
                            HeuristicItem("11. Consistencia Geográfica (Cell ID móvil)", active.heuristicReport.mobileCellIdPassed)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Indicadores GPS y RED encima de los botones
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Indicador RED — izquierda
                val netState by service?.networkLatencyState?.collectAsState()
                    ?: remember { mutableStateOf("OK") }

                Text(
                    text = if (netState == "OK") "● RED OK" else "● RED ANÓMALA",
                    color = if (netState == "OK") Color(0xFF4CAF50) else Color(0xFFCF6679),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )

                // Indicador RF — centro, siempre visible
                val rsrq = active.rsrq
                val isRfWarning = netState == "ANOMALA" && rsrq != null && rsrq <= -15

                val (rfText, rfColor) = when {
                    rsrq == null          -> "● RF N/A"     to Color(0xFF666666)
                    isRfWarning           -> "⚠ RF WARNING" to Color(0xFFFFA000)
                    rsrq > -15            -> "● RF OK"       to Color(0xFF4CAF50)
                    else                  -> "● RF N/A"      to Color(0xFF666666)
                }

                Text(
                    text = rfText,
                    color = rfColor,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isRfWarning) FontWeight.Bold else FontWeight.Normal
                )

                // Indicador GPS — derecha
                Text(
                    text = if (hasGps) "● GPS OK" else "● SIN GPS",
                    color = if (hasGps) Color(0xFF4CAF50) else Color(0xFFCF6679),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

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
                    Text("INTEL", fontSize = 9.sp, fontFamily = FontFamily.Monospace)
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
