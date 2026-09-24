package com.alexisgordr.icdetector.service

import com.alexisgordr.icdetector.R

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.provider.Settings
import android.telephony.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.alexisgordr.icdetector.core.ThreatAnalyzer
import com.alexisgordr.icdetector.models.*
import com.alexisgordr.icdetector.storage.CellDbHelper
import com.alexisgordr.icdetector.telephony.CellParser
import com.alexisgordr.icdetector.forensics.ForensicRecorder
import com.alexisgordr.icdetector.forensics.TrustContradictionTransitionTracker
import com.alexisgordr.icdetector.forensics.TrustContradictionSignal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

class MiniICService : Service() {

    private val binder = LocalBinder()
    // Red de seguridad: si alguna corrutina lanzara una excepción no capturada, se registra y la
    // app sigue viva, en vez de que el handler por defecto la tumbe. Con SupervisorJob, además, el
    // fallo de una corrutina no cancela a las demás.
    private val coroutineErrorHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("MiniIC", "Excepción no capturada en corrutina: ${throwable.message}", throwable)
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + coroutineErrorHandler)
    
    private val _cellFlow = MutableStateFlow<List<CellData>>(emptyList())
    val cellFlow: StateFlow<List<CellData>> = _cellFlow

    private val _liveLogs = MutableStateFlow(listOf("[SYS] ICdetector Engine Iniciado...", "[SYS] Esperando hooks del módem..."))
    val liveLogs: StateFlow<List<String>> = _liveLogs

    private val _dbmHistory = MutableStateFlow<List<Int>>(emptyList())
    val dbmHistory: StateFlow<List<Int>> = _dbmHistory

    private val _rsrqHistory = MutableStateFlow<List<Int>>(emptyList())
    val rsrqHistory: StateFlow<List<Int>> = _rsrqHistory

    private val _geoHistory = MutableStateFlow<List<Float>>(emptyList())
    val geoHistory: StateFlow<List<Float>> = _geoHistory

    private val _auditStatus = MutableStateFlow("")
    val auditStatus: StateFlow<String> = _auditStatus

    // Arranca en "N/A": hasta que la sonda mida algo de verdad no se afirma "OK".
    private val _networkLatencyState = MutableStateFlow<String>("N/A")
    val networkLatencyState: StateFlow<String> = _networkLatencyState

    private fun appendLog(type: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val newLog = "[$timestamp] $type $message"
        _liveLogs.update { current -> (current + newLog).takeLast(40) }
    }

    private lateinit var dbHelper: CellDbHelper
    private lateinit var mobilityFamiliarity: com.alexisgordr.icdetector.core.MobilityFamiliarityEngine
    private var lastMobilityLog: String? = null
    private lateinit var forensicRecorder: ForensicRecorder
    private val trustContradictionTransitions = TrustContradictionTransitionTracker()
    private val stableSiteMotion = com.alexisgordr.icdetector.core.MotionClassifier()
    @Volatile private var latestMotionEvidence = com.alexisgordr.icdetector.core.MotionEvidence(
        com.alexisgordr.icdetector.core.MotionState.UNKNOWN,
        reason = com.alexisgordr.icdetector.core.MotionReason.NO_RECENT_FIX
    )
    private var stableSitePreviousIdentity: String? = null
    private var lastStableSiteLog: String? = null
    private var stableSiteIntensiveActive = false
    private val forensicDispatcher = Dispatchers.IO.limitedParallelism(1)
    private lateinit var notificationController: ServiceNotificationController
    private lateinit var databaseMaintenance: DatabaseMaintenance
    private lateinit var latencyMonitor: NetworkLatencyMonitor
    private lateinit var legacyNetworkProtection: LegacyNetworkProtection
    private lateinit var transitionTracker: ServingTransitionTracker
    private lateinit var collectionPower: CollectionPowerController
    private lateinit var locationController: LocationCollectionController
    private lateinit var telephonyController: TelephonyCollectionController
    private lateinit var auditController: AuditLogController
    private lateinit var apiCoordinateValidator: ApiCoordinateValidator
    private lateinit var verificationController: ExternalVerificationController
    private lateinit var observationPersistence: ObservationPersistenceController
    private lateinit var securityAlerts: SecurityAlertController
    private lateinit var telemetryHistory: TelemetryHistoryController
    // v2.3.3 — Vigilancia de la recolección: escrituras fallidas e interrupciones.
    private lateinit var collectionHealthController: CollectionHealthController
    private var toneGenerator: ToneGenerator? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var isScreenOn = true
    @Volatile private var isUiVisible = false
    private var collectionPausedForCriticalBattery = false
    private var isServiceRunning = false
    
    private var isHardwareCipheringActive = false
    private var isHardwareCipheringAvailable = false
    private var lastGpsTriggerTime = 0L

    // v2.1: la confirmación temporal vive en core/TemporalConfidence para poder testearla de
    // extremo a extremo (ver ScenarioTest). El servicio solo la usa.
    private val temporalConfidence = com.alexisgordr.icdetector.core.TemporalConfidence(CONFIRMATION_CYCLES)
    private val threatEpisodeTracker = com.alexisgordr.icdetector.core.ThreatEpisodeTracker()
    private val isolatedCellConfidence = com.alexisgordr.icdetector.core.IsolatedCellConfidence()
    @Volatile private var intensiveMonitoringUntilMs = 0L
    private var intensiveMonitoringStartedAtMs = 0L
    // Todas las lecturas comparten cachés, transición y confirmación temporal. Serializarlas evita
    // que dos callbacks publiquen la firma de una celda con el historial calculado para otra.
    private val cellProcessingMutex = Mutex()
    private val enqueuedCellProcessing = AtomicLong(0L)
    private var lastIncidentIdentity: String? = null
    // v2.1 — ¿el TA de este módem es una medida o un campo sin rellenar? Ver TimingAdvanceSanity.
    private val taSanity = com.alexisgordr.icdetector.core.TimingAdvanceSanity()

    // Caché del prefetch de BD (heurísticas 11 y 13) para no repetir las consultas SQLite
    // en cada ciclo cuando estás parado en la misma celda. Se invalida al cambiar de celda,
    // al moverte > 150 m (la ubicación afecta al baseline) o al superar el TTL.
    // NO toca el pipeline ni el flujo de confirmación: analyzeThreats sigue ejecutándose
    // cada ciclo igual que antes; solo se evita repetir el trabajo de base de datos.
    private var cachedCellSignature = ""
    private var cachedLocationLat = 0.0
    private var cachedLocationLon = 0.0
    private var cachedHistory: List<HistoryRecord> = emptyList()
    private var cachedBaseline: SignalBaseline? = null
    private var cachedRfStability: CellRfStability? = null
    private var cachedReputation: CellReputation? = null
    private var cachedFingerprint: CellRfFingerprint? = null
    private var cachedLocalTrustEvidence = com.alexisgordr.icdetector.models.LocalCellTrustEvidence()
    // v2.1 — Posición de la antena (api_lat/api_lon) para mostrar la distancia. Solo display.
    private val apiLocationCache = ConcurrentHashMap<String, Pair<Double, Double>>()
    private var cachedRfSignature = ""
    private var cachedRfTimestamp = 0L
    private var cacheTimestamp = 0L
    private val CELL_CACHE_TTL = 60_000L

    // Latency anomaly detection
    var openCellIdKey: String = ""
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    // Cliente solo para los pings de latencia: timeout corto (4s) para que una medición
    // no bloquee la coroutine. Reutiliza el connection pool del client general (eficiente).
    private val latencyClient = client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()
    private val verificationCache = ConcurrentHashMap<String, VerificationStatus>()
    // Marca temporal del último ERROR por celda, para permitir reintentos sin saturar la API
    private val lastVerificationErrorTime = ConcurrentHashMap<String, Long>()
    // Marca temporal del último NOT_FOUND por celda. A diferencia de ERROR (fallo de red
    // transitorio, reintento a 60s), NOT_FOUND es una respuesta afirmativa de la API ("no está
    // en la base"). Reintentamos en vivo cada 1h por si una torre legítima recién desplegada se
    // incorpora a OpenCellID y debe reclasificarse a VERIFIED. Una vez VERIFIED, el guard
    // deja de consultarla para siempre (no hay reintento sobre celdas verificadas).
    private val lastNotFoundTime = ConcurrentHashMap<String, Long>()
    // TTL de reverificación de NOT_FOUND: 1h. En caliente, sin reiniciar la app.
    private val NOT_FOUND_REVERIFY_TTL = 60L * 60L * 1000L
    // REJECTED no es una negativa: es una consulta inconclusa. Se le da otra oportunidad antes,
    // pero no cada ciclo de radio, para no consumir la cuota de ambas APIs en pocos minutos.
    private val lastRejectedTime = ConcurrentHashMap<String, Long>()
    // Durante la campaña, una respuesta inconclusa no debe consumir cuota cada 15 minutos.
    // Una hora mantiene el reintento sin convertir un fallo persistente de una fuente en bucle.
    private val REJECTED_REVERIFY_TTL = 60L * 60L * 1000L

    var alarmThreshold = -50f
    var isStrongSignalAlarmEnabled = true
    var is3gAirplaneModeEnabled = true
    var isProxyEnabled = false
    var isLatencyDetectionEnabled: Boolean = false

    private var prevCid: String? = null
    // Fix #1: guarda el cellId cuya alarma ya se ha persistido en el episodio actual, para no
    // registrar la misma evidencia en cada ciclo. Se resetea en cada cambio de celda (handover).
    // Estado para heurística 14 (band downgrade intra-LTE). Guardan la última celda
    // REGISTRADA analizada; el buffer de tendencia sobrevive al handover para detectar si
    // la señal venía degradándose progresivamente (excepción del garaje/sótano).
    private var prevBand: Int? = null
    private var prevRegisteredDbm: Int? = null
    private val recentRegisteredDbmTrend = CopyOnWriteArrayList<Int>()
    private val cellChangeHistory = CopyOnWriteArrayList<Pair<String, Long>>()

    inner class LocalBinder : Binder() {
        fun getService(): MiniICService = this@MiniICService
    }

    fun forceRefresh() {
        _cellFlow.value = emptyList()
        requestFreshCellInfo()
    }

    /** Selects the faster visual refresh only while the activity is in the foreground. */
    fun setUiVisible(visible: Boolean) {
        val becameVisible = visible && !isUiVisible
        isUiVisible = visible
        if (becameVisible && ::telephonyController.isInitialized) requestFreshCellInfo()
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        dbHelper = CellDbHelper(this)
        mobilityFamiliarity = com.alexisgordr.icdetector.core.MobilityFamiliarityEngine(dbHelper)
        scope.launch(Dispatchers.IO) {
            val recovery = mobilityFamiliarity.recover(System.currentTimeMillis())
            recovery.event?.let { appendLog("[MOBILITY]", "$it id=${recovery.tripId}") }
        }
        // v2.8.0 — La escritura forense avisa cuando se rompe. Tres inserts fallidos seguidos
        // (disco lleno, base bloqueada) y la captura deja de guardar sin decir nada: el mismo
        // fallo silencioso que CollectionHealth arregló para el historial.
        forensicRecorder = ForensicRecorder(dbHelper) { failing ->
            if (failing) {
                appendLog("[SYS]", "⚠ Captura forense degradada — las muestras no se están guardando.")
            } else {
                appendLog("[SYS]", "Captura forense restablecida.")
            }
        }
        notificationController = ServiceNotificationController(this, MiniICService::class.java)
        databaseMaintenance = DatabaseMaintenance(dbHelper) { appendLog("[SYS]", it) }
        latencyMonitor = NetworkLatencyMonitor(
            client = latencyClient,
            log = { appendLog("[NET]", it) },
            publishState = { _networkLatencyState.value = it },
            onPersistentAnomaly = { showLatencyAlert() }
        )
        legacyNetworkProtection = LegacyNetworkProtection(
            context = this,
            notifications = notificationController,
            log = { appendLog("[SEC]", it) },
            playWarningTone = { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 500) }
        )
        transitionTracker = ServingTransitionTracker(dbHelper) { appendLog("[H16]", it) }
        collectionPower = CollectionPowerController(this)
        locationController = LocationCollectionController(
            context = this,
            scope = scope,
            log = { appendLog("[GPS]", it) },
            onStreamFixAvailable = { location ->
                observeMotionFix(location)
                if (location.accuracy < 100f) onGpsAvailable()
            },
            onPreciseFixAccepted = { location ->
                observeMotionFix(location)
                persistPreciseLocation(location)
            }
        )
        apiCoordinateValidator = ApiCoordinateValidator(
            db = dbHelper,
            currentLocation = { getCurrentLocation() },
            lastAcceptedLocation = { locationController.lastAcceptedLocation() },
            log = { appendLog("[API]", it) }
        )
        telephonyController = TelephonyCollectionController(
            context = this,
            log = { appendLog("[SYS]", it) },
            deliver = { cells, fresh, registered ->
                processCellInfo(cells, isFreshDelivery = fresh, fromRegisteredCallback = registered)
            }
        )
        auditController = AuditLogController(
            log = ::appendLog,
            publishStatus = { _auditStatus.value = it },
            zeroOnlyCellCount = { taSanity.zeroOnlyCellCount }
        )
        verificationController = ExternalVerificationController(
            scope = scope,
            db = dbHelper,
            client = client,
            coordinateValidator = apiCoordinateValidator,
            apiKey = { openCellIdKey },
            proxyEnabled = { isProxyEnabled },
            currentLocation = { getCurrentLocation() },
            cache = verificationCache,
            errorTimes = lastVerificationErrorTime,
            notFoundTimes = lastNotFoundTime,
            rejectedTimes = lastRejectedTime,
            log = { appendLog("[API]", it) },
            publish = ::updateFlowWithStatus,
            cacheApiLocation = { key, lat, lon -> apiLocationCache[key] = lat to lon },
            onVerified = { auditController.logVerificationOutcome(it) },
            requestFreshCellInfo = ::requestFreshCellInfo
        )
        observationPersistence = ObservationPersistenceController(
            scope = scope,
            db = dbHelper,
            location = { getCurrentLocation() },
            onWrite = ::noteWriteResult,
            onPeriodicMissingLocation = { timestamp ->
                locationController.markCoordinatesPending(timestamp)
                requestHighAccuracyFix()
            }
        )
        securityAlerts = SecurityAlertController(
            tone = { toneGenerator },
            legacyProtection = legacyNetworkProtection,
            log = { appendLog("[SEC]", it) },
            requestPreciseLocation = { requestHighAccuracyFix() },
            persistConfirmedAlarm = observationPersistence::recordConfirmedAlarm,
            strongSignalEnabled = { isStrongSignalAlarmEnabled },
            strongSignalThreshold = { alarmThreshold },
            legacyProtectionEnabled = { is3gAirplaneModeEnabled }
        )
        telemetryHistory = TelemetryHistoryController(_dbmHistory, _rsrqHistory, _geoHistory)

        // Poda de histórico antiguo al arrancar (en segundo plano, no bloquea onCreate).
        // Evita que la tabla crezca sin límite registrando 24/7. v2.3.3: conserva
        // DEFAULT_RETENTION_DAYS (120) — antes 60, que se quedaban CORTOS para una campaña de 90
        // días y borraban el primer mes justo al abrir la app para exportar.
        databaseMaintenance.claimRun(force = true)
        scope.launch(Dispatchers.IO) {
            // Si el proceso anterior terminó durante un episodio, no puede quedar marcado
            // eternamente como "en curso". Se conserva y se cierra como interrumpido.
            try {
                dbHelper.closeOpenIncidents(activeIdentity = null, interrupted = true)
                dbHelper.interruptOpenForensicCases()
            } catch (_: Exception) {}
            databaseMaintenance.run()
        }
        
        val prefs = getSharedPreferences("miniic_prefs", MODE_PRIVATE)
        collectionHealthController = CollectionHealthController(prefs)
        openCellIdKey = (prefs.getString("opencellid_key", "") ?: "").trim()
        // WiGLE ya no participa en la verificación. Elimina secretos y estado heredados.
        prefs.edit()
            .remove("wigle_api_name")
            .remove("wigle_api_token")
            .remove("wigle_rate_limited_until")
            .apply()
        // v2.3.3 — Continuidad de la recolección. Si el proceso murió (reinicio del móvil, OTA,
        // batería agotada) nadie lo anunciaba: la notificación se iba con el proceso y la
        // recolección quedaba parada sin dejar rastro. Ahora el hueco se calcula al arrancar y se
        // deja escrito, para que una interrupción sea un hecho observable y no una sorpresa en
        // diciembre al ver un agujero en el CSV.
        collectionHealthController.restore()?.let { appendLog("[SYS]", it) }
        isProxyEnabled = prefs.getBoolean("proxy_enabled", false)
        isLatencyDetectionEnabled = prefs.getBoolean("latency_detection_enabled", false)
        restoreTaSanityEvidence()            // evidencia de TA superviviente a reinicios (v2.5)

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        notificationController.createChannels()
        // En Android 14+ es obligatorio pasar el foregroundServiceType en startForeground.
        // Además, startForeground puede lanzar excepción (p. ej. ForegroundServiceStartNotAllowed
        // o SecurityException) si el permiso de ubicación no está concedido o el SO reinicia el
        // servicio en un estado restringido. Si eso ocurre, paramos limpiamente en vez de crashear.
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notificationController.foregroundNotification(getString(R.string.monitoring_active)), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notificationController.foregroundNotification(getString(R.string.monitoring_active)))
            }
        } catch (e: Exception) {
            Log.e("MiniIC", "No se pudo iniciar el servicio en primer plano: ${e.message}", e)
            stopSelf()
            return
        }

        appendLog("[SYS]", "Ubicación continua activa (24/7). Consumo de batería elevado por diseño: las coordenadas son necesarias para H11, H13 y H16.")

        registerTelephonyCallback()
        registerDisplayInfoCallback()
        registerScreenReceiver()

        collectionPausedForCriticalBattery = isBatteryCritical()
        if (collectionPausedForCriticalBattery) {
            appendLog("[SYS]", "⚠ Batería crítica (<${CollectionPowerController.CRITICAL_PERCENT} %): ubicación continua en pausa hasta conectar el cargador.")
        } else {
            ensureCollectionWakeLock()
            locationController.startContinuousUpdates()
        }

        scope.launch(Dispatchers.Default) {
            var wasAirplaneModeOn = false
            while (isActive && isServiceRunning) {
                val intensive = SystemClock.elapsedRealtime() < intensiveMonitoringUntilMs
                val delayTime = when {
                    isUiVisible -> UI_VISIBLE_SCAN_INTERVAL_MS
                    isScreenOn || intensive -> SCREEN_ON_SCAN_INTERVAL_MS
                    else -> SCREEN_OFF_SCAN_INTERVAL_MS
                }

                try {
                    val batteryCritical = isBatteryCritical()
                    if (batteryCritical) {
                        locationController.stopContinuousUpdates()
                        releaseCollectionWakeLock()
                        if (!collectionPausedForCriticalBattery) {
                            collectionPausedForCriticalBattery = true
                            appendLog("[SYS]", "⚠ Batería crítica (<${CollectionPowerController.CRITICAL_PERCENT} %): ubicación continua en pausa hasta conectar el cargador.")
                            updateNotificationText(getString(R.string.critical_battery_gps_paused))
                        }
                    } else {
                        ensureCollectionWakeLock()
                        locationController.startContinuousUpdates()
                        if (collectionPausedForCriticalBattery) {
                            collectionPausedForCriticalBattery = false
                            appendLog("[SYS]", "Alimentación recuperada: ubicación continua y recolección 24/7 reanudadas.")
                        }
                    }

                    val isAirplaneModeOn = Settings.Global.getInt(
                        contentResolver,
                        Settings.Global.AIRPLANE_MODE_ON, 0
                    ) != 0

                    if (isAirplaneModeOn) {
                        _cellFlow.value = emptyList()
                        updateNotificationText(getString(R.string.no_signal_airplane))
                        appendLog("[SYS]", "⚠️ Modo Avión activo. Suspendiendo escaneo.")
                        wasAirplaneModeOn = true
                    } else {
                        if (wasAirplaneModeOn) {
                            appendLog("[SYS]", "Conexión restaurada. Analizando nueva celda activa...")
                            // Limpiar cache no-verificada para forzar re-consulta a DB y APIs si corresponde
                            verificationCache.entries.removeIf { it.value != VerificationStatus.VERIFIED }
                            wasAirplaneModeOn = false
                            // Al salir de modo avión forzamos un fix GPS fresco SÍ o SÍ (sin
                            // debounce): es un evento raro y crítico —si hubiera un IMSI-catcher
                            // empujándote o saltando de celda, necesitamos coordenadas reales ya,
                            // no cacheadas. El coste extra de batería puntual es aceptable.
                            locationController.requestPreciseFix(force = true)
                        }
                        requestFreshCellInfo()
                        checkLatencyAnomaly()
                        val activeForPrune = _cellFlow.value.firstOrNull { it.isRegistered }
                        pruneCaches(
                            activeForPrune?.cellId,
                            activeForPrune?.identityKey
                        )
                        scheduleDailyDatabaseMaintenance()

                        // En reposo (pantalla apagada) con una celda esperando coordenadas
                        // frescas: despertamos el GPS despacio (cada 90 s, hasta 10 min) hasta
                        // conseguir un fix fresco que la rellene. Se detiene solo en cuanto se
                        // resuelve (awaitingFreshCoords pasa a false). Con pantalla encendida lo
                        // cubre el stream, así que esto es solo para reposo.
                        locationController.retryPendingCoordinatesIfDue(isScreenOn)
                    }
                } catch (ce: CancellationException) {
                    throw ce  // respetar la cancelación limpia del scope
                } catch (e: Exception) {
                    // Un fallo puntual de un ciclo NO debe detener el escaneo continuo.
                    appendLog("[SYS]", "⚠️ Error en ciclo de escaneo (se continúa): ${e.message}")
                }

                delay(delayTime.milliseconds)
            }
        }
    }

    /** Aplica retención y límite forense también en servicios que permanecen vivos durante meses. */
    private fun scheduleDailyDatabaseMaintenance() {
        if (databaseMaintenance.claimRun()) {
            scope.launch(Dispatchers.IO) { databaseMaintenance.run() }
        }
    }

    /**
     * Poda de mapas en memoria (NO toca la BD). Evita el crecimiento ilimitado de las
     * cachés indexadas por celda en sesiones muy largas. Es barato: solo actúa cuando se
     * supera el cap o hay errores caducados.
     */
    private fun pruneCaches(activeCellId: String?, activeCacheKey: String?) {
        val now = System.currentTimeMillis()
        // Errores de verificación de más de 1 h: ya no deben bloquear reintentos.
        lastVerificationErrorTime.entries.removeIf { now - it.value > 3_600_000L }
        if (apiLocationCache.size > 256) {
            if (activeCacheKey != null) {
                apiLocationCache.keys.removeIf { it != activeCacheKey }
            } else {
                // Sin celda activa no hay una clave preferente. Reducir al límite en lugar de
                // vaciar toda la caché evita perder de golpe todas las distancias conocidas.
                apiLocationCache.keys.take(apiLocationCache.size - 256).forEach(apiLocationCache::remove)
            }
        }
        // NOT_FOUND de más de 1h (el TTL): se retira la marca para acotar el mapa; al volver a
        // observar la celda, el guard la reverificará (last ausente = fuera de ventana).
        lastNotFoundTime.entries.removeIf { now - it.value > NOT_FOUND_REVERIFY_TTL }
        // Las respuestas inconclusas tienen su propia ventana, más corta que una negativa real.
        lastRejectedTime.entries.removeIf { now - it.value > REJECTED_REVERIFY_TTL }
        // Cap de seguridad: si se excede, se descarta el exceso (las celdas afectadas
        // simplemente vuelven a aprender baseline / re-verificarse; sin impacto de correctitud).
        // Estas son ConcurrentHashMap (sin orden de inserción), así que la purga por .take() es
        // ARBITRARIA. Por eso EXCLUIMOS la celda activa, para no borrar justo su baseline / estado y
        // forzar una re-verificación innecesaria en viajes largos (>MAX_TRACKED_CELLS celdas).
        // OJO: cada caché usa una CLAVE distinta:
        //   - latencyHistory   -> clave = cellId pelado
        //   - verificationCache -> clave = "mcc-mnc-tac-cellId" (compuesta)
        // por eso se pasan y comparan las dos por separado.
        latencyMonitor.prune(activeCellId, MAX_TRACKED_CELLS)
        if (verificationCache.size > MAX_TRACKED_CELLS) {
            verificationCache.keys.filter { it != activeCacheKey }
                .take(verificationCache.size - MAX_TRACKED_CELLS)
                .forEach { verificationCache.remove(it) }
        }
    }

    private fun checkLatencyAnomaly() {
        if (!isLatencyProbeActive()) {
            latencyMonitor.reset()
            return
        }
        val activeCellId = _cellFlow.value.firstOrNull { it.isRegistered }?.cellId ?: return
        if (!latencyMonitor.isDue()) return
        scope.launch(Dispatchers.IO) { latencyMonitor.check(activeCellId) }
    }

    private fun showLatencyAlert() {
        scope.launch(Dispatchers.Main) {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 300)
            val notification = NotificationCompat.Builder(this@MiniICService, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⚠ Anomalía de Red")
                .setContentText(getString(R.string.latency_warning))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java)
                .notify(LATENCY_NOTIFICATION_ID, notification)
        }
    }
    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> isScreenOn = true
                    Intent.ACTION_SCREEN_OFF -> isScreenOn = false
                }
            }
        }
        registerReceiver(screenReceiver, filter)
    }

    /** El wakelock es deliberadamente continuo: la recolección 24/7 es una función elegida. */
    @SuppressLint("WakelockTimeout")
    private fun ensureCollectionWakeLock() {
        collectionPower.acquire()
    }

    private fun releaseCollectionWakeLock() {
        collectionPower.release()
    }

    /** Solo protege frente al apagado inminente; cargando nunca se pausa la recolección. */
    private fun isBatteryCritical(): Boolean = collectionPower.isBatteryCritical()

    private fun requestHighAccuracyFix(force: Boolean = false) {
        locationController.requestPreciseFix(force)
    }

    private fun persistPreciseLocation(location: Location) {
        val cell = _cellFlow.value.firstOrNull { it.isRegistered } ?: return
        scope.launch(Dispatchers.IO) {
            val updated = dbHelper.updateNullCoordinates(
                cell.cellId, cell.mnc, cell.tac, cell.mcc, cell.radioTech,
                location.latitude, location.longitude
            )
            appendLog(
                "[GPS]",
                if (updated > 0) "Incidente localizado: $updated registro(s) con coordenadas precisas"
                else "Fix preciso obtenido (sin registros pendientes de coordenadas)"
            )
        }
    }

    private fun registerTelephonyCallback() {
        telephonyController.start()
    }

    private fun registerDisplayInfoCallback() {
        telephonyController.start()
    }

    private fun requestFreshCellInfo() {
        telephonyController.requestFreshCellInfo()
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
        locationController.destroy()
        telephonyController.destroy()
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        releaseCollectionWakeLock()
    }

    private fun processCellInfo(
        infoList: List<CellInfo>?,
        isFreshDelivery: Boolean = true,
        fromRegisteredCallback: Boolean = false
    ) {
        try {
            val list = mutableListOf<CellData>()   // v2.1: ya no se reasigna (el TA no se comparte entre celdas)
            var activeObservationToken: Long? = null
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
                    // El token pertenece a la primera celda registrada que sobrevivió al parser,
                    // la misma que se convertirá en activeRaw. Una entrada NR inválida ya no puede
                    // dominar el máximo ni mezclar dominios de reloj con el ancla LTE.
                    if (parsed.isRegistered && activeObservationToken == null) {
                        activeObservationToken = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            info.timestampMillis
                        } else {
                            @Suppress("DEPRECATION")
                            TimeUnit.NANOSECONDS.toMillis(info.timeStamp)
                        }
                    }
                }
            }

            if (list.isEmpty()) {
                _cellFlow.value = emptyList()
                updateNotificationText(getString(R.string.no_signal_airplane))
                return
            }

            // Solo una entrega con trabajo real puede invalidar otra que esté esperando el mutex.
            // Un callback vacío ya no crea una secuencia fantasma que haga perder el ciclo válido.
            val processingSequence = enqueuedCellProcessing.incrementAndGet()

            // v2.1 — EL TIMING ADVANCE NO SE COMPARTE ENTRE CELDAS.
            //
            // Antes se cogía el PRIMER TA disponible de toda la lista (vecinas incluidas) y se le
            // estampaba a la celda registrada si ella no tenía. Un TA es la distancia de ida y
            // vuelta a UNA antena concreta: atribuírselo a otra es inventar una geometría. Y esa
            // geometría alimenta H6, que penaliza -40 (lo máximo del sistema) por "Suplantación
            // TA" — es decir, la señal más cara del motor podía estar midiendo otra antena.
            //
            // Se mantiene el único caso legítimo que esto cubría: que la MISMA celda aparezca
            // duplicada en la lista (pasa con NSA y con algunos módems) y solo una de las entradas
            // traiga el TA. Ahí sí es el mismo emisor, así que se copia junto con su unidad.
            val activeIndex = list.indexOfFirst { it.isRegistered }
            if (activeIndex >= 0) {
                val activeCell = list[activeIndex]
                if (activeCell.timingAdvance == null && activeCell.cellId != "N/A") {
                    val sameCellWithTa = list.firstOrNull { c ->
                        !c.isRegistered &&
                            c.timingAdvance != null && c.timingAdvance >= 0 &&
                            c.cellId == activeCell.cellId &&
                            c.mnc == activeCell.mnc &&
                            c.tac == activeCell.tac &&
                            c.mcc == activeCell.mcc
                    }
                    if (sameCellWithTa != null) {
                        list[activeIndex] = activeCell.copy(
                            timingAdvance = sameCellWithTa.timingAdvance,
                            timingAdvanceUnit = sameCellWithTa.timingAdvanceUnit
                        )
                    }
                }

                // v2.1 — Un módem que no implementa el TA suele devolver 0 en lugar de declarar
                // "no disponible", y ese 0 es indistinguible de "estás pegado a la antena" en una
                // sola lectura. Si varias celdas distintas solo dan 0, es un campo sin rellenar:
                // se marca STUB_ZERO y deja de producir geometría por el camino de siempre. Sin
                // esto, cualquier celda fuerte sin verificar arrastraría un -15 perpetuo por
                // "Proximidad anómala (TA)" originado en el firmware, no en la red.
                val current = list[activeIndex]
                val taKey = current.identityKey
                // v2.5 — La evidencia sobrevive a los reinicios del servicio; sin esto, la misma
                // lectura de TA=0 se etiquetaba LTE_INDEX o STUB_ZERO según cuánto llevara vivo el
                // proceso, y el historial dejaba de ser autoconsistente (medido: 319 vs 394 filas).
                if (taSanity.observe(taKey, current.timingAdvance)) persistTaSanityEvidence()
                val effectiveUnit = taSanity.effectiveUnit(current.timingAdvanceUnit, current.timingAdvance)
                if (effectiveUnit != current.timingAdvanceUnit) {
                    list[activeIndex] = current.copy(timingAdvanceUnit = effectiveUnit)
                }
            }

            val neighbors = list.filter { !it.isRegistered }
            val activeRaw = list.firstOrNull { it.isRegistered }
            // Milisegundos desde el arranque de la celda registrada aceptada por el parser.
            val observationToken = activeObservationToken ?: 0L

            // Reset del estado de latencia ANTES del análisis si la celda ha cambiado
            // (prevCid aún tiene el id anterior aquí; checkAlerts lo actualiza después).
            // Esto evita que la heurística 12 lea el veredicto "ANOMALA" de la celda vieja
            // en el primer ciclo de la nueva, antes de que tenga su propio baseline.
            if (activeRaw != null && activeRaw.cellId != "N/A" && activeRaw.cellId != prevCid) {
                latencyMonitor.reset(idleLatencyState())
            }

            scope.launch(Dispatchers.IO) {
                cellProcessingMutex.withLock {
                if (processingSequence < enqueuedCellProcessing.get()) return@withLock
                val currentLocation = getCurrentLocation()
                // Cellular polling only reads motion. New evidence enters from real Location
                // callbacks, so a repeated lastKnownLocation cannot manufacture static time.
                val motionEvidence = stableSiteMotion.current(System.currentTimeMillis()).also {
                    latestMotionEvidence = it
                }
                // Mobility is contextual metadata only. Its result is deliberately not passed to
                // ThreatAnalyzer, LocalCellTrust, Stable-Site, alerts or forensics.
                val mobility = if (activeRaw != null && activeRaw.cellId != "N/A") {
                    mobilityFamiliarity.observe(activeRaw.identityKey, motionEvidence, System.currentTimeMillis())
                } else com.alexisgordr.icdetector.core.MobilityObservation(
                    com.alexisgordr.icdetector.core.MobilityFamiliarity.UNKNOWN_ON_ROUTE
                )
                val mobilityLog = "${mobility.tripId}|${mobility.familiarity}|${mobility.goodEdges}|${mobility.event}"
                if (mobilityLog != lastMobilityLog) {
                    lastMobilityLog = mobilityLog
                    appendLog(
                        "[MOBILITY]",
                        "familiarity=${mobility.familiarity} trip=${mobility.tripId ?: "none"} " +
                            "priorTrips=${mobility.priorTrips} goodEdges=${mobility.goodEdges}" +
                            (mobility.event?.let { " event=$it" } ?: "")
                    )
                }
                val siteKeys = currentLocation?.takeIf { it.accuracy <= 50f }?.let {
                    com.alexisgordr.icdetector.core.StableSiteKey.candidates(it.latitude, it.longitude)
                }.orEmpty()
                if (activeRaw != null && activeRaw.cellId != "N/A") {
                    dbHelper.observeStableSiteEpisode(activeRaw.identityKey, stableSitePreviousIdentity)
                }
                val stableSiteCandidate = if (activeRaw != null && activeRaw.cellId != "N/A" && siteKeys.isNotEmpty()) {
                    com.alexisgordr.icdetector.core.StableSiteCandidateSelector.select(
                        siteKeys.map { key -> dbHelper.evaluateStableSiteCandidate(key, activeRaw, stableSitePreviousIdentity, motionEvidence) }
                    )!!
                } else com.alexisgordr.icdetector.core.StableSiteDecision(reason = "NO_SERVING_CELL")
                val stableSiteDecision = if (activeRaw != null && activeRaw.cellId != "N/A") {
                    dbHelper.applyStableSiteDecision(stableSiteCandidate, activeRaw, siteKeys)
                } else stableSiteCandidate
                val siteKey = stableSiteDecision.siteKey

                // H16 se prepara antes de analizar la celda: aquí todavía conservamos la última
                // servidora. El resultado se retiene 20 s para que los tres ciclos temporales
                // puedan observar el mismo handover, sin convertirlo en un fallo permanente.
                val transitionCoherence = if (activeRaw != null && activeRaw.cellId != "N/A") {
                    transitionTracker.evaluate(activeRaw, currentLocation, neighbors)
                } else TransitionCoherenceResult()

                val canQuery = activeRaw != null && activeRaw.cellId != "N/A" && currentLocation != null

                val preloadedHistory: List<HistoryRecord>
                val signalBaseline: SignalBaseline?

                // rfStability (H15) solo depende de la IDENTIDAD de la celda (su historial de
                // PCI/ARFCN), NO de la ubicación. Se calcula SIEMPRE que haya celda válida,
                // haya GPS o no. Antes estaba dentro del gate canQuery (que exige currentLocation
                // != null), así que parpadeaba 100<->70 al ritmo de la disponibilidad del GPS
                // estando parado. Caché propia por firma de celda (sin depender del movimiento).
                var rfStability: CellRfStability? = null
                if (activeRaw != null && activeRaw.cellId != "N/A") {
                    // La radio forma parte de la identidad: un CID numéricamente igual en LTE y
                    // NR no puede compartir coordenada, reputación, fingerprint ni estabilidad.
                    val rfSig = activeRaw.identityKey
                    val nowRf = System.currentTimeMillis()
                    rfStability = if (rfSig == cachedRfSignature && (nowRf - cachedRfTimestamp) < CELL_CACHE_TTL) {
                        cachedRfStability
                    } else {
                        val st = dbHelper.getCellRfStability(
                            activeRaw.cellId, activeRaw.mnc, activeRaw.tac, activeRaw.mcc, activeRaw.radioTech
                        )
                        // Reputación: misma identidad de celda -> se recalcula con la misma caché.
                        // Solo lectura del historial (columna 'score'); amortigua ruido débil en
                        // celdas probadas (ver CellReputation). No toca esquema.
                        cachedReputation = dbHelper.getCellReputation(
                            activeRaw.cellId, activeRaw.mnc, activeRaw.tac, activeRaw.mcc, activeRaw.radioTech
                        )
                        // v2.1: se pasa la ubicación para acotar la huella RF a la zona actual.
                        // Con el muestreo periódico, el sitio donde más tiempo pasas aporta la
                        // mayoría de las muestras; sin acotar, la media se desplazaría hacia ese
                        // sitio y la misma celda vista desde otro punto parecería "incoherente".
                        // Con ubicación desconocida se mantiene el comportamiento clásico.
                        cachedFingerprint = dbHelper.getCellRfFingerprint(
                            activeRaw.cellId, activeRaw.mnc, activeRaw.tac, activeRaw.mcc,
                            radio = activeRaw.radioTech,
                            nearLocation = currentLocation
                        )
                        cachedLocalTrustEvidence = dbHelper.getLocalCellTrustEvidence(activeRaw)
                        // v2.1 — Posición de la antena según las bases públicas, para poder
                        // MOSTRAR la distancia de forma continua (no solo en el ciclo posterior a
                        // la verificación). Misma caché por identidad de celda: una consulta más
                        // cada 60 s como mucho.
                        val apiLocation = dbHelper.getCellApiLocation(
                            activeRaw.cellId, activeRaw.mnc, activeRaw.tac, activeRaw.mcc, activeRaw.radioTech
                        )
                        if (apiLocation != null) apiLocationCache[rfSig] = apiLocation
                        else apiLocationCache.remove(rfSig)
                        cachedRfSignature = rfSig
                        cachedRfStability = st
                        cachedRfTimestamp = nowRf
                        st
                    }
                }
                val reputation: CellReputation? = cachedReputation
                val rfFingerprint: CellRfFingerprint? = cachedFingerprint
                val localTrustEvidence = cachedLocalTrustEvidence

                if (canQuery) {
                    val signature = activeRaw.identityKey
                    val now = System.currentTimeMillis()
                    // ¿Cuánto te has movido desde el último prefetch? (afecta al baseline, que
                    // filtra muestras por cercanía). Si te mueves mucho, hay que reconsultar.
                    val movedMeters = if (cachedCellSignature.isNotEmpty()) {
                        val r = FloatArray(1)
                        Location.distanceBetween(
                            cachedLocationLat, cachedLocationLon,
                            currentLocation.latitude, currentLocation.longitude, r
                        )
                        r[0]
                    } else Float.MAX_VALUE

                    val cacheValid = signature == cachedCellSignature &&
                        (now - cacheTimestamp) < CELL_CACHE_TTL &&
                        movedMeters < 150f

                    if (cacheValid) {
                        // Misma celda, mismo sitio y dentro del TTL: reutilizar (evita 2 queries).
                        preloadedHistory = cachedHistory
                        signalBaseline = cachedBaseline
                    } else {
                        preloadedHistory = dbHelper.getPreviousCellHistory(
                            activeRaw.cellId, activeRaw.mnc, activeRaw.tac, activeRaw.mcc,
                            activeRaw.radioTech, currentLocation
                        )
                        signalBaseline = dbHelper.getCellSignalBaseline(
                            activeRaw.cellId, activeRaw.mnc, activeRaw.tac, activeRaw.mcc,
                            activeRaw.radioTech, currentLocation
                        )
                        cachedCellSignature = signature
                        cachedLocationLat = currentLocation.latitude
                        cachedLocationLon = currentLocation.longitude
                        cachedHistory = preloadedHistory
                        cachedBaseline = signalBaseline
                        cacheTimestamp = now
                    }
                } else {
                    preloadedHistory = emptyList()
                    signalBaseline = null
                }

                // FIX: analizar SOLO la celda activa (registrada). Antes se llamaba a
                // analyzeThreats sobre cada celda, vecinas incluidas, y como `neighbors`
                // contiene a todas las no registradas, cada vecina se comparaba consigo
                // misma. Además, el análisis de amenazas está pensado para la celda
                // servidora, no para las vecinas (que solo son contexto). La activa nunca
                // está dentro de `neighbors`, así que ya no hay auto-comparación.
                // Distancia a la antena según las bases públicas. Se calcula DESPUÉS del análisis
                // y no se le pasa a ninguna heurística: su precisión depende de lo buena que sea
                // una coordenada colaborativa, que no es comparable con la geometría del TA.
                // Mezclarlas sería juntar dos magnitudes de fiabilidad muy distinta.
                val towerDistance: Int? = activeRaw?.identityKey?.let(apiLocationCache::get)?.let { (tLat, tLon) ->
                    currentLocation?.let { loc ->
                        val r = FloatArray(1)
                        Location.distanceBetween(loc.latitude, loc.longitude, tLat, tLon, r)
                        r[0].toInt()
                    }
                }

                // The modem can briefly publish an empty neighbour list even in ordinary
                // coverage. Do not let one such snapshot become H1 evidence that an episode can
                // retain: require three fresh consecutive deliveries for the same serving cell.
                val wifiActive = isWifiConnected()
                val isolatedCellConfirmed = activeRaw?.let { active ->
                    isolatedCellConfidence.observe(
                        identity = active.identityKey,
                        candidate = !wifiActive && neighbors.isEmpty() && active.dbm >= -80,
                        observationToken = observationToken
                    )
                } ?: false

                val analyzedList = list.map { cell ->
                    if (cell.isRegistered) {
                        // FIX (coherencia de score): resolver el estado de verificación conocido
                        // (caché) ANTES de analizar, para que el bonus de VERIFIED / la penalización
                        // de NOT_FOUND y las heurísticas que dependen de `verified` (p.ej. la supresión
                        // de "Proximidad anómala TA") se apliquen en el MISMO ciclo. Antes se aplicaba
                        // después, así que el securityScore registrado no reflejaba la verificación y
                        // una celda VERIFIED podía perder hasta +15 y quedar marcada sospechosa sin
                        // serlo. Una celda nueva sigue siendo PENDING aquí (aún no verificada): correcto.
                        val knownCk = cell.identityKey
                        val knownVerified = verificationCache[knownCk] ?: VerificationStatus.PENDING
                        val analyzed = ThreatAnalyzer.analyzeThreats(
                            active = cell.copy(verified = knownVerified),
                            neighbors = neighbors,
                            isHardwareCipheringActive = isHardwareCipheringActive,
                            isHardwareCipheringAvailable = isHardwareCipheringAvailable,
                            cellChangeHistory = cellChangeHistory,
                            currentLocation = currentLocation,
                            preloadedHistory = preloadedHistory,
                            isWifiActive = wifiActive,
                            isNetworkLatencyAnomalous = networkLatencyState.value == "ANOMALA",
                            isNetworkLatencyAvailable = networkLatencyState.value != "N/A",
                            signalBaseline = signalBaseline,
                            previousBand = prevBand,
                            previousDbm = prevRegisteredDbm,
                            recentRegisteredDbm = recentRegisteredDbmTrend.toList(),
                            rfStability = rfStability,
                            reputation = reputation,
                            rfFingerprint = rfFingerprint,
                            transitionCoherence = transitionCoherence,
                            isolatedCellConfirmed = isolatedCellConfirmed
                        ).copy(distanceToTowerMeters = towerDistance)
                        com.alexisgordr.icdetector.core.LocalCellTrustEngine.apply(
                            analyzed,
                            localTrustEvidence,
                            stableSiteDecision
                        )
                    } else {
                        // Las vecinas no se evalúan como amenaza; se mantienen como contexto.
                        cell
                    }
                }

                withContext(Dispatchers.Main) {
                    val sorted = analyzedList.sortedWith(
                        compareByDescending<CellData> { it.isRegistered }
                            .thenByDescending { it.dbm }
                    )

                    val active = sorted.firstOrNull { it.isRegistered }
                    if (active != null) {
                        val stableSiteStartedWatching = stableSiteDecision.enforced && !stableSiteIntensiveActive
                        stableSiteIntensiveActive = stableSiteDecision.enforced
                        siteKeys.forEach { key ->
                            dbHelper.recordStableSiteContext(
                                // One aggregate contribution per identity and minute. The PK also
                                // makes retries/restarts idempotent without retaining raw snapshots.
                                eventKey = "${System.currentTimeMillis() / 60_000L}:$key",
                                siteKey = key,
                                serving = activeRaw ?: active,
                                neighbours = neighbors,
                                motion = motionEvidence
                            )
                        }
                        stableSitePreviousIdentity = active.identityKey
                        val siteEvidence = stableSiteDecision.evidence
                        val neighbourDiagnostic = com.alexisgordr.icdetector.core.StableSiteNeighbourEvidence.diagnostic(neighbors)
                        val siteLog = "${stableSiteDecision.featureState}|${motionEvidence.state}|${motionEvidence.reason}|${siteEvidence?.siteNeighbourDays}|${stableSiteDecision.wouldTrigger}|${stableSiteDecision.enforced}"
                        if (siteLog != lastStableSiteLog) {
                            lastStableSiteLog = siteLog
                            appendLog(
                                "[SITE]",
                                "Protección=${stableSiteDecision.featureState}, sitio=${if (siteKey != null) "detectado" else "no disponible"}, " +
                                    "movimiento=${motionEvidence.state} (${motionEvidence.reason}), vecinos=${siteEvidence?.siteNeighbourDays ?: 0} días, " +
                                    "capacidad=${siteEvidence?.neighbourCapability ?: neighbourDiagnostic.capability}, " +
                                    "Neighbours raw=${neighbourDiagnostic.raw} fullIdentity=${neighbourDiagnostic.fullIdentity} rfOnly=${neighbourDiagnostic.rfOnly} withoutUsefulRf=${neighbourDiagnostic.withoutUsefulRf}, " +
                                    "madurez=${siteEvidence?.maturityReason ?: "NO_SITE_DATA"}, " +
                                    "aplicación=${when { stableSiteDecision.enforced -> "SITE_UNVERIFIED"; stableSiteDecision.wouldTrigger -> "SOMBRA: aplicaría SITE_UNVERIFIED"; else -> "NO ACTIVA" }}"
                            )
                        }
                        // Observe the trust result immediately after LocalCellTrustEngine. This is
                        // a one-way evidence signal and cannot alter the detector input below.
                        // Always feed the RF contradiction tracker. A real contradiction has
                        // priority over contextual novelty and must not go blind during a hold.
                        val liveTrustContradiction = trustContradictionTransitions.observe(active)
                        val trustContradictionTransition = if (
                            stableSiteDecision.enforced && liveTrustContradiction == TrustContradictionSignal.NONE
                        ) TrustContradictionSignal.SITE_NOVELTY else liveTrustContradiction
                        // 1. Obtener estado conocido (Caché o DB) para no mostrar PENDING si ya existe
                        val cacheKey = active.identityKey
                        val knownStatus = verificationCache[cacheKey] ?: VerificationStatus.PENDING

                        // Aplicar confirmación temporal antes de alertas
                        val episode = threatEpisodeTracker.apply(
                            active,
                            observationToken.takeIf { it > 0L },
                            isFreshDelivery = isFreshDelivery
                        )
                        if (episode.startedWatching) {
                            appendLog("[SEC]", getString(R.string.episode_watch_started))
                            locationController.requestPreciseFix(force = true)
                            requestFreshCellInfo()
                        }
                        if (episode.promoted) {
                            appendLog("[SEC]", getString(
                                R.string.episode_promoted_format,
                                episode.families.joinToString()
                            ))
                        }

                        val temporalActive = temporalConfidence.apply(
                            episode.cell,
                            observationToken.takeIf { it > 0L },
                            isFreshDelivery = isFreshDelivery
                        )
                        updateIntensiveMonitoring(
                            watching = episode.watching || stableSiteDecision.enforced,
                            startedWatching = episode.startedWatching || stableSiteStartedWatching,
                            confirmed = temporalActive.isSuspicious
                        )
                        val diagnosticInputs = com.alexisgordr.icdetector.core.DiagnosticEngine.Inputs(
                            neighborCount = neighbors.size,
                            wifiActive = isWifiConnected(),
                            locationAvailable = currentLocation != null,
                            historyWithLocation = preloadedHistory.count { it.lat != null && it.lon != null },
                            latencyAvailable = networkLatencyState.value != "N/A",
                            cipheringAvailable = isHardwareCipheringAvailable,
                            previousBandAvailable = prevBand != null && prevRegisteredDbm != null,
                            signalBaseline = signalBaseline,
                            rfFingerprint = rfFingerprint,
                            rfStability = rfStability,
                            reputation = reputation,
                            transitionCoherence = transitionCoherence
                        )
                        val confirmedActive = temporalActive.copy(
                            heuristicDiagnostics = com.alexisgordr.icdetector.core.DiagnosticEngine.explain(
                                temporalActive, diagnosticInputs
                            ),
                            baselineMaturity = com.alexisgordr.icdetector.core.DiagnosticEngine.maturity(diagnosticInputs)
                        )
                        // Caja negra: se actualiza fuera del hilo de UI. Registra desde 1/3 y
                        // cierra el episodio al recuperarse o al cambiar de identidad.
                        val previousIncidentIdentity = lastIncidentIdentity
                        lastIncidentIdentity = if (confirmedActive.temporalProgress.active) cacheKey else null
                        scope.launch(forensicDispatcher) {
                            if (previousIncidentIdentity != null && previousIncidentIdentity != cacheKey) {
                                dbHelper.closeOpenIncidents(previousIncidentIdentity, interrupted = true)
                            }
                            if (confirmedActive.temporalProgress.active) {
                                dbHelper.recordIncidentPhase(confirmedActive)
                            } else {
                                dbHelper.closeOpenIncidents(cacheKey, interrupted = false)
                            }
                        }

                        // Captura forense pasiva. Recibe una copia del ciclo ya resuelto, jamás
                        // devuelve datos al detector y por tanto no puede alterar su veredicto.
                        //
                        // v2.8.0 — Va por [forensicDispatcher], igual que los incidentes. Con
                        // Dispatchers.IO (64 hilos) dos ciclos seguidos podían entrar a la vez: el
                        // mutex interno del grabador los ordena, pero no garantiza EN QUÉ orden, y
                        // un caso cuyo prebúfer se vuelca detrás de la muestra que lo disparó deja
                        // de ser una línea de tiempo. Un solo hilo serializa la escritura de verdad.
                        scope.launch(forensicDispatcher) {
                            forensicRecorder.observe(
                                active = confirmedActive.copy(verified = knownStatus),
                                neighbors = neighbors,
                                location = currentLocation,
                                latencyState = networkLatencyState.value,
                                logs = liveLogs.value,
                                trustContradictionTransition = trustContradictionTransition
                            )
                        }

                        // 2. Lanzar alertas y registro con el estado actual
                        checkAlerts(confirmedActive.copy(verified = knownStatus), confirmed = true)

                        // 3. Iniciar proceso de verificación (solo si es necesario)
                        verificationController.verify(active)
                        
                        val finalStatus = verificationCache[cacheKey] ?: knownStatus
                        
                        _cellFlow.value = sorted.map { 
                            if (it.isRegistered) confirmedActive.copy(verified = finalStatus) else it 
                        }
                        
                        updateNotification(confirmedActive.copy(verified = finalStatus))

                        // Actualizar estado de banda para la heurística 14 (tras el análisis,
                        // de modo que el PRÓXIMO ciclo compare contra estos valores).
                        val isLteActive = active.radioTech == RadioTech.LTE
                        prevBand = if (isLteActive) {
                            active.band ?: active.arfcn?.let { com.alexisgordr.icdetector.core.BandPlan.earfcnToBandLte(it) }
                        } else null
                        prevRegisteredDbm = active.dbm
                        if (active.dbm != Int.MAX_VALUE && active.dbm != -999) {
                            recentRegisteredDbmTrend.add(active.dbm)
                            while (recentRegisteredDbmTrend.size > 6) recentRegisteredDbmTrend.removeAt(0)
                        }
                    } else {
                        stableSiteIntensiveActive = false
                        _cellFlow.value = emptyList()
                        updateNotificationText(getString(R.string.searching_network))
                    }
                }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Recupera la evidencia del diagnóstico de Timing Advance guardada por arranques anteriores.
     *
     * Se restaura EVIDENCIA, no veredicto: `isStub` se sigue derivando de ella, de modo que si
     * algún día cambia `MIN_DISTINCT_CELLS` el pasado se reevalúa en lugar de heredar una
     * conclusión congelada. Si las preferencias están corruptas o vacías, se empieza de cero:
     * la evidencia se rederiva sola en unos minutos de uso en movimiento.
     */
    private fun restoreTaSanityEvidence() {
        try {
            val p = getSharedPreferences("miniic_prefs", MODE_PRIVATE)
            val seenReal = p.getBoolean(KEY_TA_SEEN_REAL, false)
            val cells = p.getStringSet(KEY_TA_ZERO_CELLS, emptySet()).orEmpty()
            if (!seenReal && cells.isEmpty()) return
            taSanity.restore(seenReal, cells)
            if (taSanity.isStub) {
                appendLog(
                    "[TA]",
                    "Diagnóstico de TA recuperado del arranque anterior: el módem no rellena el campo " +
                        "(${taSanity.zeroOnlyCellCount} celdas distintas con 0). No se deduce distancia."
                )
            }
        } catch (_: Exception) {}
    }

    /** Guarda la evidencia de TA. Se llama solo cuando [TimingAdvanceSanity.observe] dice que cambió. */
    private fun persistTaSanityEvidence() {
        try {
            getSharedPreferences("miniic_prefs", MODE_PRIVATE).edit()
                .putBoolean(KEY_TA_SEEN_REAL, taSanity.hasSeenRealValue)
                .putStringSet(KEY_TA_ZERO_CELLS, taSanity.zeroOnlyCellKeys)
                .apply()
        } catch (_: Exception) {}
    }

    private fun getCurrentLocation(): Location? {
        return locationController.currentLocation()
    }

    private fun observeMotionFix(location: Location) {
        latestMotionEvidence = stableSiteMotion.observe(
            com.alexisgordr.icdetector.core.MotionFix(
                location.latitude,
                location.longitude,
                location.time,
                location.accuracy,
                if (location.hasSpeed()) location.speed else null
            )
        )
    }

    /**
     * Keeps short-lived evidence responsive without allowing intermittent H1 noise to pin the
     * screen-off loop at 3 seconds forever. The hard cap is monotonic and is not extended until
     * the episode has fully expired and a later episode starts from zero.
     */
    private fun updateIntensiveMonitoring(
        watching: Boolean,
        startedWatching: Boolean,
        confirmed: Boolean
    ) {
        val now = SystemClock.elapsedRealtime()
        if (!watching && !confirmed) {
            intensiveMonitoringStartedAtMs = 0L
            intensiveMonitoringUntilMs = 0L
            return
        }
        if (startedWatching || intensiveMonitoringStartedAtMs == 0L) {
            intensiveMonitoringStartedAtMs = now
        }
        if (confirmed) {
            // Maximum resolution is useful while the forensic case can still accept samples.
            // ForensicRecorder locks the same identity after this timeout until recovery, so
            // keeping a 3-second loop beyond the case lifetime would only waste battery.
            intensiveMonitoringUntilMs = intensiveMonitoringStartedAtMs + ForensicRecorder.MAX_CASE_MS
            return
        }
        val hardStop = intensiveMonitoringStartedAtMs + MAX_INTENSIVE_MONITORING_MS
        intensiveMonitoringUntilMs = minOf(now + INTENSIVE_MONITORING_TAIL_MS, hardStop)
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    // Detecta si hay una VPN activa en la red por defecto del sistema.
    // Doble comprobación: transporte VPN o ausencia de la capability NOT_VPN (robusto
    // ante distintos modos de túnel). La latencia hacia endpoints externos viajaría por
    // el túnel y no reflejaría la red celular local, así que no debe medirse.
    private fun isVpnActive(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    // La sonda de latencia solo es representativa si está activada y la salida es la red
    // celular directa: sin WiFi, sin VPN y sin Tor (proxy SOCKS5). En cualquier otro caso
    // el indicador debe ser "N/A", no "OK".
    private fun isLatencyProbeActive(): Boolean =
        isLatencyDetectionEnabled && !isWifiConnected() && !isVpnActive() && !isProxyEnabled

    // Estado "idle" del indicador tras un handover: "OK" solo si la sonda puede medir;
    // si no, "N/A" para no afirmar un estado de red sin verificación.
    private fun idleLatencyState(): String = if (isLatencyProbeActive()) "OK" else "N/A"

    private fun onGpsAvailable() {
        val now = System.currentTimeMillis()
        if (now - lastGpsTriggerTime < 60000L) return

        // Solo comprobamos que HAY alguna celda activa; la celda concreta se recaptura DESPUÉS del
        // delay (puede haber handover en esos 3 s).
        if (_cellFlow.value.none { it.isRegistered }) return

        lastGpsTriggerTime = now
        scope.launch {
            delay(3000L.milliseconds)

            // Verificar también en DB antes de lanzar
            val loc = getCurrentLocation()
            if (loc == null) {
                appendLog("[GPS]", "GPS inestable tras espera, reintentando más tarde")
                lastGpsTriggerTime = 0L
                return@launch
            }

            // FIX race condition: recapturar la celda activa AHORA, no la de hace 3 s. Si hubo
            // handover durante la espera, las coordenadas y la (re)verificación deben ir a nombre de
            // la celda REALMENTE activa, no de la anterior — si no, inyectaríamos un GPS fresco en
            // los registros de la antena equivocada (ruido para H11/H13).
            val currentCell = _cellFlow.value.firstOrNull { it.isRegistered } ?: return@launch
            val cacheKey = currentCell.identityKey
            val cachedStatus = verificationCache[cacheKey]

            // Rellenar coordenadas SOLO con un fix casi de tiempo real (<15 s, la cadencia del
            // stream). Antes de un cambio de celda / rebote a la misma celda queremos la posición
            // real del momento: ante una clonación de Cell ID, la H11/H13 comparan contra estas
            // coordenadas, así que una posición vieja podría enmascarar la incoherencia. Si no hay
            // fix tan fresco, se omite y lo cubre el fix forzado (fresco por definición).
            val coordAgeMs = System.currentTimeMillis() - loc.time
            if (coordAgeMs < 15000L) {
                locationController.resolvePendingCoordinates()
                scope.launch(Dispatchers.IO) {
                    val updated = dbHelper.updateNullCoordinates(
                        currentCell.cellId,
                        currentCell.mnc,
                        currentCell.tac,
                        currentCell.mcc,
                        currentCell.radioTech,
                        loc.latitude,
                        loc.longitude
                    )
                    if (updated > 0) {
                        appendLog("[GPS]", "Coordenadas frescas rellenadas en $updated registro(s)")
                    }
                }
            } else {
                appendLog("[GPS]", "Fix disponible pero no lo bastante fresco (${coordAgeMs / 1000}s) — se omite el relleno; espera al fix preciso")
            }

            // El relleno de coordenadas (arriba) se ejecuta SIEMPRE que haya GPS, esté la celda
            // verificada o no: una celda puede estar verificada y aun así no tener coordenadas si
            // el GPS no estaba listo al registrarla. La RE-VERIFICACIÓN, en cambio, solo si hace
            // falta (pendiente/error/desconocida); una celda ya verificada no se vuelve a consultar.
            val needsReverification = cachedStatus == null ||
                                      cachedStatus == VerificationStatus.PENDING ||
                                      cachedStatus == VerificationStatus.ERROR
            if (!needsReverification) return@launch

            val dbStatus = withContext(Dispatchers.IO) {
                dbHelper.getKnownStatus(
                    currentCell.mnc, currentCell.tac, currentCell.cellId,
                    currentCell.mcc, loc.latitude, loc.longitude, currentCell.radioTech
                )
            }

            if (dbStatus == VerificationStatus.VERIFIED) {
                appendLog("[GPS]", "Celda ya verificada en DB, sin necesidad de API")
                verificationCache[cacheKey] = VerificationStatus.VERIFIED
                // Forzar actualización de la UI con el estado recuperado
                updateFlowWithStatus(currentCell, VerificationStatus.VERIFIED)
                return@launch
            }

            verificationCache.remove(cacheKey)
            appendLog("[GPS]", "GPS estabilizado — relanzando verificación")
            verificationController.verify(currentCell)
        }
    }

    /**
     * Marca en la pantalla el estado de verificación de **una** celda.
     *
     * Recibe la celda entera, no su Cell ID. Comparando solo el CID, otra celda de la lista con el
     * mismo identificador pero distinto operador, área o tecnología —vecinas incluidas— cambiaba de
     * color con ella. En una app cuyo trabajo es distinguir antenas, la identidad parcial no vale
     * en ningún sitio, y menos en el que la persona mira.
     */
    private fun updateFlowWithStatus(cell: CellData, status: VerificationStatus) {
        val clave = cell.identityKey
        scope.launch(Dispatchers.Main) {
            _cellFlow.value = _cellFlow.value.map {
                if (it.identityKey == clave) it.copy(verified = status) else it
            }
        }
    }

    private fun checkAlerts(cell: CellData, confirmed: Boolean = false) {
        val cid = cell.cellId
        val dbm = cell.dbm
        val net = cell.networkType

        // Cierra en el terminal la auditoría de esta misma celda si la verificación ya ha
        // contestado desde que se escribió. No hace nada si nada ha cambiado.
        auditController.logVerificationOutcome(cell)

        if (cid != prevCid) {
            telemetryHistory.onHandover()
            // Reset del estado de latencia en el handover: el veredicto de la celda anterior
            // NO es válido para la nueva. Limpiamos "ANOMALA" (icono y heurística 12) y la
            // racha; la celda nueva volverá a "OK"/"ANOMALA" según sus propias mediciones una
            // vez aprenda su baseline. Evita que la heurística 12 se contamine con datos viejos.
            latencyMonitor.reset(idleLatencyState())
            securityAlerts.resetAlarmEpisode()
            appendLog("[RADIO]", "Handover celular completado -> Nueva celda CID: $cid ($net)")
            // Esta celda queda pendiente de coordenadas frescas hasta que un fix las rellene.
            locationController.markCoordinatesPending()
            // El stream GPS permanece activo 24/7. Este listener puntual da prioridad al handover
            // si todavía no existe un fix contemporáneo; conserva el debounce y single-flight.
            requestHighAccuracyFix()
            auditController.generate(cell)

            val currentTime = System.currentTimeMillis()
            cellChangeHistory.add(Pair(cid, currentTime))
            cellChangeHistory.removeAll { currentTime - it.second > 10000L }

            // El informe de heurísticas es la observación cruda del ciclo. No debe producir un
            // tono aquí: TemporalConfidence necesita tres ciclos antes de convertirla en alarma.
            // Si el ping-pong contribuye a una amenaza confirmada, lo registra la rama general
            // cell.isSuspicious de abajo, junto al resto de las heurísticas confirmadas.
            if (!cell.heuristicReport.pingPongPassed) {
                // Evidencia para la campaña, sin alerta: el informe sigue siendo crudo y todavía
                // no ha superado los tres ciclos de TemporalConfidence.
                appendLog("[RADIO]", "Ping-Pong observado (sin confirmar; sin tono).")
            } else if (cellChangeHistory.size >= 3) {
                val speedKmh = (getCurrentLocation()?.speed ?: 0f) * 3.6f
                appendLog("[RADIO]", "Ping-Pong detectado a ${String.format(Locale.getDefault(), "%.1f", speedKmh)} km/h. Ignorando alerta.")
            }

            observationPersistence.recordHandover(cell)

            prevCid = cid
        } else if (confirmed) {
            // Misma celda que en el ciclo anterior: muestreo periódico (ver más abajo). Solo en la
            // ruta del bucle principal (confirmed = true), nunca en la de re-análisis tras la API,
            // para no duplicar filas por el mismo instante.
            observationPersistence.recordPeriodicIfDue(cell, isScreenOn)
        }

        telemetryHistory.record(cell)

        securityAlerts.evaluate(cell, confirmed)
    }

    private fun updateNotification(cell: CellData) {
        val vStatus = when (cell.verified) {
            VerificationStatus.VERIFIED -> getString(R.string.verified_label)
            // El aspa roja sobraba: que una base colaborativa no conozca una antena no es un fallo
            // de la antena ni un indicio de nada, y desde v2.1 tampoco resta puntos. La
            // notificación lo dice como lo que es, un dato de contexto.
            VerificationStatus.NOT_FOUND -> getString(R.string.not_found_label)
            VerificationStatus.REJECTED -> getString(R.string.rejected_label)
            VerificationStatus.ERROR -> getString(R.string.api_error_label)
            VerificationStatus.PENDING -> getString(R.string.pending_label)
        }
        val content = "$vStatus | ${cell.networkType} | CID: ${cell.cellId} | ${cell.dbm} dBm"
        updateNotificationText(content)
    }

    /**
     * Resultado de una escritura en el historial.
     *
     * v2.3.3 — `SQLiteDatabase.insert()` devuelve -1 y se traga la excepción cuando el disco está
     * lleno o la base bloqueada. Sin esto, la app seguía analizando y mostrando "Sondeo activo"
     * con la base de datos sin recibir una sola fila: la campaña moría en silencio.
     */
    @Synchronized
    private fun noteWriteResult(rowId: Long) {
        when (collectionHealthController.noteWrite(rowId)) {
            CollectionHealthController.Change.FAILED -> {
                appendLog("[SYS]", "⚠ ESCRITURA FALLIDA: la base de datos rechaza las filas (¿disco lleno?). La recolección NO está guardando datos.")
                updateNotificationText(CollectionHealthController.WRITE_FAILURE_TEXT, force = true)
            }
            CollectionHealthController.Change.RECOVERED -> {
                appendLog("[SYS]", "Escritura en base de datos restablecida.")
            }
            CollectionHealthController.Change.NONE -> Unit
        }
    }

    private var lastNotificationTime = 0L
    @Synchronized
    private fun updateNotificationText(text: String, force: Boolean = false) {
        // Throttle: no repintar la notificación más de una vez cada 2 s. El callback de telefonía
        // puede dispararse muchas veces por segundo en zonas de transición; repintar cada vez satura
        // el System Server (IPC + batería) y Android acaba silenciando con "rate limit exceeded".
        // Los datos (BD, logs y el TONO de alarma) van en tiempo real por su cuenta: esto solo
        // limita el DIBUJO visual de la notificación, no la detección ni el registro.
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationTime < 2000L) return
        lastNotificationTime = now
        // Un fallo de escritura tapa cualquier otro texto: es la única condición en la que la app
        // parece funcionar y no está recogiendo nada.
        val visible = collectionHealthController.visibleNotification(text, now)
        notificationController.notifyForeground(visible)
    }

    private fun getNetworkOperatorMcc(): String {
        return telephonyController.operatorMcc()
    }

    private fun getNetworkOperatorMnc(): String {
        return telephonyController.operatorMnc()
    }

    private fun getLteSpecificType(): String {
        return telephonyController.displayNetworkType()
    }

    /**
     * Escribe en el terminal el resultado de una celda **después** de que las bases públicas
     * contesten, y solo si esa celda es la que tiene la auditoría escrita y su estado ha cambiado
     * desde entonces.
     *
     * Existe porque el terminal miente por omisión: la auditoría se genera en el handover, con la
     * celda todavía PENDING, y ahí puede leerse "100% — entorno SEGURO". La verificación llega
     * después y cambia la etiqueta de la celda, pero nadie reescribía nada:
     * quedaban a la vista dos cifras distintas —la del terminal y la de la cabecera— sin ninguna
     * pista de que una era historia y la otra el presente.
     */
    companion object {
        const val CHANNEL_ID = ServiceNotificationController.CHANNEL_ID
        const val ALERT_CHANNEL_ID = ServiceNotificationController.ALERT_CHANNEL_ID
        const val NOTIFICATION_ID = ServiceNotificationController.NOTIFICATION_ID
        const val LATENCY_NOTIFICATION_ID = 3  // distinto al NOTIFICATION_ID principal
        const val ACTION_STOP = "com.alexisgordr.icdetector.STOP"
        // Cap de seguridad para los mapas en memoria indexados por celda: evita crecimiento
        // ilimitado en sesiones muy largas o viajes con miles de celdas distintas.
        const val MAX_TRACKED_CELLS = 500
        // v2.5 — Evidencia del diagnóstico de Timing Advance, superviviente a reinicios del
        // servicio. Ver TimingAdvanceSanity: se guarda la evidencia, nunca el veredicto.
        private const val KEY_TA_SEEN_REAL = "ta_seen_real_value"
        private const val KEY_TA_ZERO_CELLS = "ta_zero_only_cells"
        // Ciclos consecutivos de sospecha necesarios para confirmar una alarma.
        private const val CONFIRMATION_CYCLES = 3
        private const val INTENSIVE_MONITORING_TAIL_MS = 60_000L
        private const val MAX_INTENSIVE_MONITORING_MS = 5L * 60L * 1000L
        private const val UI_VISIBLE_SCAN_INTERVAL_MS = 1_000L
        private const val SCREEN_ON_SCAN_INTERVAL_MS = 3_000L
        private const val SCREEN_OFF_SCAN_INTERVAL_MS = 10_000L
    }
}
