package com.alexisgordr.icdetector.service

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.provider.Settings
import android.telephony.*
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.alexisgordr.icdetector.MainActivity
import com.alexisgordr.icdetector.core.ThreatAnalyzer
import com.alexisgordr.icdetector.models.*
import com.alexisgordr.icdetector.network.OpenCellIdClient
import com.alexisgordr.icdetector.network.WigleClient
import com.alexisgordr.icdetector.storage.CellDbHelper
import com.alexisgordr.icdetector.telephony.CellParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class MiniICService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    
    private val _cellFlow = MutableStateFlow<List<CellData>>(emptyList())
    val cellFlow: StateFlow<List<CellData>> = _cellFlow

    private val _liveLogs = MutableStateFlow(listOf("[SYS] ICdetector Engine Iniciado...", "[SYS] Esperando hooks del módem..."))
    val liveLogs: StateFlow<List<String>> = _liveLogs

    private val _dbmHistory = MutableStateFlow<List<Int>>(emptyList())
    val dbmHistory: StateFlow<List<Int>> = _dbmHistory

    private val _geoHistory = MutableStateFlow<List<Float>>(emptyList())
    val geoHistory: StateFlow<List<Float>> = _geoHistory

    private val _auditStatus = MutableStateFlow("")
    val auditStatus: StateFlow<String> = _auditStatus

    private fun appendLog(type: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val newLog = "[$timestamp] $type $message"
        _liveLogs.value = (_liveLogs.value + newLog).takeLast(40)
    }

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var dbHelper: CellDbHelper
    private lateinit var locationManager: LocationManager
    private var toneGenerator: ToneGenerator? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var displayInfoCallback: TelephonyCallback? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var lastDisplayInfo: TelephonyDisplayInfo? = null
    private var isScreenOn = true
    private var isServiceRunning = false
    private var lastAirplaneTriggerTime = 0L
    private var lastStrongSignalAlarmTime = 0L
    
    private var isHardwareCipheringActive = false
    private var isHardwareCipheringAvailable = false
    private val locationListener = LocationListener { location ->
        if (location.accuracy < 100f) {
            onGpsAvailable()
        }
    }
    private var lastGpsTriggerTime = 0L

    private val anomalyStreaks = ConcurrentHashMap<String, Int>()
    private var lastStreakCellId = ""
    private val CONFIRMATION_CYCLES = 3

    var openCellIdKey: String = ""
    var wigleApiName: String = ""
    var wigleApiToken: String = ""
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val verificationCache = ConcurrentHashMap<String, VerificationStatus>()

    var alarmThreshold = -50f
    var isStrongSignalAlarmEnabled = true
    var is3gAirplaneModeEnabled = true
    var isProxyEnabled = false

    private var prevCid: String? = null
    private var prevNetType: String? = null
    private var prevDbm: Int? = null
    private var isCallbackWorking = false
    private var connectionRetryCount = 0
    private val cellChangeHistory = CopyOnWriteArrayList<Pair<String, Long>>()

    inner class LocalBinder : Binder() {
        fun getService(): MiniICService = this@MiniICService
    }

    fun forceRefresh() {
        _cellFlow.value = emptyList()
        requestFreshCellInfo()
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        dbHelper = CellDbHelper(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        
        val prefs = getSharedPreferences("miniic_prefs", MODE_PRIVATE)
        openCellIdKey = prefs.getString("opencellid_key", "") ?: ""
        wigleApiName = prefs.getString("wigle_api_name", "") ?: ""
        wigleApiToken = prefs.getString("wigle_api_token", "") ?: ""
        isProxyEnabled = prefs.getBoolean("proxy_enabled", false)

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        createNotificationChannel()
        // En Android 14+ es obligatorio pasar el foregroundServiceType en startForeground
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification("Sondeo activo"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Sondeo activo"))
        }

        registerTelephonyCallback()
        registerDisplayInfoCallback()
        registerScreenReceiver()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // GPS puro por satélite
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    3000L, 5f, locationListener
                )
            }
            // Network/WiFi (GrapheneOS usa sus propios servidores, privado)
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    3000L, 5f, locationListener
                )
            }
        }

        scope.launch(Dispatchers.Default) {
            var wasAirplaneModeOn = false
            while (isActive && isServiceRunning) {
                val delayTime = if (isScreenOn) 2000L else 10000L
                
                val isAirplaneModeOn = Settings.Global.getInt(
                    contentResolver, 
                    Settings.Global.AIRPLANE_MODE_ON, 0
                ) != 0

                if (isAirplaneModeOn) {
                    _cellFlow.value = emptyList()
                    updateNotificationText("Sin señal / Modo Avión")
                    appendLog("[SYS]", "⚠️ Modo Avión activo. Suspendiendo escaneo.")
                    wasAirplaneModeOn = true
                } else {
                    if (wasAirplaneModeOn) {
                        appendLog("[SYS]", "Conexión restaurada. Analizando nueva celda activa...")
                        // Limpiar cache no-verificada para forzar re-consulta a DB y APIs si corresponde
                        verificationCache.entries.removeIf { it.value != VerificationStatus.VERIFIED }
                        wasAirplaneModeOn = false
                    }
                    requestFreshCellInfo()
                }

                delay(delayTime)
            }
        }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                isScreenOn = intent?.action == Intent.ACTION_SCREEN_ON
            }
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun registerTelephonyCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val callback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                            isCallbackWorking = true
                            processCellInfo(cellInfo)
                        }
                    }
                    telephonyCallback = callback
                    telephonyCallback?.let {
                        telephonyManager.registerTelephonyCallback(mainExecutor, it)
                    }

                    if (Build.VERSION.SDK_INT >= 34) {
                        registerAdvancedSecurityCallback()
                    }
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun registerAdvancedSecurityCallback() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val securityCallback = ImsiCatcherSecurityCallback()
                telephonyManager.registerTelephonyCallback(mainExecutor, securityCallback)
                appendLog("[SYS]", "Callback L3 registrado (esperando eventos del modem).")
            }
        } catch (e: Exception) {
            isHardwareCipheringAvailable = false
            Log.e("MiniIC", "Aviso: No se pudo registrar el callback de seguridad avanzado: ${e.message}")
            appendLog("[SYS]", "Error al registrar callback L3: ${e.message}")
        }
    }

    private fun triggerSecurityAlert(message: String) {
        Log.e("MiniIC_Security", message)
        appendLog("[SEC]", "🚨 ALERTA: $message")
        toneGenerator?.startTone(ToneGenerator.TONE_SUP_ERROR, 500)
        updateNotificationText("⚠️ $message")
        
        scope.launch(Dispatchers.IO) {
            dbHelper.logConnection(
                netType = "ALERTA",
                cid = "SECURITY",
                mnc = "N/A",
                tac = "N/A",
                mcc = "N/A",
                dbm = -999,
                verified = VerificationStatus.ERROR,
                score = 0,
                failedHeuristics = message
            )
        }
    }

    private fun registerDisplayInfoCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    displayInfoCallback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                        override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                            isCallbackWorking = true
                            lastDisplayInfo = displayInfo
                            requestFreshCellInfo()
                        }
                    }
                    displayInfoCallback?.let {
                        telephonyManager.registerTelephonyCallback(mainExecutor, it)
                    }
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun requestFreshCellInfo() {
        if (!isCallbackWorking && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)) {
            connectionRetryCount++
            if (connectionRetryCount >= 4) {
                connectionRetryCount = 0
                try {
                    telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                    telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
                    displayInfoCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
                } catch (_: Exception) {}
                registerTelephonyCallback()
                registerDisplayInfoCallback()
            }
        }

        if (telephonyCallback == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerTelephonyCallback()
        }
        if (displayInfoCallback == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerDisplayInfoCallback()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                telephonyManager.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        processCellInfo(cellInfo)
                    }
                    override fun onError(errorCode: Int, detail: Throwable?) {
                        if (ContextCompat.checkSelfPermission(this@MiniICService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            try {
                                processCellInfo(telephonyManager.allCellInfo)
                            } catch (e: SecurityException) { e.printStackTrace() }
                        }
                    }
                })
            } catch (e: SecurityException) { e.printStackTrace() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        scope.cancel()
        toneGenerator?.release()
        screenReceiver?.let { unregisterReceiver(it) }
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
            displayInfoCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
        }
    }

    private fun processCellInfo(infoList: List<CellInfo>?) {
        try {
            if (!infoList.isNullOrEmpty()) {
                isCallbackWorking = true
                connectionRetryCount = 0
            }

            var list = mutableListOf<CellData>()
            infoList?.forEach { info ->
                val networkTypeString = if (info.isRegistered && info is CellInfoLte) {
                    getLteSpecificType()
                } else if (info is CellInfoLte) {
                    "4G LTE"
                } else if (info is CellInfoNr) {
                    "5G NR (SA)"
                } else if (info is CellInfoWcdma) {
                    "3G WCDMA"
                } else if (info is CellInfoGsm) {
                    "2G GSM"
                } else {
                    "Unknown"
                }

                val mcc = getNetworkOperatorMcc()
                val mnc = getNetworkOperatorMnc()

                val parsed = CellParser.parseCell(info, networkTypeString, mcc, mnc)
                if (parsed != null && parsed.dbm != Int.MAX_VALUE && parsed.dbm < 100) {
                    list.add(parsed)
                }
            }

            if (list.isEmpty()) {
                _cellFlow.value = emptyList()
                updateNotificationText("Sin señal / Modo Avión")
                return
            }

            val bestTa = list.firstOrNull { it.timingAdvance != null && it.timingAdvance >= 0 }?.timingAdvance
            
            if (bestTa != null) {
                list = list.asSequence().map { cell -> 
                    if (cell.isRegistered && cell.timingAdvance == null) {
                        cell.copy(timingAdvance = bestTa)
                    } else cell
                }.toMutableList()
            }

            val neighbors = list.filter { !it.isRegistered }
            val activeRaw = list.firstOrNull { it.isRegistered }

            scope.launch(Dispatchers.IO) {
                val currentLocation = getCurrentLocation()
                
                // Pre-fetch de historial para la heurística 11 (Geográfica/RF)
                val preloadedHistory = if (activeRaw != null && activeRaw.cellId != "N/A" && currentLocation != null) {
                    dbHelper.getPreviousCellHistory(activeRaw.cellId, activeRaw.mnc, activeRaw.tac, currentLocation)
                } else emptyList()

                val analyzedList = list.map { cell ->
                    ThreatAnalyzer.analyzeThreats(
                        active = cell,
                        neighbors = neighbors,
                        isHardwareCipheringActive = isHardwareCipheringActive,
                        isHardwareCipheringAvailable = isHardwareCipheringAvailable,
                        cellChangeHistory = cellChangeHistory,
                        currentLocation = currentLocation,
                        preloadedHistory = if (cell.isRegistered) preloadedHistory else emptyList(),
                        isWifiActive = isWifiConnected()
                    )
                }

                withContext(Dispatchers.Main) {
                    val sorted = analyzedList.sortedWith(
                        compareByDescending<CellData> { it.isRegistered }
                            .thenByDescending { it.dbm }
                    )

                    val active = sorted.firstOrNull { it.isRegistered }
                    if (active != null) {
                        // 1. Obtener estado conocido (Caché o DB) para no mostrar PENDING si ya existe
                        val cacheKey = "${active.mcc}-${active.mnc}-${active.tac}-${active.cellId}"
                        val knownStatus = verificationCache[cacheKey] ?: VerificationStatus.PENDING

                        // Aplicar confirmación temporal antes de alertas
                        val confirmedActive = applyTemporalConfidence(active)

                        // 2. Lanzar alertas y registro con el estado actual
                        checkAlerts(confirmedActive.copy(verified = knownStatus))

                        // 3. Iniciar proceso de verificación (solo si es necesario)
                        verifyCell(active, neighbors)
                        
                        val finalStatus = verificationCache[cacheKey] ?: knownStatus
                        
                        _cellFlow.value = sorted.map { 
                            if (it.isRegistered) confirmedActive.copy(verified = finalStatus) else it 
                        }
                        
                        updateNotification(confirmedActive.copy(verified = finalStatus))
                    } else {
                        _cellFlow.value = emptyList()
                        updateNotificationText("Buscando red...")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getCurrentLocation(): Location? {
        return try {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED) return null

            val gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            val now = System.currentTimeMillis()
            val maxAge = 120000L    // 2 minutos
            val maxAccuracy = 100f  // 100 metros

            // Filtrar por antigüedad y precisión
            val validGps = gpsLoc?.takeIf {
                it.accuracy < maxAccuracy &&
                (now - it.time) < maxAge
            }

            val validNet = netLoc?.takeIf {
                it.accuracy < maxAccuracy &&
                (now - it.time) < maxAge
            }

            // Preferir el más preciso de los dos
            when {
                validGps != null && validNet != null ->
                    if (validGps.accuracy <= validNet.accuracy) validGps else validNet
                validGps != null -> validGps
                validNet != null -> validNet
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun applyTemporalConfidence(cell: CellData): CellData {
        // Si cambia la celda activa, resetear todos los streaks
        if (cell.cellId != lastStreakCellId) {
            anomalyStreaks.clear()
            lastStreakCellId = cell.cellId
        }

        val currentStreak = if (cell.isSuspicious) {
            val newStreak = (anomalyStreaks[cell.cellId] ?: 0) + 1
            anomalyStreaks[cell.cellId] = newStreak
            newStreak
        } else {
            anomalyStreaks.remove(cell.cellId)
            0
        }

        val isConfirmed = cell.isSuspicious && currentStreak >= CONFIRMATION_CYCLES

        return cell.copy(
            isSuspicious = isConfirmed,
            suspiciousReason = when {
                isConfirmed -> cell.suspiciousReason
                cell.isSuspicious -> "[$currentStreak/$CONFIRMATION_CYCLES ciclos confirmando] ${cell.suspiciousReason}"
                else -> null
            }
        )
    }

    private fun onGpsAvailable() {
        val now = System.currentTimeMillis()
        if (now - lastGpsTriggerTime < 60000L) return

        val currentCell = _cellFlow.value.firstOrNull { it.isRegistered } ?: return
        val cacheKey = "${currentCell.mcc}-${currentCell.mnc}-${currentCell.tac}-${currentCell.cellId}"
        val cachedStatus = verificationCache[cacheKey]

        // Solo re-verificar si realmente está pendiente o con error
        val needsReverification = cachedStatus == null ||
                                  cachedStatus == VerificationStatus.PENDING ||
                                  cachedStatus == VerificationStatus.ERROR

        if (!needsReverification) return

        lastGpsTriggerTime = now
        scope.launch {
            delay(3000L)

            // Verificar también en DB antes de lanzar
            val loc = getCurrentLocation()
            if (loc == null) {
                appendLog("[GPS]", "GPS inestable tras espera, reintentando más tarde")
                lastGpsTriggerTime = 0L
                return@launch
            }

            // NUEVO: rellenar registros históricos sin coordenadas
            scope.launch(Dispatchers.IO) {
                val updated = dbHelper.updateNullCoordinates(
                    currentCell.cellId,
                    currentCell.mnc,
                    currentCell.tac,
                    currentCell.mcc,
                    loc.latitude,
                    loc.longitude
                )
                if (updated > 0) {
                    appendLog("[GPS]", "Coordenadas rellenadas en $updated registros históricos")
                }
            }

            val dbStatus = withContext(Dispatchers.IO) {
                dbHelper.getKnownStatus(
                    currentCell.mnc, currentCell.tac, currentCell.cellId,
                    currentCell.mcc, loc.latitude, loc.longitude
                )
            }

            if (dbStatus == VerificationStatus.VERIFIED) {
                appendLog("[GPS]", "Celda ya verificada en DB, sin necesidad de API")
                verificationCache[cacheKey] = VerificationStatus.VERIFIED
                // Forzar actualización de la UI con el estado recuperado
                updateFlowWithStatus(currentCell.cellId, VerificationStatus.VERIFIED)
                return@launch
            }

            verificationCache.remove(cacheKey)
            appendLog("[GPS]", "GPS estabilizado — relanzando verificación")
            verifyCell(currentCell, _cellFlow.value.filter { !it.isRegistered })
        }
    }

    private fun isValidCoordinate(lat: Double, lon: Double): Boolean {
        if (lat == 0.0 && lon == 0.0) return false
        if (lat < -90 || lat > 90) return false
        if (lon < -180 || lon > 180) return false
        
        val currentLoc = getCurrentLocation()
        if (currentLoc == null) {
            appendLog("[API]", "Coordenadas descartadas: sin fix GPS para validar distancia")
            return false
        }

        val results = FloatArray(1)
        Location.distanceBetween(
            currentLoc.latitude, currentLoc.longitude,
            lat, lon, results
        )
        if (results[0] > 200000f) {
            appendLog("[API]", "Coordenadas rechazadas: a ${results[0].toInt()}m de tu posición")
            return false
        }
        return true
    }

    private fun verifyCell(cell: CellData, neighbors: List<CellData>) {
        if (cell.cellId == "N/A" || cell.mnc == "N/A" || cell.mcc == "N/A") return
        
        val cacheKey = "${cell.mcc}-${cell.mnc}-${cell.tac}-${cell.cellId}"
        
        // 1. Mirar caché rápida
        val cached = verificationCache[cacheKey]
        if (cached != null && cached != VerificationStatus.ERROR) return

        // Verificar credenciales
        val hasWigle = wigleApiName.isNotBlank() && wigleApiToken.isNotBlank()
        val hasOpenCellId = openCellIdKey.isNotBlank() && !openCellIdKey.startsWith("pk.YOUR")

        if (!hasWigle && !hasOpenCellId) {
            appendLog("[API]", "Intentando conectarse a la base de datos")
            return
        }

        // Marcamos como PENDING inmediatamente para evitar peticiones duplicadas (Race Condition)
        verificationCache[cacheKey] = VerificationStatus.PENDING

        scope.launch(Dispatchers.IO) {
            // 2. Mirar Base de Datos (fuera del hilo principal)
            val currentLoc = getCurrentLocation()
            val statusFromDb = dbHelper.getKnownStatus(cell.mnc, cell.tac, cell.cellId, cell.mcc, currentLoc?.latitude, currentLoc?.longitude)
            
            if (statusFromDb != VerificationStatus.PENDING) {
                verificationCache[cacheKey] = statusFromDb
                updateFlowWithStatus(cell.cellId, statusFromDb)
                // Actualizar la fila recién insertada que quedó en PENDING
                dbHelper.updateVerificationStatus(
                    cell.mnc, cell.tac, cell.cellId,
                    statusFromDb,
                    mcc = cell.mcc
                )
                return@launch
            }

            // 3. Consultar APIs si es realmente nueva
            var finalStatus = VerificationStatus.PENDING
            appendLog("[API]", "Nueva antena detectada. Verificando firmas en la nube...")

            // Intento WiGLE
            if (wigleApiName.isNotBlank() && wigleApiToken.isNotBlank()) {
                val (s, data) = WigleClient.tryWigleSync(cell, wigleApiName, wigleApiToken, isProxyEnabled, client)
                if (s == VerificationStatus.VERIFIED && data != null) {
                    val lat = data.optDouble("trilat", data.optDouble("lat", Double.NaN))
                    val lon = data.optDouble("trilong", data.optDouble("lon", Double.NaN))
                    if (!lat.isNaN() && !lon.isNaN() && isValidCoordinate(lat, lon)) {
                        processSuccessfulVerification(lat, lon, cell, cacheKey, neighbors, "WiGLE")
                        return@launch
                    } else {
                        appendLog("[API]", "WiGLE: coordenadas inválidas descartadas.")
                    }
                }
                finalStatus = s
            }
            
            // Fallback OpenCellID
            if (finalStatus != VerificationStatus.VERIFIED && openCellIdKey.isNotBlank() && !openCellIdKey.startsWith("pk.YOUR")) {
                val (s, data) = OpenCellIdClient.tryOpenCellIdSyncWithData(cell, openCellIdKey, isProxyEnabled, client)
                if (s == VerificationStatus.VERIFIED && data != null) {
                    val lat = data.optDouble("lat", Double.NaN)
                    val lon = data.optDouble("lon", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN() && isValidCoordinate(lat, lon)) {
                        processSuccessfulVerification(lat, lon, cell, cacheKey, neighbors, "OpenCellID")
                        return@launch
                    } else {
                        appendLog("[API]", "OpenCellID: coordenadas inválidas descartadas.")
                    }
                }
                finalStatus = s
            }

            // Guardar resultado negativo si ninguna lo encontró
            if (finalStatus == VerificationStatus.NOT_FOUND) {
                verificationCache[cacheKey] = VerificationStatus.NOT_FOUND
                dbHelper.updateVerificationStatus(cell.mnc, cell.tac, cell.cellId, VerificationStatus.NOT_FOUND, mcc = cell.mcc)
                appendLog("[API]", "Antena no identificada en bases públicas.")
                updateFlowWithStatus(cell.cellId, VerificationStatus.NOT_FOUND)
            }
        }
    }

    private fun updateFlowWithStatus(cid: String, status: VerificationStatus) {
        scope.launch(Dispatchers.Main) {
            _cellFlow.value = _cellFlow.value.map { 
                if (it.cellId == cid) it.copy(verified = status) else it
            }
        }
    }

    private fun processSuccessfulVerification(lat: Double, lon: Double, cell: CellData, cacheKey: String, neighbors: List<CellData>, source: String) {
        appendLog("[API]", "Validación OK ($source). Firmas geográficas obtenidas.")
        verificationCache[cacheKey] = VerificationStatus.VERIFIED
        dbHelper.updateVerificationStatus(
            cell.mnc,
            cell.tac,
            cell.cellId,
            VerificationStatus.VERIFIED,
            lat,
            lon,
            cell.mcc)
        
        val loc = getCurrentLocation()
        val history = if (loc != null && cell.cellId != "N/A") {
            dbHelper.getPreviousCellHistory(cell.cellId, cell.mnc, cell.tac, loc)
        } else emptyList()

        val updatedActive = cell.copy(verified = VerificationStatus.VERIFIED, lat = lat, lon = lon)
        val analyzedActive = ThreatAnalyzer.analyzeThreats(
            active = updatedActive,
            neighbors = neighbors,
            isHardwareCipheringActive = isHardwareCipheringActive,
            isHardwareCipheringAvailable = isHardwareCipheringAvailable,
            cellChangeHistory = cellChangeHistory,
            currentLocation = loc,
            preloadedHistory = history,
            isWifiActive = isWifiConnected()
        )
        
        scope.launch(Dispatchers.Main) {
            _cellFlow.value = _cellFlow.value.map { 
                if (it.cellId == cell.cellId) analyzedActive.copy(isRegistered = it.isRegistered) else it 
            }
            
            if (analyzedActive.isSuspicious && analyzedActive.isRegistered) {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 200)
                checkAlerts(analyzedActive)
            }
        }
    }

    private fun checkAlerts(cell: CellData) {
        val cid = cell.cellId
        val dbm = cell.dbm
        val net = cell.networkType

        if (cid != prevCid) {
            _dbmHistory.value = emptyList()
            appendLog("[RADIO]", "Handover celular completado -> Nueva celda CID: $cid ($net)")
            generateAuditLog(cell) 

            val currentTime = System.currentTimeMillis()
            cellChangeHistory.add(Pair(cid, currentTime))
            cellChangeHistory.removeAll { currentTime - it.second > 10000L }

            if (!cell.heuristicReport.pingPongPassed) {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_PIP, 300)
                appendLog("[SEC]", "🚨 ¡ALERTA! Efecto Ping-Pong detectado en parado.")
                Log.e("MiniIC", "ANOMALÍA: Efecto Ping-Pong detectado en parado.")
            } else if (cellChangeHistory.size >= 3) {
                val speedKmh = (getCurrentLocation()?.speed ?: 0f) * 3.6f
                appendLog("[RADIO]", "Ping-Pong detectado a ${String.format(Locale.getDefault(), "%.1f", speedKmh)} km/h. Ignorando alerta.")
            }

            // --- NUEVO: REGISTRO INMEDIATO DE LA NUEVA CELDA ---
            scope.launch(Dispatchers.IO) {
                val loc = getCurrentLocation()
                dbHelper.logConnection(
                    netType = net, 
                    cid = cid, 
                    mnc = cell.mnc, 
                    tac = cell.tac, 
                    mcc = cell.mcc, 
                    dbm = dbm, 
                    verified = cell.verified, // Será PENDING inicialmente
                    score = cell.securityScore,
                    failedHeuristics = cell.suspiciousReason ?: "OK",
                    lat = loc?.latitude,
                    lon = loc?.longitude,
                    pci = cell.pci,
                    arfcn = cell.arfcn
                )
            }
            // --------------------------------------------------

            prevCid = cid
        }

        if (dbm != -999 && dbm != Int.MAX_VALUE) {
            val currentHistory = _dbmHistory.value.toMutableList()
            currentHistory.add(dbm)
            if (currentHistory.size > 50) currentHistory.removeAt(0)
            _dbmHistory.value = currentHistory

            val currentTa = cell.timingAdvance
            if (currentTa != null && currentTa != Int.MAX_VALUE) {
                val currentGeo = _geoHistory.value.toMutableList()
                currentGeo.add(currentTa.toFloat())
                if (currentGeo.size > 50) currentGeo.removeAt(0)
                _geoHistory.value = currentGeo
            }
        }

        if (isStrongSignalAlarmEnabled && dbm != -999 && dbm >= alarmThreshold) {
            val now = System.currentTimeMillis()
            if (now - lastStrongSignalAlarmTime > 60000L) { // Limit to once per minute
                lastStrongSignalAlarmTime = now
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 150)
                Log.w("MiniIC", "Warning: High power signal detected: $dbm dBm")
                appendLog("[SEC]", "⚠️ Señal sospechosamente fuerte detectada: $dbm dBm")
            }
        }

        if (prevNetType != null && prevDbm != null) {
            val isPrevSecure = prevNetType!!.contains("4G") || prevNetType!!.contains("5G")
            val isCurrentInsecure = net.contains("2G") || net.contains("3G")
            if (isPrevSecure && isCurrentInsecure && prevDbm!! >= -85) {
                toneGenerator?.startTone(ToneGenerator.TONE_SUP_ERROR, 500)
                Log.e("MiniIC", "CRITICAL: Downgrade attack detected! Pre-transition signal: $prevDbm dBm")
                triggerAirplaneMode()
            }
        }

        if (cell.isSuspicious) {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 200)
            Log.e("MiniIC", "THREAT DETECTED: ${cell.suspiciousReason}")
        }

        if (is3gAirplaneModeEnabled && (net.contains("3G") || net.contains("2G"))) {
            triggerAirplaneMode()
        }

        prevNetType = net
        prevDbm = dbm
    }

    private fun triggerAirplaneMode() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAirplaneTriggerTime < 60000L) return
        lastAirplaneTriggerTime = currentTime
        Log.e("MiniIC", "CRITICAL: 2G/3G network detected! Fallback to manual settings.")
        appendLog("[SEC]", "🚨 CRÍTICO: Red 2G/3G detectada. Abriendo ajustes para Modo Avión manual.")
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
        try {
            val isAirplaneOn = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            if (!isAirplaneOn) {
                Settings.Global.putInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
                val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply { putExtra("state", true) }
                sendBroadcast(intent)
            }
        } catch (_: SecurityException) {
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            startActivity(intent)
        }
    }

    private fun updateNotification(cell: CellData) {
        val vStatus = when(cell.verified) {
            VerificationStatus.VERIFIED -> "✅ VERIFICADA"
            VerificationStatus.NOT_FOUND -> "❌ NO REGISTRADA"
            VerificationStatus.ERROR -> "⚠️ ERROR API"
            VerificationStatus.PENDING -> "⏳ PENDIENTE"
        }
        val content = "$vStatus | ${cell.networkType} | CID: ${cell.cellId} | ${cell.dbm} dBm"
        updateNotificationText(content)
    }

    private fun updateNotificationText(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = Intent(this, MiniICService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ICdetection: Monitoreo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        val chan = NotificationChannel(CHANNEL_ID, "miniIC Channel", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(chan)
    }

    private fun getNetworkOperatorMcc(): String {
        val operator = telephonyManager.networkOperator
        return if (operator != null && operator.length >= 3) operator.substring(0, 3) else "N/A"
    }

    private fun getNetworkOperatorMnc(): String {
        val operator = telephonyManager.networkOperator
        return if (operator != null && operator.length > 3) operator.substring(3) else "N/A"
    }

    private fun getLteSpecificType(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lastDisplayInfo?.let { info ->
                val override = info.overrideNetworkType
                if (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA || override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) return "5G NR (NSA)"
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val ssStr = telephonyManager.serviceState?.toString() ?: ""
                    if (ssStr.contains("nrState=CONNECTED") || ssStr.contains("nrState=NOT_RESTRICTED")) return "5G NR (NSA)"
                } catch (_: Exception) {}
            }
        }
        return "4G LTE"
    }

    private fun generateAuditLog(cell: CellData) {
        _auditStatus.value = "Auditoría en curso..."
        appendLog("[AUDIT]", "--- INICIANDO CICLO DE AUDITORÍA (11 REGLAS) ---")
        val report = cell.heuristicReport
        val results = mapOf(
            "1. Celda Aislada" to report.isolatedCellPassed,
            "2. Estabilidad Potencia" to report.powerJumpPassed,
            "3. Consistencia MCC" to report.mccConsistencyPassed,
            "4. Límite MNC" to report.mncCountPassed,
            "5. Validación Regional TAC" to report.tacDeviationPassed,
            "6. Geometría (TA)" to report.taDistancePassed,
            "7. Espectro Fantasma" to report.ghostNeighborsPassed,
            "8. Sanidad ARFCN" to report.arfcnSanityPassed,
            "9. Cifrado Hardware" to report.hardwareCipheringPassed,
            "10. Anti Ping-Pong" to report.pingPongPassed,
            "11. Consistencia Geográfica (Cell ID móvil)" to report.mobileCellIdPassed
        )

        results.forEach { (regla, pasado) ->
            val status = if (pasado) "PASSED" else "FAILED"
            appendLog("[HEUR]", "$regla: $status")
        }

        appendLog("[AUDIT]", "Resultado Global: ${cell.securityScore}% de seguridad.")
        if (cell.isSuspicious) appendLog("[SEC]", "🚨 CRÍTICO: Antena sospechosa detectada: ${cell.suspiciousReason}")
        else appendLog("[SYS]", "✅ Entorno validado como SEGURO.")
        _auditStatus.value = "Auditoría completada ✅"
    }

    @RequiresApi(Build.VERSION_CODES.S)
    inner class ImsiCatcherSecurityCallback : TelephonyCallback(), TelephonyCallback.CellInfoListener {
        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {}
        
        @Suppress("unused")
        fun onCipheringStatusChanged(params: Any) {
            isHardwareCipheringAvailable = true
            appendLog("[MODEM]", "Evento de cambio en estado de cifrado detectado (Reflexión).")
            try {
                val getStatusMethod = params::class.java.getMethod("getCipheringStatus")
                val status = getStatusMethod.invoke(params) as Int
                if (status == 0) {
                    isHardwareCipheringActive = false
                    appendLog("[MODEM]", "¡ALERTA L1! Protocolo de cifrado anulado (Reflexión)")
                    triggerSecurityAlert("¡PELIGRO! Conexión celular NO CIFRADA (Cifrado nulo detectado)")
                } else {
                    isHardwareCipheringActive = true
                    appendLog("[MODEM]", "SYS: Cifrado de hardware validado como ACTIVO.")
                }
            } catch (_: Exception) {
                try {
                    val statusField = params::class.java.getField("cipheringStatus")
                    val status = statusField[params] as Int
                    if (status == 0) {
                        isHardwareCipheringActive = false
                        appendLog("[MODEM]", "¡ALERTA L1! Protocolo de cifrado anulado (Reflexión)")
                        triggerSecurityAlert("¡PELIGRO! Conexión celular NO CIFRADA (Cifrado nulo detectado)")
                    } else {
                        isHardwareCipheringActive = true
                        appendLog("[MODEM]", "SYS: Cifrado de hardware validado como ACTIVO.")
                    }
                } catch (_: Exception) {
                    // Ignored
                }
            }
        }

        @Suppress("unused", "UNUSED_PARAMETER")
        fun onCellularIdentifierDisclosure(params: Any) {
            appendLog("[MODEM]", "🚨 CRÍTICO: Detección de Disclosure de Identificador Celular (Reflexión)!")
            triggerSecurityAlert("¡ALERTA CRÍTICA! Intento de extracción de IMSI detectado por la red")
        }
    }

    companion object {
        const val CHANNEL_ID = "miniic_channel"
        const val NOTIFICATION_ID = 202
        const val ACTION_STOP = "com.alexisgordr.icdetector.STOP"
    }
}
