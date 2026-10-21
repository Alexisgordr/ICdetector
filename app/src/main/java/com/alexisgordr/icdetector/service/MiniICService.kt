package com.alexisgordr.icdetector.service

import com.alexisgordr.icdetector.R

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Resources
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
import com.alexisgordr.icdetector.util.LocaleController
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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

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

    // 3.0 — Salud del ciclo de análisis (ver AnalysisHealth). Solo se toca en el hilo principal.
    private val analysisHealthTracker = com.alexisgordr.icdetector.core.AnalysisHealth()
    private val _analysisHealth = MutableStateFlow(com.alexisgordr.icdetector.core.AnalysisHealth.State())
    val analysisHealth: StateFlow<com.alexisgordr.icdetector.core.AnalysisHealth.State> = _analysisHealth

    private val _liveLogs = MutableStateFlow(listOf("[SYS] ICdetector Engine Iniciado...", "[SYS] Esperando hooks del módem..."))
    val liveLogs: StateFlow<List<String>> = _liveLogs

    // v2.10.4 — Estado de servicio (ServiceState). Solo recolección y visualización.
    private val serviceStatePersistence = com.alexisgordr.icdetector.core.ServiceStatePersistence()
    private val serviceStateDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val _serviceState = MutableStateFlow<ServiceStateSnapshot?>(null)
    val serviceState: StateFlow<ServiceStateSnapshot?> = _serviceState
    private val _serviceEventsRevision = MutableStateFlow(0L)
    val serviceEventsRevision: StateFlow<Long> = _serviceEventsRevision
    private var lastRadioContextLog: String? = null

    private val _dbmHistory = MutableStateFlow<List<Int>>(emptyList())
    val dbmHistory: StateFlow<List<Int>> = _dbmHistory

    private val _rsrqHistory = MutableStateFlow<List<Int>>(emptyList())
    val rsrqHistory: StateFlow<List<Int>> = _rsrqHistory

    private val _geoHistory = MutableStateFlow<List<Float>>(emptyList())
    val geoHistory: StateFlow<List<Float>> = _geoHistory

    private val _auditStatus = MutableStateFlow("")
    val auditStatus: StateFlow<String> = _auditStatus

    /**
     * v2.10.4 — Recibe una lectura de ServiceState (callback o sondeo). Los cambios se insertan
     * primero y solo después se publican a la UI y al terminal. No toca heurísticas.
     */
    private fun onServiceStateSnapshot(snapshot: ServiceStateSnapshot) {
        // La telemetría actual se mantiene viva aunque la firma de evento no cambie. Una lectura
        // atrasada nunca puede devolver la tarjeta a un estado anterior.
        if (snapshot.timestampMs >= (_serviceState.value?.timestampMs ?: Long.MIN_VALUE)) {
            _serviceState.value = snapshot
        }
        if (!::dbHelper.isInitialized) return
        writeGate.launch(scope, serviceStateDispatcher) {
            serviceStatePersistence.persist(
                snapshot = snapshot,
                insert = dbHelper::insertServiceStateEvent
            ) { change ->
                appendLog(
                    "[SERVICIO]",
                    com.alexisgordr.icdetector.core.ServiceStateTracker.terminalLine(change)
                )
                _serviceEventsRevision.value = _serviceEventsRevision.value + 1L
            }
        }
    }

    /**
     * v2.10.4 — Línea [RADIO] cuando cambia la servidora, su estado de conexión o sus portadoras
     * secundarias. Se deduplica por firma para no repetir la misma línea en cada ciclo.
     */
    private fun logRadioContext(serving: CellData?, registeredCount: Int) {
        if (serving == null) return
        val carriers = serving.secondaryCarriers.toCompactString()
        val signature = "${serving.identityKey}|${serving.connectionState}|$carriers|${serving.csg?.indicator}"
        if (signature == lastRadioContextLog) return
        lastRadioContextLog = signature
        val bandwidth = serving.bandwidthKhz?.let { " · ${it / 1000} MHz" } ?: ""
        val secondary = if (carriers.isNotEmpty()) "portadoras secundarias=$carriers" else "sin portadoras secundarias"
        appendLog(
            "[RADIO]",
            "Servidora ${serving.radioTech.name} ${serving.arfcn ?: "?"}/${serving.pci ?: "?"}$bandwidth · " +
                "estado de conexión=${serving.connectionState.name} · entradas registradas=$registeredCount · $secondary"
        )
        serving.csg?.takeIf { it.indicator }?.let { csg ->
            appendLog(
                "[RADIO]",
                "Celda de grupo cerrado (CSG, posible femtocelda): id=${csg.identity ?: "?"}" +
                    (csg.homeNodebName?.let { " nombre=$it" } ?: "")
            )
        }
    }

    private fun logUnusablePrimaryAbstention() {
        val signature = "ABSTAIN_UNUSABLE_PRIMARY"
        if (signature == lastRadioContextLog) return
        lastRadioContextLog = signature
        appendLog("[RADIO]", "Primaria declarada sin señal utilizable: ciclo en abstención.")
    }

    /**
     * 3.0 (G3) — Reloj de los viajes: civil para fechas, monotónico para intervalos y el número de
     * arranque, para no comparar nunca tiempos monotónicos de arranques distintos.
     */
    private fun mobilityClock() = com.alexisgordr.icdetector.core.MobilityClock(
        civilMs = System.currentTimeMillis(),
        elapsedMs = SystemClock.elapsedRealtime(),
        bootId = bootCount
    )

    private val bootCount: String? by lazy {
        Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }?.toString()
    }

    /** 3.0 (G3) — Ancla monotónica del viaje abierto: preferencia interna, no viaja en la copia. */
    private fun mobilityAnchorStore() = object : com.alexisgordr.icdetector.core.MobilityAnchorStore {
        private val prefs = getSharedPreferences(com.alexisgordr.icdetector.backup.BackupSettings.PREFS_FILE, MODE_PRIVATE)
        override fun load() = com.alexisgordr.icdetector.core.MobilityTripAnchor.parse(prefs.getString(com.alexisgordr.icdetector.core.MobilityTripAnchor.PREF_KEY, null))
        override fun save(anchor: com.alexisgordr.icdetector.core.MobilityTripAnchor?) {
            prefs.edit().apply { if (anchor == null) remove(com.alexisgordr.icdetector.core.MobilityTripAnchor.PREF_KEY) else putString(com.alexisgordr.icdetector.core.MobilityTripAnchor.PREF_KEY, anchor.serialize()) }.apply()
        }
    }

    private fun appendLog(type: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val newLog = "[$timestamp] $type $message"
        _liveLogs.update { current -> (current + newLog).takeLast(40) }
    }

    private lateinit var dbHelper: CellDbHelper
    private lateinit var localProfile: LocalProfileController
    private lateinit var mobilityFamiliarity: com.alexisgordr.icdetector.core.MobilityFamiliarityEngine
    private var lastMobilityLog: String? = null
    private lateinit var forensicRecorder: ForensicRecorder
    private val trustContradictionTransitions = TrustContradictionTransitionTracker()
    private val stableSiteMotion = com.alexisgordr.icdetector.core.MotionClassifier()
    private var stableSitePreviousIdentity: String? = null
    private var lastStableSiteLog: String? = null
    private var stableSiteIntensiveActive = false
    private val forensicDispatcher = Dispatchers.IO.limitedParallelism(1)
    /** v2.10.10 — Escrituras del historial en orden, en una sola cola. */
    private val historyWriteDispatcher = Dispatchers.IO.limitedParallelism(1)
    /**
     * 3.0 — Todas las escrituras (y los ciclos de análisis que escriben) pasan por aquí, para poder
     * terminarlas antes de una restauración: ver [quiesceForRestore].
     */
    private val writeGate = com.alexisgordr.icdetector.core.WriteGate()
    /** v2.10.10 — Cambia con cada "Borrar historial"; ver [clearHistory] y [HistoryEpoch]. */
    private val historyEpoch = com.alexisgordr.icdetector.core.HistoryEpoch()
    private lateinit var notificationController: ServiceNotificationController
    private lateinit var databaseMaintenance: DatabaseMaintenance
    private lateinit var legacyNetworkProtection: LegacyNetworkProtection
    private lateinit var transitionTracker: ServingTransitionTracker
    private lateinit var collectionPower: CollectionPowerController
    private lateinit var locationController: LocationCollectionController
    private lateinit var telephonyController: TelephonyCollectionController
    private lateinit var auditController: AuditLogController
    private lateinit var observationPersistence: ObservationPersistenceController
    private lateinit var securityAlerts: SecurityAlertController
    private lateinit var telemetryHistory: TelemetryHistoryController
    // v2.3.3 — Vigilancia de la recolección: escrituras fallidas e interrupciones.
    private lateinit var collectionHealthController: CollectionHealthController
    private var toneGenerator: ToneGenerator? = null
    /** v2.10.7 — Última promoción multiseñal escrita en el terminal (evita repetirla en cada ciclo). */
    private var lastLoggedPromotion: String? = null
    private var screenReceiver: BroadcastReceiver? = null
    /** Estado real de la pantalla; se lee de PowerManager al registrar el receptor (ver allí). */
    @Volatile private var isScreenOn = true
    @Volatile private var isUiVisible = false
    @Volatile private var collectionPausedForCriticalBattery = false
    // 3.0 — Se escribe en el hilo principal (onCreate/onDestroy) y lo lee el bucle periódico en
    // Dispatchers.Default. Es un indicador simple (una escritura, lecturas sin operación compuesta),
    // así que @Volatile basta para la visibilidad; el bucle también se para al cancelar el scope.
    @Volatile private var isServiceRunning = false
    
    private var lastGpsTriggerTime = 0L

    // v2.1: la confirmación temporal vive en core/TemporalConfidence para poder testearla de
    // extremo a extremo (ver ScenarioTest). El servicio solo la usa.
    // 3.0 (#20) — Reloj que cuenta el tiempo dormido: un hueco en Doze no puede parecer de 0 s.
    private val temporalConfidence = com.alexisgordr.icdetector.core.TemporalConfidence(
        CONFIRMATION_CYCLES,
        elapsedRealtimeMs = SystemClock::elapsedRealtime
    )
    // 3.0 — Mismo reloj que la confirmación temporal: elapsedRealtime cuenta el sueño profundo, así
    // que tras dormir la evidencia (90 s) y la promoción (30 s) caducan por el tiempo real. El reloj
    // por defecto (System.nanoTime) se detiene en sueño profundo y las alargaba.
    private val threatEpisodeTracker = com.alexisgordr.icdetector.core.ThreatEpisodeTracker(
        nowMs = SystemClock::elapsedRealtime
    )
    private val isolatedCellConfidence = com.alexisgordr.icdetector.core.IsolatedCellConfidence()
    @Volatile private var loggedStuckObservationToken = false
    @Volatile private var intensiveMonitoringUntilMs = 0L
    private var intensiveMonitoringStartedAtMs = 0L
    // Todas las lecturas comparten cachés, transición y confirmación temporal. Serializarlas evita
    // que dos callbacks publiquen la firma de una celda con el historial calculado para otra.
    private val cellProcessingMutex = Mutex()
    /** 3.0 (#24) — Entregas, pérdidas de señal y ciclos en curso. Ver CollectionGeneration. */
    private val collectionGeneration = com.alexisgordr.icdetector.core.CollectionGeneration()
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
    /** 3.0 — La última lectura del historial RF (H15) falló; se avisa una vez por racha. */
    @Volatile private var rfStabilityReadFailing = false
    private var cachedReputation: CellReputation? = null
    private var cachedFingerprint: CellRfFingerprint? = null
    private var cachedTrustGate: Boolean? = null
    /** Posición con la que se calculó [cachedFingerprint]; null si se calculó sin GPS. */
    private var fingerprintLocation: Location? = null

    /** v2.10.9 — Misma regla que el baseline de potencia: recalcular al moverse 150 m. */
    private fun fingerprintNeedsRefresh(current: Location?): Boolean {
        val previous = fingerprintLocation
        return com.alexisgordr.icdetector.core.RfFingerprintRefresh.needed(
            computedWithFix = previous != null,
            movedMeters = current?.let { previous?.distanceTo(it) ?: 0f }
        )
    }
    private var cachedLocalTrustEvidence = com.alexisgordr.icdetector.models.LocalCellTrustEvidence()
    private var cachedRfSignature = ""
    private var cachedRfTimestamp = 0L
    private var cacheTimestamp = 0L
    private val CELL_CACHE_TTL = 60_000L

    var alarmThreshold = -50f
    var isStrongSignalAlarmEnabled = true
    /** 3.0 — Avisos de red 2G/3G (Ajustes). Predeterminado: solo notificación. */
    @Volatile var legacyNetworkAlertMode: com.alexisgordr.icdetector.core.LegacyNetworkAlertMode =
        com.alexisgordr.icdetector.core.LegacyNetworkAlertMode.DEFAULT
    /** 3.0 — Modo de ubicación (Ajustes). Continuo por defecto; adaptativo ahorra batería. */
    @Volatile var locationMode: com.alexisgordr.icdetector.core.LocationMode =
        com.alexisgordr.icdetector.core.LocationMode.CONTINUOUS
    /**
     * Último modo anotado en el terminal, para dejar constancia de cada cambio. 3.0 — Solo se toca
     * desde [applyLocationMode], que se ejecuta siempre en el hilo principal (onCreate, receptor de
     * pantalla y, desde el bucle periódico, con withContext(Main)). Comparar y asignar es una
     * operación compuesta: @Volatile no la haría atómica; confinarla a un hilo, sí.
     */
    private var loggedLocationMode: com.alexisgordr.icdetector.core.LocationMode? = null

    /** 3.0 (#19) — Identidad completa de la última servidora; ver ServingIdentityChange. */
    private var prevServingIdentity: com.alexisgordr.icdetector.core.ServingIdentityChange.Identity? = null
    // Fix #1: guarda el cellId cuya alarma ya se ha persistido en el episodio actual, para no
    // registrar la misma evidencia en cada ciclo. Se resetea en cada cambio de celda (handover).
    // Estado para heurística 14 (band downgrade intra-LTE). Guardan la última celda
    // REGISTRADA analizada; el buffer de tendencia sobrevive al handover para detectar si
    // la señal venía degradándose progresivamente (excepción del garaje/sótano).
    private var prevBand: Int? = null
    /** 3.0 (#10) — eNodeB de la celda en la que se midió [prevBand]. */
    private var prevBandSite: String? = null
    /** 3.0 (#8) — NR-ARFCN de la servidora anterior si era NR (H14 en 5G SA). */
    private var prevNrArfcn: Int? = null
    /** 3.1.0 (#37) — De dónde salen [prevBand], [prevBandSite] y [prevNrArfcn] (ver BandContext). */
    private var bandContext: com.alexisgordr.icdetector.core.BandContext? = null
    /** 3.0 (#20) — Continuidad de cobertura: pérdidas de señal y huecos sin lecturas. */
    private val coverageContinuity = com.alexisgordr.icdetector.core.CoverageContinuity()
    private var prevRegisteredDbm: Int? = null
    private val recentRegisteredDbmTrend = CopyOnWriteArrayList<Int>()
    // 3.0 (corte de campaña) — Cambios de servidora para H10, registrados al recibir cada lectura (ver CellChangeHistory).
    private val cellChanges = com.alexisgordr.icdetector.core.CellChangeHistory(ThreatAnalyzer.PING_PONG_WINDOW_MS)
    // 3.1.0 — H17 en sombra: churn de 15 min (ver CellChurn). Reloj monotónico de la entrega.
    private val cellChurn = com.alexisgordr.icdetector.core.CellChurn()
    private var lastChurnLog: String? = null

    inner class LocalBinder : Binder() {
        fun getService(): MiniICService = this@MiniICService
    }

    /**
     * 3.0 (#20) — Pérdida de la servidora utilizable: invalida los ciclos en curso y rompe la
     * continuidad de la confirmación.
     */
    private fun onSignalLost() {
        collectionGeneration.lose()
        // 3.0 — Sin celda activa no hay 4G/5G continuo: reinicia la cuenta de cierre del episodio 2G/3G.
        if (::securityAlerts.isInitialized) securityAlerts.onServiceInterrupted()
    }

    fun forceRefresh() {
        // 3.0 (#24) — La pantalla se vacía: un ciclo en curso no puede volver a pintarla. No es una
        // pérdida de señal (#20): la confirmación en curso no vuelve a empezar.
        collectionGeneration.lose(signalLost = false)
        _cellFlow.value = emptyList()
        requestFreshCellInfo()
    }

    /** Selects the faster visual refresh only while the activity is in the foreground. */
    fun setUiVisible(visible: Boolean) {
        val becameVisible = visible && !isUiVisible
        isUiVisible = visible
        if (becameVisible && ::telephonyController.isInitialized) requestFreshCellInfo()
    }

    // v2.10.8 — Las notificaciones salían en el idioma del sistema aunque la app estuviera en otro:
    // el servicio no aplicaba el idioma elegido en Ajustes, solo MainActivity.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleController.localizedContext(newBase))
    }

    // v2.10.8 — El idioma se fijaba al arrancar el servicio. Al cambiarlo en Ajustes solo se
    // recreaba la pantalla y el servicio, que sigue en marcha, seguía publicando las notificaciones
    // en el idioma anterior. Ahora escucha el cambio y cambia sus textos al momento.
    @Volatile private var languageResources: Resources? = null

    override fun getResources(): Resources = languageResources ?: super.getResources()

    private val languageListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == LocaleController.KEY) applySelectedLanguage()
    }

    private fun applySelectedLanguage() {
        languageResources = LocaleController.localizedContext(baseContext).resources
        if (!::notificationController.isInitialized) return
        // Mismo ID de canal: Android solo actualiza el nombre y la descripción visibles.
        notificationController.createChannels()
        val serving = _cellFlow.value.firstOrNull { it.isRegistered }
        if (serving != null) {
            updateNotification(serving, force = true)
        } else {
            updateNotificationText(getString(R.string.monitoring_active), force = true)
        }
    }

    /**
     * 3.0 — True si el servicio se arrancó con la pregunta de la primera apertura pendiente o con una
     * restauración a medias: no ha abierto la base ni recoge nada, y se detiene. 3.1.0 (#47) —
     * También con una base de una versión más nueva (DatabaseGate).
     */
    @Volatile var collectionBlocked = false
        private set

    override fun onCreate() {
        super.onCreate()
        // 3.0 — Antes de abrir la base o registrar nada. Cubre cualquier vía de arranque (la
        // pantalla, un reinicio START_STICKY, otra app): sin decisión inicial, nada se recoge.
        if (!com.alexisgordr.icdetector.backup.FirstRun.allowsCollection(this)) {
            collectionBlocked = true
            Log.w("MiniIC", "Monitorización bloqueada: falta la decisión inicial o hay una restauración pendiente.")
            stopSelf()
            return
        }
        // 3.1.0 (#47) — Único punto de apertura de la base: si es de una versión más nueva no se
        // toca, no se recoge nada y la app lo explica (DatabaseGate). Antes, crash en bucle.
        if (!CellDbHelper.getInstance(this).openThroughGate()) {
            collectionBlocked = true
            Log.w("MiniIC", "Monitorización bloqueada: la base de datos es de una versión más nueva.")
            stopSelf()
            return
        }
        isServiceRunning = true
        getSharedPreferences(LocaleController.PREFS, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(languageListener)
        dbHelper = CellDbHelper.getInstance(this)
        mobilityFamiliarity = com.alexisgordr.icdetector.core.MobilityFamiliarityEngine(
            dbHelper, anchors = mobilityAnchorStore()
        )
        writeGate.launch(scope, Dispatchers.IO) {
            val recovery = mobilityFamiliarity.recover(mobilityClock())
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
        notificationController = ServiceNotificationController(this, MiniICService::class.java) { locationMode }
        databaseMaintenance = DatabaseMaintenance(dbHelper) { appendLog("[SYS]", it) }
        legacyNetworkProtection = LegacyNetworkProtection(
            notifications = notificationController,
            log = { appendLog("[SEC]", it) }
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
            },
            locationMode = { locationMode }
        )
        telephonyController = TelephonyCollectionController(
            context = this,
            log = { appendLog("[SYS]", it) },
            deliver = { cells, fresh, registered ->
                processCellInfo(cells, isFreshDelivery = fresh, fromRegisteredCallback = registered)
            },
            serviceStateSink = ::onServiceStateSnapshot
        )
        auditController = AuditLogController(
            log = ::appendLog,
            publishStatus = { _auditStatus.value = it },
            zeroOnlyCellCount = { taSanity.zeroOnlyCellCount }
        )
        // 3.0 — `versionName+commit`: separa en los datos las filas de compilaciones distintas.
        val appVersion = com.alexisgordr.icdetector.core.AppBuildId.current(
            runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        )
        // 3.0 — Perfil local en sombra: después de cada fila, en la misma cola; no toca el motor.
        val profilePrefs = getSharedPreferences("miniic_prefs", MODE_PRIVATE)
        localProfile = LocalProfileController(
            dbHelper = dbHelper,
            prefs = profilePrefs,
            deviceModel = com.alexisgordr.icdetector.core.profile.ProfileCompatibility.deviceModel(
                android.os.Build.MANUFACTURER, android.os.Build.MODEL
            ),
            // Opción experimental (Ajustes): solo cambia el modo guardado en cada evaluación.
            mode = {
                com.alexisgordr.icdetector.core.profile.LocalProfileOption.mode(profilePrefs.getBoolean(
                    com.alexisgordr.icdetector.core.profile.LocalProfileOption.PREF_KEY,
                    com.alexisgordr.icdetector.core.profile.LocalProfileOption.DEFAULT_ENABLED
                ))
            },
            appVersion = appVersion
        )
        writeGate.launch(scope, historyWriteDispatcher) {
            runCatching { localProfile.logSwitchOffByRestore() }
                .onFailure { e -> runCatching { localProfile.recordFailure("restore", e) } }
            runCatching { localProfile.bootstrapIfNeeded() }
                .onFailure { e -> runCatching { localProfile.recordFailure("bootstrap", e) } }
        }
        observationPersistence = ObservationPersistenceController(
            scope = scope,
            insert = { row -> dbHelper.logObservation(row, appVersion) },
            location = { getCurrentLocation()?.toObservedFix() },
            serviceState = { _serviceState.value },
            onWrite = ::noteWriteResult,
            onPeriodicMissingLocation = { _ ->
                locationController.markCoordinatesPending()
                requestHighAccuracyFix()
            },
            writeDispatcher = historyWriteDispatcher,
            writeGate = writeGate,
            historyGeneration = { historyEpoch.current() },
            locationMode = { locationMode.storedValue },
            afterWrite = localProfile::onRowWritten,
            onAfterWriteError = { e -> localProfile.recordFailure("evaluation", e) },
            // 3.0 — La fila cuenta como fallida (noteWriteResult avisa); el error queda en logcat.
            onInsertError = { e -> Log.e("MiniIC", "Escritura del historial fallida: ${e.javaClass.simpleName}", e) }
        )
        securityAlerts = SecurityAlertController(
            tone = { toneGenerator },
            legacyProtection = legacyNetworkProtection,
            log = { appendLog("[SEC]", it) },
            requestPreciseLocation = { requestHighAccuracyFix() },
            persistConfirmedAlarm = observationPersistence::recordConfirmedAlarm,
            notifyConfirmedAlarm = { cell ->
                scope.launch(Dispatchers.Main) { notificationController.showConfirmedAlarm(cell) }
            },
            strongSignalEnabled = { isStrongSignalAlarmEnabled },
            strongSignalThreshold = { alarmThreshold },
            legacyAlertMode = { legacyNetworkAlertMode }
        )
        telemetryHistory = TelemetryHistoryController(_dbmHistory, _rsrqHistory, _geoHistory)

        // Poda de histórico antiguo al arrancar (en segundo plano, no bloquea onCreate).
        // Evita que la tabla crezca sin límite registrando 24/7. v2.3.3: conserva
        // DEFAULT_RETENTION_DAYS (120) — antes 60, que se quedaban CORTOS para una campaña de 90
        // días y borraban el primer mes justo al abrir la app para exportar.
        databaseMaintenance.claimRun(force = true)
        // Si el proceso anterior terminó durante un episodio, no puede quedar marcado
        // eternamente como "en curso". Se conserva y se cierra como interrumpido.
        //
        // v2.10.10 — Va por la misma cola de un solo hilo que la captura forense y los incidentes,
        // y se encola antes de que llegue ningún ciclo: así se ejecuta siempre ANTES de la primera
        // muestra nueva. Antes iba por su cuenta en IO y, si se retrasaba, podía marcar como
        // interrumpido un caso que el servicio nuevo acababa de abrir.
        writeGate.launch(scope, forensicDispatcher) {
            try {
                dbHelper.closeOpenIncidents(activeIdentity = null, interrupted = true)
                dbHelper.interruptOpenForensicCases()
            } catch (_: Exception) {}
        }
        writeGate.launch(scope, Dispatchers.IO) { databaseMaintenance.run() }
        
        val prefs = getSharedPreferences("miniic_prefs", MODE_PRIVATE)
        collectionHealthController = CollectionHealthController(
            preferences = prefs,
            interruptedText = { hours, minutes ->
                getString(R.string.collection_interrupted_format, hours.toInt(), minutes.toInt())
            },
            writeFailureText = { getString(R.string.write_failure_notification) }
        )
        // Ajustes de servicios externos retirados (ver LegacyPreferences): se borran los secretos
        // y el estado que pudieran quedar de versiones anteriores.
        prefs.edit().apply { com.alexisgordr.icdetector.core.LegacyPreferences.KEYS.forEach(::remove) }.apply()
        // v2.3.3 — Continuidad de la recolección. Si el proceso murió (reinicio del móvil, OTA,
        // batería agotada) nadie lo anunciaba: la notificación se iba con el proceso y la
        // recolección quedaba parada sin dejar rastro. Ahora el hueco se calcula al arrancar y se
        // deja escrito, para que una interrupción sea un hecho observable y no una sorpresa en
        // diciembre al ver un agujero en el CSV.
        collectionHealthController.restore()?.let { appendLog("[SYS]", it) }
        locationMode = com.alexisgordr.icdetector.core.LocationMode.fromStored(
            prefs.getString(com.alexisgordr.icdetector.core.LocationMode.PREF_KEY, null)
        )
        legacyNetworkAlertMode = com.alexisgordr.icdetector.core.LegacyNetworkAlertMode.fromStored(
            prefs.getString(com.alexisgordr.icdetector.core.LegacyNetworkAlertMode.PREF_KEY, null)
        )
        restoreTaSanityEvidence()            // evidencia de TA superviviente a reinicios (v2.5)

        try {
            // v2.10.7 — Canal de notificaciones, no de alarma. STREAM_ALARM ignora el modo silencio
            // y los botones de volumen: un tester no tenía forma de callar el pitido durante una
            // alarma. Con STREAM_NOTIFICATION sigue el volumen de notificaciones y respeta
            // silencio, vibración y No molestar. Solo cambia cómo suena, no cuándo.
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        notificationController.createChannels()
        // 3.1.0 (#42) — Un fallo del arranque anterior queda en el terminal de este.
        CollectionHealthController.foregroundStartFailure(prefs)?.let {
            appendLog("[SYS]", com.alexisgordr.icdetector.core.MonitoringStop.terminalLine(it))
        }
        // En Android 14+ es obligatorio pasar el foregroundServiceType en startForeground.
        // Además, startForeground puede lanzar excepción (p. ej. ForegroundServiceStartNotAllowed
        // o SecurityException) si el permiso de ubicación no está concedido o el SO reinicia el
        // servicio en un estado restringido. Si eso ocurre, paramos limpiamente en vez de crashear.
        // 3.1.0 (#42) — Y ya no en silencio: notificación normal, evento y estado en la pantalla
        // (MonitoringStop).
        val foregroundStarted = com.alexisgordr.icdetector.core.MonitoringStop.startForeground(
            MonitoringStopSink(this), System.currentTimeMillis()
        ) {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notificationController.foregroundNotification(getString(R.string.monitoring_active)), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notificationController.foregroundNotification(getString(R.string.monitoring_active)))
            }
        }
        if (!foregroundStarted) {
            Log.e("MiniIC", "No se pudo iniciar el servicio en primer plano")
            stopSelf()
            return
        }
        // 3.1.0 (#42) — Para el arranque tras reiniciar (BootStart): el monitoreo está activado.
        prefs.edit().putBoolean(com.alexisgordr.icdetector.core.BootStart.PREF_MONITORING_ENABLED, true).apply()

        // 3.0 — Solo en modo continuo; el adaptativo lo anota applyLocationMode().
        if (locationMode == com.alexisgordr.icdetector.core.LocationMode.CONTINUOUS) {
            appendLog("[SYS]", "Ubicación continua activa (24/7). Consumo de batería elevado por diseño: las coordenadas son necesarias para H11, H13 y H16.")
        }

        registerTelephonyCallback()
        registerDisplayInfoCallback()
        registerScreenReceiver()

        collectionPausedForCriticalBattery = isBatteryCritical()
        if (collectionPausedForCriticalBattery) {
            locationController.updateCollection(isScreenOn, enabled = false)
            appendLog("[SYS]", "⚠ Batería crítica (<${CollectionPowerController.CRITICAL_PERCENT} %): ubicación continua en pausa hasta conectar el cargador.")
        } else {
            ensureCollectionWakeLock()
            applyLocationMode()
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
                        locationController.updateCollection(isScreenOn, enabled = false)
                        releaseCollectionWakeLock()
                        if (!collectionPausedForCriticalBattery) {
                            collectionPausedForCriticalBattery = true
                            appendLog("[SYS]", "⚠ Batería crítica (<${CollectionPowerController.CRITICAL_PERCENT} %): ubicación continua en pausa hasta conectar el cargador.")
                            updateNotificationText(getString(R.string.critical_battery_gps_paused))
                        }
                    } else {
                        ensureCollectionWakeLock()
                        if (collectionPausedForCriticalBattery) {
                            collectionPausedForCriticalBattery = false
                            appendLog("[SYS]", "Alimentación recuperada: ubicación continua y recolección 24/7 reanudadas.")
                        }
                        // GPS y reintentos limitados según modo, pantalla y recepción. 3.0 — En el
                        // hilo principal, como el resto de llamadas: loggedLocationMode y el
                        // controlador de ubicación quedan en un solo hilo. Es una llamada ligera.
                        withContext(Dispatchers.Main) { applyLocationMode() }
                    }

                    val isAirplaneModeOn = Settings.Global.getInt(
                        contentResolver,
                        Settings.Global.AIRPLANE_MODE_ON, 0
                    ) != 0

                    if (isAirplaneModeOn) {
                        // 3.0 (#24) — Modo avión: invalida el ciclo que siga analizando. En el hilo
                        // principal, como la publicación de cada ciclo: así no puede intercalarse
                        // con un ciclo que ya pasó mayPublish (o lo invalida antes, o vacía después
                        // de que termine de publicar). Solo esto; la lectura del ajuste y el resto
                        // del bucle siguen fuera del hilo principal.
                        withContext(Dispatchers.Main) {
                            onSignalLost()
                            _cellFlow.value = emptyList()
                            updateNotificationText(getString(R.string.no_signal_airplane), kind = com.alexisgordr.icdetector.core.MonitoringNotificationText.TextKind.NO_SIGNAL)
                        }
                        appendLog("[SYS]", "⚠️ Modo Avión activo. Suspendiendo escaneo.")
                        wasAirplaneModeOn = true
                    } else {
                        if (wasAirplaneModeOn) {
                            appendLog("[SYS]", "Conexión restaurada. Analizando nueva celda activa...")
                            wasAirplaneModeOn = false
                            // Al salir de modo avión forzamos un fix GPS fresco SÍ o SÍ (sin
                            // debounce): es un evento raro y crítico —si hubiera un IMSI-catcher
                            // empujándote o saltando de celda, necesitamos coordenadas reales ya,
                            // no cacheadas. El coste extra de batería puntual es aceptable.
                            locationController.requestPreciseFix(force = true)
                        }
                        requestFreshCellInfo()
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
            writeGate.launch(scope, Dispatchers.IO) { databaseMaintenance.run() }
        }
    }

    /**
     * Poda de mapas en memoria (NO toca la BD). Evita el crecimiento ilimitado de las
     * cachés indexadas por celda en sesiones muy largas. Es barato: solo actúa cuando se
     * supera el cap o hay errores caducados.
     */
    /**
     * v2.10.10 — "Borrar historial" coordinado con el servicio. Antes la pantalla borraba la base
     * directamente y el servicio seguía con su estado: el caso forense abierto apuntaba a un caso
     * borrado, el prebúfer guardaba observaciones anteriores y las cachés de líneas base, confianza
     * y episodios seguían usando el historial borrado.
     *
     * Orden: se detiene el análisis (mutex de ciclos), se descartan las escrituras pendientes de
     * antes del borrado (generación), se vacía la cola forense e incidentes, se borra la base en
     * esas mismas colas y, por último, se olvida todo el estado derivado del historial.
     */
    suspend fun clearHistory() {
        var outcome: com.alexisgordr.icdetector.core.HistoryClear.Outcome =
            com.alexisgordr.icdetector.core.HistoryClear.Outcome.RestoreInProgress
        cellProcessingMutex.withLock {
            // Bajo el cerrojo de la época: ningún resultado empezado antes se aplica después.
            historyEpoch.invalidate()
            withContext(forensicDispatcher) {
                forensicRecorder.reset()
                // 3.1.0 (#41) — También el borrado pasa por la puerta de escrituras: con una
                // restauración en curso (puerta cerrada) no se toca la base que se está copiando.
                // Un error al borrar se informa como tal, no como restauración (HistoryClear).
                outcome = com.alexisgordr.icdetector.core.HistoryClear.run(writeGate, scope, historyWriteDispatcher) { dbHelper.clear() }
            }
            withContext(Dispatchers.Main) { forgetHistoryDerivedState() }
        }
        appendLog("[SYS]", com.alexisgordr.icdetector.core.HistoryClear.logLine(outcome))
    }

    private fun forgetHistoryDerivedState() {
        // Cachés de líneas base, reputación, huella, confianza local y puerta de confianza.
        cachedCellSignature = ""
        cacheTimestamp = 0L
        cachedHistory = emptyList()
        cachedBaseline = null
        cachedRfSignature = ""
        cachedRfTimestamp = 0L
        cachedRfStability = null
        cachedReputation = null
        cachedFingerprint = null
        cachedTrustGate = null
        fingerprintLocation = null
        cachedLocalTrustEvidence = com.alexisgordr.icdetector.models.LocalCellTrustEvidence()
        // Episodios en curso: empiezan de cero, como en un arranque limpio.
        temporalConfidence.reset()
        threatEpisodeTracker.reset()
        isolatedCellConfidence.reset()
        lastIncidentIdentity = null
        lastLoggedPromotion = null
        securityAlerts.resetAlarmEpisode()
        cellChanges.clear()
        cellChurn.reset()
        lastChurnLog = null
    }

    private fun registerScreenReceiver() {
        // 3.0 — Bug found during testing: isScreenOn empezaba siempre en true. Si Android
        // reiniciaba el servicio con la pantalla apagada, el modo adaptativo encendía el GPS
        // continuo hasta el siguiente evento de apagado. Se lee el estado real antes de aplicar el
        // modo de ubicación; los eventos de pantalla lo mantienen al día desde aquí.
        isScreenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOn = true
                        // 3.0 — En adaptativo, al volver a usar el móvil se pide una ubicación
                        // fresca y vuelve el GPS continuo mientras la pantalla siga encendida.
                        if (locationMode != com.alexisgordr.icdetector.core.LocationMode.CONTINUOUS &&
                            !collectionPausedForCriticalBattery
                        ) {
                            applyLocationMode()
                            requestHighAccuracyFix()
                        }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn = false
                        if (!collectionPausedForCriticalBattery) applyLocationMode()
                    }
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

    /**
     * 3.0 — Enciende o apaga el GPS continuo según el modo de ubicación y la pantalla, y deja una
     * línea en el terminal cada vez que cambia el modo (para que la caja negra lo registre).
     */
    private fun applyLocationMode() {
        val mode = locationMode
        locationController.updateCollection(isScreenOn, enabled = !collectionPausedForCriticalBattery)
        if (mode != loggedLocationMode) {
            loggedLocationMode = mode
            appendLog(
                "[GPS]",
                when (mode) {
                    com.alexisgordr.icdetector.core.LocationMode.ADAPTIVE ->
                        "Modo de ubicación adaptativo: GPS continuo solo con la pantalla encendida."
                    com.alexisgordr.icdetector.core.LocationMode.INTELLIGENT ->
                        "Modo de ubicación inteligente: intentos GPS breves cada 45 s cuando hay recepción."
                    com.alexisgordr.icdetector.core.LocationMode.CONTINUOUS ->
                        "Modo de ubicación continuo: GPS activo también con la pantalla apagada."
                }
            )
        }
    }

    private fun requestHighAccuracyFix(force: Boolean = false) {
        locationController.requestPreciseFix(force)
    }

    private fun persistPreciseLocation(location: Location) {
        val cell = _cellFlow.value.firstOrNull { it.isRegistered } ?: return
        writeGate.launch(scope, Dispatchers.IO) {
            val updated = dbHelper.updateNullCoordinates(
                cell.cellId, cell.mnc, cell.tac, cell.mcc, cell.radioTech,
                location.latitude, location.longitude,
                fixTimeMs = location.time,
                accuracyM = location.takeIf { it.hasAccuracy() }?.accuracy
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
        if (collectionBlocked) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            // 3.1.0 (#42) — Detenido por el usuario: no se vuelve a arrancar tras un reinicio.
            getSharedPreferences("miniic_prefs", MODE_PRIVATE).edit()
                .putBoolean(com.alexisgordr.icdetector.core.BootStart.PREF_MONITORING_ENABLED, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * 3.0 — Antes de restaurar: no se aceptan escrituras nuevas y se espera a que terminen todas las
     * que ya estaban en cola o en marcha. False si no terminan a tiempo: entonces no se restaura.
     */
    suspend fun quiesceForRestore(timeoutMs: Long = 30_000L): Boolean = writeGate.closeAndDrain(timeoutMs)

    /** 3.0 — La restauración se canceló antes de reemplazar nada: se vuelve a escribir. */
    fun resumeWritesAfterCancelledRestore() = writeGate.reopen()

    override fun onDestroy() {
        super.onDestroy()
        // Bloqueado en onCreate: no se inicializó nada que haya que cerrar.
        if (collectionBlocked) return
        isServiceRunning = false
        getSharedPreferences(LocaleController.PREFS, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(languageListener)
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
        // 3.0 — Ticket de esta entrega, en cuanto exista: un fallo después de abrirla debe cerrarla.
        var deliveredTicket: com.alexisgordr.icdetector.core.CollectionGeneration.Ticket? = null
        try {
            // v2.10.4 — Sondeo del ServiceState en cada entrega de celdas. El tracker descarta las
            // repeticiones, así que solo un cambio real produce línea de terminal y evento.
            telephonyController.pollServiceState()?.let(::onServiceStateSnapshot)
            val list = mutableListOf<CellData>()   // v2.1: ya no se reasigna (el TA no se comparte entre celdas)
            var activeObservationToken: Long? = null
            // v2.10.4 — Un token por celda parseada (null en las no registradas). El token que
            // cuenta es el de la celda que ServingCellSelection elija como servidora.
            val parsedTokens = mutableListOf<Long?>()
            infoList?.forEach { info ->
                val networkTypeString = if (info.isRegistered && info is CellInfoLte) {
                    getLteSpecificType()
                } else if (info is CellInfoLte) {
                    "4G LTE"
                } else if (info is CellInfoNr) {
                    // 3.0 — Que exista un CellInfoNr no demuestra 5G SA (en NSA también aparece la
                    // portadora NR). Sin más evidencia, solo 5G NR (las filas anteriores dicen 5G NR (SA)).
                    "5G NR"
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
                if (parsed != null) {
                    list.add(parsed)
                    // El token pertenece a la celda registrada que se convertirá en activeRaw
                    // (v2.10.4: la elegida por ServingCellSelection, no simplemente la primera).
                    // Una entrada NR inválida ya no puede dominar el máximo ni mezclar dominios de
                    // reloj con el ancla LTE.
                    parsedTokens.add(
                        if (parsed.isRegistered) {
                            info.timestampMillis
                        } else null
                    )
                }
            }

            if (list.isEmpty()) {
                // 3.0 (#24) — Una pérdida de señal invalida el ciclo que aún esté analizando.
                onSignalLost()
                _cellFlow.value = emptyList()
                updateNotificationText(getString(R.string.no_signal_airplane), kind = com.alexisgordr.icdetector.core.MonitoringNotificationText.TextKind.NO_SIGNAL)
                return
            }

            // v2.10.4 — Una sola celda servidora, elegida por el estado de conexión que declara el
            // módem (PRIMARY_SERVING) y, si no lo declara, la primera registrada. Se coloca en la
            // posición 0 con sus portadoras secundarias adjuntas, de modo que todo el código que
            // busca "la primera registrada" (TA, Stable-Site, H15, historial, UI) ve la misma.
            // Ver ServingCellSelection: antes se analizaban todas las registradas y ganaba la de
            // más señal, que con agregación de portadoras podía ser una secundaria.
            val servingIndex = com.alexisgordr.icdetector.core.ServingCellSelection.primaryIndex(list)
            if (servingIndex < 0) {
                // Si el módem declaró una primaria pero no proporcionó una señal utilizable,
                // abstenerse: una secundaria nunca puede heredar el historial de la primaria.
                val declaredPrimary = com.alexisgordr.icdetector.core.ServingCellSelection.hasDeclaredPrimary(list)
                onSignalLost()
                _cellFlow.value = com.alexisgordr.icdetector.core.ServingCellSelection.abstentionPublication()
                if (declaredPrimary) logUnusablePrimaryAbstention()
                updateNotificationText(getString(R.string.searching_network), kind = com.alexisgordr.icdetector.core.MonitoringNotificationText.TextKind.NO_SIGNAL)
                return
            }
            activeObservationToken = parsedTokens.getOrNull(servingIndex)
            // La elección ya se hizo viendo también la primaria no utilizable. A partir de aquí
            // se conserva el filtro histórico para que métricas inválidas no entren como vecinas.
            val servingFirst = com.alexisgordr.icdetector.core.ServingCellSelection.withPrimaryFirst(
                list.filter { it.dbm != Int.MAX_VALUE && it.dbm < 100 }
            )
            list.clear()
            list.addAll(servingFirst)
            logRadioContext(list.firstOrNull()?.takeIf { it.isRegistered }, list.count { it.isRegistered })

            // Una entrega con trabajo real invalida a otra que esté esperando el mutex. 3.0 (#24):
            // la lista vacía y la abstención también invalidan, pero solo a la hora de PUBLICAR
            // (ver collectionGeneration.lose() arriba): un ciclo en curso no puede pintar ni
            // alertar sobre una celda que ya se perdió.
            val cycleTicket = collectionGeneration.deliver(
                list.firstOrNull { it.isRegistered }?.identityKey ?: "N/A"
            )
            deliveredTicket = cycleTicket
            // 3.0 (A03) — Hora, posición y estado de servicio de ESTA lectura, fijados al recibirla.
            // El análisis puede tardar; la fila del historial y el propio análisis usan este
            // contexto, no el del momento en que termine. El estado de servicio ya se ha
            // sondeado al principio de esta entrega.
            val deliveryLocation = getCurrentLocation()
            val observationContext = observationPersistence.capture(deliveryLocation?.toObservedFix())
            // 3.0 (#20) — Instante monotónico de la entrega, para medir huecos sin lecturas.
            val deliveredAtElapsedMs = SystemClock.elapsedRealtime()

            // v2.1 — EL TIMING ADVANCE NO SE COMPARTE ENTRE CELDAS.
            //
            // Antes se cogía el PRIMER TA disponible de toda la lista (vecinas incluidas) y se le
            // estampaba a la celda registrada si ella no tenía. Un TA es la distancia de ida y
            // vuelta a UNA antena concreta: atribuírselo a otra es inventar una geometría. Y esa
            // geometría alimenta H6 (en 3.0, la proximidad por TA; la rama "Suplantación TA" de -40
            // se retiró por no tener datos en producción): la regla podía estar midiendo otra antena.
            //
            // Se mantiene el único caso legítimo que esto cubría: que la MISMA celda aparezca
            // duplicada en la lista (pasa con NSA y con algunos módems) y solo una de las entradas
            // traiga el TA. Ahí sí es el mismo emisor, así que se copia junto con su unidad.
            val activeIndex = list.indexOfFirst { it.isRegistered }
            if (activeIndex >= 0) {
                val activeCell = list[activeIndex]
                // 3.0 (#21) — Solo de la MISMA celda duplicada: misma tecnología y mismo emisor
                // físico (PCI/frecuencia). Ver TimingAdvanceBorrowing.
                run {
                    val sameCellWithTa = com.alexisgordr.icdetector.core.TimingAdvanceBorrowing.source(activeCell, list)
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
                // 3.0 — Clave por emplazamiento (eNodeB en LTE), no por identidad completa; con el CID
                // ilegible un cero no cuenta (ver TimingAdvanceSanity.evidenceKey).
                val taKey = com.alexisgordr.icdetector.core.TimingAdvanceSanity.evidenceKey(current)
                // v2.5 — La evidencia sobrevive a los reinicios del servicio; sin esto, la misma
                // lectura de TA=0 se etiquetaba LTE_INDEX o STUB_ZERO según cuánto llevara vivo el
                // proceso, y el historial dejaba de ser autoconsistente (medido: 319 vs 394 filas).
                // 3.0 (#33) — Evidencia por unidad: un LTE que reporta no oculta un GSM/NR a cero.
                if (taSanity.observe(current.timingAdvanceUnit, taKey, current.timingAdvance)) persistTaSanityEvidence()
                // Unidad efectiva (STUB_ZERO si se concluyó) y, 3.1.0 (#43), si el veredicto sigue pendiente.
                list[activeIndex] = taSanity.annotate(current)
            }

            // 3.0 — Vecinas para el análisis (H1-H5, H7, H11, H16, densidad bayesiana, Stable-Site
            // y diagnóstico): sin las portadoras SECONDARY_SERVING que el módem publica sin
            // isRegistered. La caja negra conserva el contexto completo, secundarias incluidas.
            val neighbors = com.alexisgordr.icdetector.core.ServingCellSelection.neighbours(list)
            val nonServingContext = com.alexisgordr.icdetector.core.ServingCellSelection.nonServingEntries(list)
            val activeRaw = list.firstOrNull { it.isRegistered }
            // Milisegundos desde el arranque de la celda registrada aceptada por el parser.
            // 3.0 — Sin marca de tiempo (o 0), desconocido: IsolatedCellConfidence lo trata como fresco.
            val observationToken = activeObservationToken?.takeIf { it > 0L }

            // 3.0 — Lista sin celda registrada: tampoco hay servicio continuo (ver onSignalLost).
            if (activeRaw == null && ::securityAlerts.isInitialized) securityAlerts.onServiceInterrupted()

            // 3.0 (corte de campaña) — H10: el cambio de servidora se registra AQUÍ, al recibir la lectura y antes
            // de su análisis, para que el ciclo que lo observa ya lo cuente. Antes se guardaba al
            // publicar (checkAlerts, después de ThreatAnalyzer) y el tercer cambio se detectaba un
            // ciclo tarde. Se hace en el hilo principal, que recibe las entregas en orden.
            // Un ciclo que después se descarta (isLatest/mayPublish) DEJA su cambio en el historial, a
            // propósito: el módem sí informó de esa servidora, y los descartes ocurren justo cuando la
            // servidora cambia mientras se analiza; quitarlos ocultaría los cambios rápidos que H10
            // busca. Igual que la transición de H16 y el viaje de movilidad, es una observación del
            // ciclo, no un resultado publicado. La copia es la de este instante: una lectura posterior
            // no entra en el análisis de esta.
            val changeTimeMs = System.currentTimeMillis()
            if (activeRaw != null) {
                cellChanges.observe(com.alexisgordr.icdetector.core.ServingIdentityChange.of(activeRaw), changeTimeMs)
                // 3.1.0 (H17, en sombra) — Con el mismo criterio, el churn de 15 min también registra
                // aquí cada servidora, con el reloj monotónico de la entrega: un ciclo descartado deja
                // su cambio. La foto (snapshot) se toma en el análisis.
                cellChurn.observe(com.alexisgordr.icdetector.core.ServingIdentityChange.of(activeRaw), deliveredAtElapsedMs)
            }
            val cellChangeHistory = cellChanges.snapshot()

            writeGate.launchSource(scope, Dispatchers.IO) {
                cellProcessingMutex.withLock {
                if (!collectionGeneration.isLatest(cycleTicket)) return@withLock
                // 3.0 — Límite del ciclo: un fallo interno no deja el último resultado como si fuera
                // actual ni genera alarmas. Sin reintentos: lo que el ciclo ya hizo (ver la nota en
                // onAnalysisFailed) queda hecho una sola vez. La cancelación se relanza.
                com.alexisgordr.icdetector.core.AnalysisHealth.guard(onFailure = { errorType ->
                    withContext(Dispatchers.Main) { onAnalysisFailed(cycleTicket, errorType) }
                }) {
                // 3.0 (#20) — Tras una pérdida de señal, el contexto de banda de antes del hueco no
                // sirve para comparar: H14 queda N/A hasta tener una banda previa nueva.
                val signalLosses = collectionGeneration.losses
                // También un hueco de más de 2 min sin lecturas, aunque no llegara una lista vacía.
                val continuityBreak = coverageContinuity.check(signalLosses, deliveredAtElapsedMs)
                val continuityBroken = continuityBreak != com.alexisgordr.icdetector.core.CoverageContinuity.Break.NONE
                if (continuityBroken) {
                    // 3.0 (#20) — La racha de "sin vecinas" de H1 tampoco cruza el hueco: dos
                    // muestras antes y una después no confirman el aislamiento.
                    isolatedCellConfidence.reset()
                    prevBand = null
                    prevBandSite = null
                    prevNrArfcn = null
                    bandContext = null
                    prevRegisteredDbm = null
                    recentRegisteredDbmTrend.clear()
                    // 3.1.0 — H17: el churn de antes de la pérdida o del hueco no se suma al de después.
                    // Esta entrega (y las posteriores) ya están observadas: solo se olvida lo anterior.
                    cellChurn.restartAt(deliveredAtElapsedMs, servedAtMs = activeRaw != null)
                }
                val currentLocation = deliveryLocation
                // Edad del fix en la ENTREGA (reloj monotónico); sin marca monotónica, desconocida.
                val deliveryFixAgeMs = currentLocation?.elapsedRealtimeNanos?.takeIf { it > 0 }
                    ?.let { deliveredAtElapsedMs - it / 1_000_000L }
                // 3.1.0 (H17, en sombra) — Churn de 15 min (observado al recibir la entrega): se guarda
                // en la fila y se anota en el terminal; no se evalúa ni puntúa (CellChurn).
                val churn = cellChurn.snapshot(deliveredAtElapsedMs, currentLocation?.takeIf { it.hasSpeed() }?.speed, deliveryFixAgeMs)
                if (activeRaw != null) {
                    val churnLog = "15 min: ${churn.distinctCells15m} celdas distintas, ${churn.changes15m} cambios; movimiento ${churn.motion.name} (sin evaluar)"
                    if (churnLog != lastChurnLog) {
                        lastChurnLog = churnLog
                        appendLog("[H17 sombra]", churnLog)
                    }
                }
                // Cellular polling only reads motion. New evidence enters from real Location
                // callbacks, so a repeated lastKnownLocation cannot manufacture static time.
                // 3.0 — Reloj monotónico, el mismo con que se sellan los fixes (no la hora civil).
                val motionEvidence = stableSiteMotion.current(SystemClock.elapsedRealtime())
                // Mobility is contextual metadata only. Its result is deliberately not passed to
                // ThreatAnalyzer, LocalCellTrust, Stable-Site, alerts or forensics.
                // 3.0 — Como la racha de aislamiento de H1 (más abajo), es un efecto del ANÁLISIS,
                // no de la publicación: un ciclo descartado en mayPublish ya lo ha observado. Es una
                // observación real y su token de frescura evita contarla dos veces.
                val mobility = if (activeRaw != null && activeRaw.cellId != "N/A") {
                    // 3.0 (G4) — Tras una pérdida de servicio o un hueco no hay arista desde la celda anterior.
                    mobilityFamiliarity.observe(activeRaw.identityKey, motionEvidence, mobilityClock(), serviceContinuous = !continuityBroken)
                } else com.alexisgordr.icdetector.core.MobilityObservation(
                    com.alexisgordr.icdetector.core.MobilityFamiliarity.UNKNOWN_ON_ROUTE
                )
                val mobilityLog = "${mobility.tripId}|${mobility.familiarity}|${mobility.goodEdges}|${mobility.arrivalPriorTrips}|${mobility.event}"
                if (mobilityLog != lastMobilityLog) {
                    lastMobilityLog = mobilityLog
                    appendLog(
                        "[MOBILITY]",
                        // 3.0 (G2) — Dos conceptos: la celda en viajes anteriores y la llegada en este viaje.
                        "cellHistory=${mobility.familiarity} goodEdges=${mobility.goodEdges} trip=${mobility.tripId ?: "none"} " +
                            "arrival=${mobility.arrivalPriorTrips?.let { "priorTrips=$it" } ?: "none"}" +
                            (mobility.event?.let { " event=$it" } ?: "")
                    )
                }
                val siteKeys = currentLocation?.takeIf { it.accuracy <= 50f }?.let {
                    com.alexisgordr.icdetector.core.StableSiteKey.candidates(it.latitude, it.longitude)
                }.orEmpty()
                // 3.0 — El sitio se evalúa ANTES de registrar el cambio de servidora, para saber si el
                // episodio que se abre en este ciclo empieza con el sitio ya maduro (avisos
                // silenciosos). La evaluación no lee los episodios, así que el orden no la cambia.
                val stableSiteCandidate = if (activeRaw != null && activeRaw.cellId != "N/A" && siteKeys.isNotEmpty()) {
                    com.alexisgordr.icdetector.core.StableSiteCandidateSelector.select(
                        siteKeys.map { key -> dbHelper.evaluateStableSiteCandidate(key, activeRaw, stableSitePreviousIdentity, motionEvidence) }
                    )!!
                } else com.alexisgordr.icdetector.core.StableSiteDecision(reason = "NO_SERVING_CELL")
                // Episodios de Stable-Site: registran el cambio de servidora observado y su decisión
                // entra en el análisis (LocalCellTrustEngine), así que van antes de mayPublish, como
                // el viaje de movilidad. No enseñan nada al sitio: eso es recordStableSiteContext,
                // que solo corre con un ciclo publicado.
                if (activeRaw != null && activeRaw.cellId != "N/A") {
                    val episodeObservation = dbHelper.observeStableSiteEpisodeStart(activeRaw.identityKey, stableSitePreviousIdentity)
                    recordStableSiteEpisodeStart(episodeObservation, stableSiteCandidate)
                }
                val stableSiteDecision = if (activeRaw != null && activeRaw.cellId != "N/A") {
                    dbHelper.applyStableSiteDecision(stableSiteCandidate, activeRaw, siteKeys)
                } else stableSiteCandidate
                val siteKey = stableSiteDecision.siteKey

                // H16 se prepara antes de analizar la celda: aquí todavía conservamos la última
                // servidora. El resultado se retiene 20 s para que los tres ciclos temporales
                // puedan observar el mismo handover, sin convertirlo en un fallo permanente.
                // 3.0 — Escribe cell_transitions YA, antes de mayPublish (ver ServingTransitionTracker).
                // Es una de las escrituras que observan un hecho real del ciclo y no dependen de que
                // se publique: el viaje de movilidad, los episodios de Stable-Site (arriba; su
                // decisión la usa el propio análisis) y esta transición. Lo que se aprende o se
                // muestra (historial, incidentes, caja negra, contexto de Stable-Site) va después.
                val transitionCoherence = if (activeRaw != null && activeRaw.cellId != "N/A") {
                    transitionTracker.evaluate(activeRaw, currentLocation, neighbors, serviceContinuous = !continuityBroken)
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
                        // v2.10.9 — La huella RSRQ/SINR se acota a la posición actual, pero esta
                        // caché solo miraba la celda y el TTL: tras moverte podía compararse una
                        // lectura con la huella de la zona anterior hasta 60 s. Igual que el
                        // baseline de potencia, se recalcula al desplazarse 150 m.
                        if (fingerprintNeedsRefresh(currentLocation)) {
                            cachedFingerprint = dbHelper.getCellRfFingerprint(
                                activeRaw.cellId, activeRaw.mnc, activeRaw.tac, activeRaw.mcc,
                                radio = activeRaw.radioTech,
                                nearLocation = currentLocation
                            )
                            fingerprintLocation = currentLocation
                        }
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
                        fingerprintLocation = currentLocation
                        cachedLocalTrustEvidence = dbHelper.getLocalCellTrustEvidence(activeRaw)
                        // Solo para la pantalla de madurez (#18): misma caché por identidad.
                        cachedTrustGate = dbHelper.hasTrustedBaselineGate(
                            activeRaw.cellId, activeRaw.mnc, activeRaw.tac, activeRaw.mcc, activeRaw.radioTech
                        )
                        // 3.0 — Lectura fallida de H15: null (H15 se abstiene este ciclo) y sin caché, para
                        // reintentar en el siguiente. Una línea al empezar la racha y otra al recuperarse;
                        // sin identificadores ni ubicación.
                        if (st == null) {
                            if (!rfStabilityReadFailing) {
                                rfStabilityReadFailing = true
                                appendLog("[H15]", "Lectura del historial RF fallida: H15 no se evalúa hasta que vuelva a leerse.")
                            }
                        } else {
                            if (rfStabilityReadFailing) {
                                rfStabilityReadFailing = false
                                appendLog("[H15]", "Lectura del historial RF restablecida.")
                            }
                            cachedRfSignature = rfSig
                            cachedRfStability = st
                            cachedRfTimestamp = nowRf
                        }
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
                // The modem can briefly publish an empty neighbour list even in ordinary
                // coverage. Do not let one such snapshot become H1 evidence that an episode can
                // retain: require three fresh consecutive deliveries for the same serving cell.
                // 3.0 — Una sola lectura por observación: la usan el análisis y su explicación.
                val wifiActive = isWifiConnected()
                val isolatedCellConfirmed = activeRaw?.let { active ->
                    isolatedCellConfidence.observe(
                        identity = active.identityKey,
                        candidate = !wifiActive && neighbors.isEmpty() && active.dbm >= -80,
                        observationToken = observationToken
                    )
                } ?: false
                // 3.0 — Un módem que repite siempre la misma marca de tiempo deja H1 en N/A: decirlo una vez.
                if (isolatedCellConfidence.stuckOnRepeatedToken) {
                    if (!loggedStuckObservationToken) {
                        loggedStuckObservationToken = true
                        appendLog("[RADIO]", ISOLATED_TOKEN_STUCK_LOG)
                    }
                } else loggedStuckObservationToken = false

                // v2.10.4 — Solo se analiza la servidora (posición 0). Las demás entradas
                // registradas son portadoras secundarias: se muestran, no se juzgan con el
                // historial de otra celda.
                val analyzedList = list.mapIndexed { index, cell ->
                    if (index == 0 && cell.isRegistered) {
                        val analyzed = ThreatAnalyzer.analyzeThreats(
                            active = cell,
                            neighbors = neighbors,
                            cellChangeHistory = cellChangeHistory,
                            currentLocation = currentLocation,
                            preloadedHistory = preloadedHistory,
                            isWifiActive = wifiActive,
                            signalBaseline = signalBaseline,
                            previousBand = prevBand,
                            previousDbm = prevRegisteredDbm,
                            previousBandSite = prevBandSite,
                            previousNrArfcn = prevNrArfcn,
                            recentRegisteredDbm = recentRegisteredDbmTrend.toList(),
                            rfStability = rfStability,
                            reputation = reputation,
                            rfFingerprint = rfFingerprint,
                            transitionCoherence = transitionCoherence,
                            isolatedCellConfirmed = isolatedCellConfirmed,
                            // 3.0 — La velocidad de H10 solo vale con un fix reciente; sin marca monotónica, vieja.
                            // Edad en la ENTREGA, no tras esperar al mutex del análisis.
                            locationFixAgeMs = deliveryFixAgeMs,
                            // 3.1.0 (#46) — La precisión que se guarda en la fila (GpsAccuracyM).
                            locationAccuracyM = observationContext.fix?.accuracyM,
                            // 3.0 (corte de campaña) — H3 (servidora frente a SIM) usa el ServiceState de ESTA
                            // lectura, el mismo que se guarda en la fila.
                            serviceState = observationContext.service
                        )
                        com.alexisgordr.icdetector.core.LocalCellTrustEngine.apply(
                            analyzed,
                            localTrustEvidence,
                            stableSiteDecision
                        ).copy(churn = churn)
                    } else {
                        // Las vecinas no se evalúan como amenaza; se mantienen como contexto.
                        cell
                    }
                }

                val published = withContext(Dispatchers.Main) {
                    // 3.0 (#24) — Tras analizar: si mientras tanto se perdió la señal o cambió la
                    // servidora, este resultado ya no describe el presente. No se publica, no
                    // alerta, no se guarda en historial/incidentes/caja negra, no enseña a
                    // Stable-Site ni alimenta los contadores de confirmación. Las entregas llegan
                    // por el hilo principal, así que nada puede cambiar entre esta comprobación y
                    // el resto del bloque.
                    if (!collectionGeneration.mayPublish(cycleTicket)) return@withContext false
                    // 3.0 (#20) — La confirmación temporal y los episodios empiezan de nuevo después
                    // de un hueco de cobertura. La línea del terminal deja constancia en la caja negra.
                    coverageContinuity.acknowledge(signalLosses, deliveredAtElapsedMs)
                    if (continuityBroken) {
                        temporalConfidence.interrupt()
                        threatEpisodeTracker.interrupt()
                        appendLog(
                            "[SEC]",
                            if (continuityBreak == com.alexisgordr.icdetector.core.CoverageContinuity.Break.GAP) {
                                "Continuidad interrumpida por un hueco sin lecturas: la confirmación vuelve a empezar."
                            } else {
                                "Continuidad interrumpida por pérdida de señal: la confirmación vuelve a empezar."
                            }
                        )
                    }
                    // v2.10.4 — La servidora va siempre primero; nunca se reordena por potencia.
                    val analyzedServing = analyzedList.firstOrNull()?.takeIf { it.isRegistered }
                    val sorted = listOfNotNull(analyzedServing) +
                        analyzedList.drop(if (analyzedServing != null) 1 else 0).sortedWith(
                            compareByDescending<CellData> { it.isRegistered }
                                .thenByDescending { it.dbm }
                        )

                    val active = analyzedServing
                    if (active != null) {
                        val stableSiteStartedWatching = stableSiteDecision.enforced && !stableSiteIntensiveActive
                        stableSiteIntensiveActive = stableSiteDecision.enforced
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
                        // 3.0 — Aviso silencioso de Stable-Site: solo desde un ciclo que se publica
                        // (ya pasó mayPublish), nunca desde un análisis descartado.
                        maybeNotifyStableSite(stableSiteDecision)
                        // Observe the trust result immediately after LocalCellTrustEngine. This is
                        // a one-way evidence signal and cannot alter the detector input below.
                        // Always feed the RF contradiction tracker. A real contradiction has
                        // priority over contextual novelty and must not go blind during a hold.
                        val liveTrustContradiction = trustContradictionTransitions.observe(active)
                        val trustContradictionTransition = if (
                            stableSiteDecision.enforced && liveTrustContradiction == TrustContradictionSignal.NONE
                        ) TrustContradictionSignal.SITE_NOVELTY else liveTrustContradiction
                        // Identidad completa de la servidora: incidentes y caja negra.
                        val cacheKey = active.identityKey

                        // Aplicar confirmación temporal antes de alertas
                        val episode = threatEpisodeTracker.apply(
                            active,
                            observationToken,
                            isFreshDelivery = isFreshDelivery
                        )
                        if (episode.startedWatching) {
                            appendLog("[SEC]", getString(R.string.episode_watch_started))
                            locationController.requestPreciseFix(force = true)
                            requestFreshCellInfo()
                        }
                        // v2.10.7 — Una línea por episodio, no una por ciclo: durante una alarma
                        // la misma frase llegaba a repetirse cada segundo y tapaba el resto del
                        // terminal. Solo afecta al registro; la promoción no cambia.
                        val promotionLogKey = if (episode.promoted) {
                            episode.cell.identityKey + "|" + episode.families.joinToString()
                        } else null
                        if (promotionLogKey != null && promotionLogKey != lastLoggedPromotion) {
                            appendLog("[SEC]", getString(
                                R.string.episode_promoted_format,
                                episode.families.joinToString()
                            ))
                        }
                        lastLoggedPromotion = promotionLogKey

                        // 3.0 — Durante un ping-pong (H10 fallida) la racha se acumula sobre el
                        // conjunto de identidades, no sobre la celda actual: alternar entre ellas
                        // es justo lo sospechoso y no debe reiniciarla. Mismo reloj que el
                        // historial de cambios.
                        val confirmationKey = com.alexisgordr.icdetector.core.PingPongRule.confirmationKey(
                            currentIdentity = episode.cell.identityKey,
                            history = cellChangeHistory,
                            nowMs = System.currentTimeMillis(),
                            pingPongFailed = episode.cell.heuristicReport.pingPong == HeuristicStatus.FAILED
                        )
                        val temporalActive = temporalConfidence.apply(
                            episode.cell,
                            observationToken,
                            isFreshDelivery = isFreshDelivery,
                            heldWithoutEvidence = episode.heldWithoutEvidence,
                            confirmationKey = confirmationKey
                        )
                        updateIntensiveMonitoring(
                            watching = episode.watching || stableSiteDecision.enforced,
                            startedWatching = episode.startedWatching || stableSiteStartedWatching,
                            confirmed = temporalActive.isSuspicious
                        )
                        val diagnosticInputs = com.alexisgordr.icdetector.core.DiagnosticEngine.Inputs(
                            neighborCount = neighbors.size,
                            // 3.0 — El Wi-Fi capturado para ESTE análisis: volver a consultarlo al
                            // publicar podía explicar el resultado con otro estado (p. ej. "faltan
                            // vecinas" para una regla que se abstuvo por Wi-Fi).
                            wifiActive = wifiActive,
                            isolationProgress = isolatedCellConfidence.progress to isolatedCellConfidence.required,
                            locationAvailable = currentLocation != null,
                            historyWithLocation = preloadedHistory.count { it.lat != null && it.lon != null },
                            previousBandAvailable = (prevBand != null || prevNrArfcn != null) && prevRegisteredDbm != null,
                            signalBaseline = signalBaseline,
                            rfFingerprint = rfFingerprint,
                            rfStability = rfStability,
                            rfStabilityReadFailed = rfStabilityReadFailing && rfStability == null,
                            reputation = reputation,
                            transitionCoherence = transitionCoherence,
                            trustGateMet = cachedTrustGate.takeIf { activeRaw != null && activeRaw.cellId != "N/A" }
                        )
                        val confirmedActive = temporalActive.copy(
                            heuristicDiagnostics = com.alexisgordr.icdetector.core.DiagnosticEngine.explain(
                                temporalActive, diagnosticInputs
                            ),
                            baselineMaturity = com.alexisgordr.icdetector.core.DiagnosticEngine.maturity(diagnosticInputs)
                        )
                        // 3.0 — Orden de la publicación: primero lo que hace el ciclo visible y válido
                        // (contexto del próximo ciclo, pantalla y salud) y
                        // después sus efectos (incidentes, caja negra, historial y alertas). Antes las
                        // alertas y escrituras iban delante: un fallo en la cola dejaba una alarma
                        // enviada con la pantalla vieja y «análisis no disponible».

                        // 2. Contexto de banda para la heurística 14 (tras el análisis, de modo que el
                        // PRÓXIMO ciclo compare contra estos valores).
                        // 3.1.0 (#37) — Una lectura sin canal de la MISMA celda ya no borra su banda.
                        val nextBand = com.alexisgordr.icdetector.core.BandContext.next(bandContext, active)
                        bandContext = nextBand
                        prevBand = nextBand.lteBand
                        prevBandSite = nextBand.site
                        prevNrArfcn = nextBand.nrArfcn
                        prevRegisteredDbm = active.dbm
                        if (active.dbm != Int.MAX_VALUE && active.dbm != -999) {
                            recentRegisteredDbmTrend.add(active.dbm)
                            while (recentRegisteredDbmTrend.size > 6) recentRegisteredDbmTrend.removeAt(0)
                        }

                        // 3. El resultado visible y su fecha de validez se publican juntos, sin nada
                        // entre medias que pueda fallar: la pantalla nunca enseña un resultado nuevo
                        // con la salud del anterior.
                        _cellFlow.value = sorted.mapIndexed { index, cell ->
                            if (index == 0) confirmedActive else cell
                        }
                        val recovered = onAnalysisPublished(cycleTicket)
                        // La notificación es presentación: si falla, no es un fallo del análisis.
                        // Al recuperarse se fuerza, para que el aviso no quede por el límite de 2 s.
                        publishNotification { updateNotification(confirmedActive, force = recovered) }

                        // 4. Efectos del ciclo ya publicado. Si fallan, el resultado en pantalla sigue
                        // siendo válido: no se marca «no disponible»; una línea [SYS] por racha dice
                        // que puede faltar esa parte (ver publicationTail).
                        publicationTail {
                            // Caja negra: se actualiza fuera del hilo de UI. Registra desde 1/3 y
                            // cierra el episodio al recuperarse o al cambiar de identidad.
                            val previousIncidentIdentity = lastIncidentIdentity
                            lastIncidentIdentity = if (confirmedActive.temporalProgress.active) cacheKey else null
                            writeGate.launch(scope, forensicDispatcher) {
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
                            writeGate.launch(scope, forensicDispatcher) {
                                forensicRecorder.observe(
                                    active = confirmedActive,
                                    neighbors = nonServingContext,
                                    location = currentLocation,
                                    logs = liveLogs.value,
                                    trustContradictionTransition = trustContradictionTransition
                                )
                            }

                            // Alertas y registro (fila de historial) con el estado de esta entrega.
                            checkAlerts(
                                confirmedActive,
                                confirmed = true,
                                context = observationContext,
                                episodeKey = confirmationKey
                            )
                        }
                    } else {
                        stableSiteIntensiveActive = false
                        _cellFlow.value = emptyList()
                        val recovered = onAnalysisPublished(cycleTicket)
                        publishNotification {
                            updateNotificationText(getString(R.string.searching_network), force = recovered, kind = com.alexisgordr.icdetector.core.MonitoringNotificationText.TextKind.NO_SIGNAL)
                        }
                    }
                    true
                }

                // v2.10.10 — Escritura de Stable-Site fuera del hilo principal. Se hacía dentro del
                // bloque Main: hasta cuatro transacciones SQLite por ciclo que, con la base ocupada
                // (poda, borrado), congelaban la interfaz. Sigue dentro del mutex de ciclos.
                // 3.0 — Solo un ciclo limpio, sin racha de confirmación ni episodio abierto, enseña
                // a Stable-Site (ver StableSiteContextPolicy).
                // 3.0 (corte de campaña) — Y solo un ciclo PUBLICADO: antes se escribía antes de
                // mayPublish, así que un análisis descartado (la servidora cambió mientras tanto) no
                // se mostraba ni alertaba, pero enseñaba al sitio. Ahora va detrás de la publicación,
                // y la confirmación y el episodio que lee son los de ESTE ciclo, ya actualizados.
                if (published) analyzedList.firstOrNull()?.takeIf { it.isRegistered }?.takeIf { servingForSite ->
                    com.alexisgordr.icdetector.core.StableSiteContextPolicy.mayLearn(
                        servingForSite,
                        inConfirmation = temporalConfidence.inConfirmation,
                        episodeOpen = threatEpisodeTracker.hasOpenEpisode()
                    )
                }?.let { servingForSite ->
                    siteKeys.forEach { key ->
                        dbHelper.recordStableSiteContext(
                            // One aggregate contribution per identity and minute. The PK also
                            // makes retries/restarts idempotent without retaining raw snapshots.
                            eventKey = "${System.currentTimeMillis() / 60_000L}:$key",
                            siteKey = key,
                            serving = activeRaw ?: servingForSite,
                            neighbours = neighbors,
                            motion = motionEvidence
                        )
                    }
                }
                }
                }
            }
        } catch (e: Exception) {
            // 3.0 — Antes solo printStackTrace: la entrega se perdía sin línea de terminal ni cambio
            // de salud, y la pantalla seguía enseñando el último resultado como actual. Si el fallo
            // llega después de deliver(), esa generación nueva ya invalidó al ciclo anterior en curso
            // y ninguno publicará. Mismo contrato que el análisis: el tipo, nunca el mensaje.
            onDeliveryFailed(deliveredTicket, e.javaClass.simpleName)
        }
    }

    /**
     * 3.0 — Fallo de una entrega en el hilo principal, antes de lanzar su ciclo. Con ticket, es el
     * fallo de ese ciclo (onAnalysisFailed); sin él, la salud se marca igual, sin generación.
     */
    private fun onDeliveryFailed(ticket: com.alexisgordr.icdetector.core.CollectionGeneration.Ticket?, errorType: String) {
        if (ticket != null) {
            onAnalysisFailed(ticket, errorType)
            return
        }
        val change = analysisHealthTracker.onDeliveryFailed()
        _analysisHealth.value = analysisHealthTracker.state
        if (change == com.alexisgordr.icdetector.core.AnalysisHealth.Change.BECAME_UNAVAILABLE) {
            appendLog("[SYS]", "${com.alexisgordr.icdetector.core.AnalysisHealth.FAILURE_LOG} [$errorType]")
            publishNotification { updateNotificationText(getString(R.string.analysis_unavailable), force = true) }
        }
    }

    /**
     * 3.0 — Fallo de un ciclo de análisis, en el hilo principal. Solo cambia la salud si el ciclo
     * aún podía publicar (ver AnalysisHealth); un ciclo ya descartado no afecta a la generación nueva.
     * No publica nada, no alerta y no reintenta.
     *
     * Límites (efectos parciales): lo que el ciclo hizo antes de fallar se queda, una sola vez. En
     * segundo plano: trayecto de movilidad, episodio y decisión de Stable-Site, contexto de sitio por
     * minuto, cachés de lecturas, racha de aislamiento de H1 y la transición de H16 (cell_transitions).
     * Al publicar, si el fallo llega antes de la pantalla: episodio y confirmación temporal pueden
     * haber avanzado, pero aún no se ha escrito la fila del historial, el incidente ni la muestra
     * forense, ni se ha lanzado ninguna alerta: todo eso va después de publicar (publicationTail).
     * No es una transacción global; no se deshace ni se repite nada. La siguiente entrega es una
     * observación nueva, no un reintento.
     */
    /**
     * 3.0 — Ciclo de análisis (IO, bajo cellProcessingMutex): si este ciclo abrió un episodio,
     * anota si empezó con el sitio maduro (ver StableSiteNotices.pendingAfterObservation). commit():
     * el pendiente debe estar en disco antes de que el ciclo pueda avisar.
     */
    private fun recordStableSiteEpisodeStart(
        observation: com.alexisgordr.icdetector.core.StableSiteEpisodeObservation,
        siteAtStart: com.alexisgordr.icdetector.core.StableSiteDecision
    ) {
        val notices = com.alexisgordr.icdetector.core.StableSiteNotices
        val prefs = getSharedPreferences(com.alexisgordr.icdetector.backup.BackupSettings.PREFS_FILE, MODE_PRIVATE)
        val pending = prefs.getString(notices.PREF_PENDING_EPISODE, null)
        val next = notices.pendingAfterObservation(pending, observation, siteAtStart)
        if (next == pending) return
        prefs.edit().apply { if (next == null) remove(notices.PREF_PENDING_EPISODE) else putString(notices.PREF_PENDING_EPISODE, next) }.commit()
    }

    /**
     * 3.0 — Aviso silencioso de Stable-Site (ver StableSiteNotices). Se llama en el hilo principal,
     * dentro del bloque de publicación y después de collectionGeneration.mayPublish: un análisis
     * descartado nunca avisa y su pendiente se conserva para el siguiente ciclo válido.
     * Solo informa: no toca la puntuación, la confianza temporal ni las alarmas. El episodio ya
     * está guardado; si la notificación falla o Android la bloquea, el análisis sigue igual.
     */
    private fun maybeNotifyStableSite(decision: com.alexisgordr.icdetector.core.StableSiteDecision) {
        val notices = com.alexisgordr.icdetector.core.StableSiteNotices
        val prefs = getSharedPreferences(com.alexisgordr.icdetector.backup.BackupSettings.PREFS_FILE, MODE_PRIVATE)
        val enabled = prefs.getBoolean(notices.PREF_ENABLED, notices.DEFAULT_ENABLED)
        val pending = prefs.getString(notices.PREF_PENDING_EPISODE, null)
        val action = notices.decide(decision, enabled, pending)
        if (action == com.alexisgordr.icdetector.core.StableSiteNotices.Action.NONE) return
        val episodeId = decision.episodeId ?: return
        // Se consume ANTES de intentar publicar: como mucho un aviso por episodio. 3.1.0 (#47) —
        // apply(), no commit(): esto corre en el hilo principal y commit() escribía en disco aquí.
        // apply() cambia la memoria al momento (ninguna lectura de este proceso vuelve a ver el
        // pendiente) y escribe en disco en segundo plano. Si el proceso muriera justo antes de esa
        // escritura, el siguiente arranque podría repetir el aviso; usa el mismo id de notificación
        // por episodio (StableSiteNotices.notificationId), así que reemplaza al anterior, no suma otro.
        prefs.edit().remove(notices.PREF_PENDING_EPISODE).apply()
        if (action == com.alexisgordr.icdetector.core.StableSiteNotices.Action.CONSUME_SILENTLY) {
            appendLog("[SITE]", notices.LOG_DISABLED)
            return
        }
        val posted = try {
            notificationController.showStableSiteNotice(episodeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("MiniIC", "Stable-Site notice failed (${e.javaClass.simpleName})")
            null
        }
        appendLog("[SITE]", when (posted) {
            true -> notices.LOG_POSTED
            false -> notices.LOG_BLOCKED
            null -> notices.LOG_FAILED
        })
    }

    private fun onAnalysisFailed(ticket: com.alexisgordr.icdetector.core.CollectionGeneration.Ticket, errorType: String) {
        val change = analysisHealthTracker.onCycleFailed(ticket.generation, collectionGeneration.mayPublish(ticket))
        _analysisHealth.value = analysisHealthTracker.state
        if (change == com.alexisgordr.icdetector.core.AnalysisHealth.Change.BECAME_UNAVAILABLE) {
            appendLog("[SYS]", "${com.alexisgordr.icdetector.core.AnalysisHealth.FAILURE_LOG} [$errorType]")
            publishNotification { updateNotificationText(getString(R.string.analysis_unavailable), force = true) }
        }
    }

    /**
     * 3.0 — El resultado de un ciclo válido acaba de publicarse en pantalla (hilo principal): su fecha
     * de validez pasa a ser ahora. Devuelve true si cierra una racha de fallos, para forzar el
     * repintado de la notificación.
     */
    private fun onAnalysisPublished(ticket: com.alexisgordr.icdetector.core.CollectionGeneration.Ticket): Boolean {
        val change = analysisHealthTracker.onCyclePublished(ticket.generation, System.currentTimeMillis())
        _analysisHealth.value = analysisHealthTracker.state
        if (change == com.alexisgordr.icdetector.core.AnalysisHealth.Change.RECOVERED) {
            appendLog("[SYS]", com.alexisgordr.icdetector.core.AnalysisHealth.RECOVERY_LOG)
            return true
        }
        return false
    }

    private var publicationTailFailing = false

    /**
     * 3.0 — Efectos de un ciclo YA publicado (incidentes, caja negra, historial, alertas). Un fallo
     * aquí no es un fallo del análisis: el resultado en pantalla es válido y no se marca «no
     * disponible». Una línea [SYS] al empezar una racha, con el tipo de error, nunca el mensaje; lo
     * que llegó a hacerse antes del fallo se queda, sin repetirlo.
     */
    private inline fun publicationTail(block: () -> Unit) {
        try {
            block()
            publicationTailFailing = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!publicationTailFailing) {
                publicationTailFailing = true
                appendLog("[SYS]", "${com.alexisgordr.icdetector.core.AnalysisHealth.TAIL_FAILURE_LOG} [${e.javaClass.simpleName}]")
            }
        }
    }

    /**
     * 3.0 — Actualizar la notificación sin que un fallo suyo pase por fallo del análisis: el resultado
     * ya está publicado y es válido. Solo se anota en el registro del sistema, con el tipo de error.
     */
    private inline fun publishNotification(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("MiniIC", "No se pudo actualizar la notificación de monitorización (${e.javaClass.simpleName}).")
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
            // 3.0 (#33) — Una clave por unidad. Las claves globales de versiones anteriores
            // (ta_seen_real_value, ta_zero_only_cells) no dicen de qué tecnología vinieron y NO se
            // leen: cada unidad empieza vacía y se rederiva en unos minutos de uso.
            // 3.1.0 (#43) — Ventana por unidad (ta_window_<unidad>). Sin ella, se migra la evidencia
            // de 3.0: las celdas a cero sí, el latch «reportó alguna vez» no (un valor espurio no
            // fuerza REAL). Las claves de 3.0 se borran.
            val legacy = p.edit()
            var migrated = false
            com.alexisgordr.icdetector.core.TimingAdvanceSanity.EVIDENCE_UNITS.forEach { unit ->
                val window = p.getString(KEY_TA_WINDOW_PREFIX + unit.name, null)
                if (window != null) {
                    taSanity.restore(unit, window)
                } else {
                    val cells = p.getStringSet(KEY_TA_ZERO_CELLS_PREFIX + unit.name, emptySet()).orEmpty()
                    if (cells.isNotEmpty()) taSanity.restoreLegacy(unit, cells)
                }
                if (p.contains(KEY_TA_SEEN_REAL_PREFIX + unit.name) || p.contains(KEY_TA_ZERO_CELLS_PREFIX + unit.name)) {
                    legacy.remove(KEY_TA_SEEN_REAL_PREFIX + unit.name).remove(KEY_TA_ZERO_CELLS_PREFIX + unit.name)
                    migrated = true
                }
            }
            if (migrated) {
                legacy.apply()
                persistTaSanityEvidence()
            }
            if (taSanity.anyStub) {
                appendLog(
                    "[TA]",
                    "Diagnóstico de TA recuperado del arranque anterior: el módem no rellena el campo " +
                        "(${taSanity.zeroOnlyCellCount} celdas distintas con 0). No se deduce distancia."
                )
            }
        } catch (_: Exception) {}
    }

    /** Guarda la ventana de TA. Se llama cuando [TimingAdvanceSanity.observe] lo pide (veredicto nuevo o cada 30). */
    private fun persistTaSanityEvidence() {
        try {
            val editor = getSharedPreferences("miniic_prefs", MODE_PRIVATE).edit()
            com.alexisgordr.icdetector.core.TimingAdvanceSanity.EVIDENCE_UNITS.forEach { unit ->
                editor.putString(KEY_TA_WINDOW_PREFIX + unit.name, taSanity.summary(unit))
            }
            editor.apply()
        } catch (_: Exception) {}
    }

    /** Same position checks as analysis, plus GPS availability; never starts acquisition. */
    fun hasUsableGpsFix(): Boolean =
        ::locationController.isInitialized && locationController.hasUsableGpsFix()

    private fun getCurrentLocation(): Location? {
        return locationController.currentLocation()
    }

    /** 3.0 (A03) — Posición de una observación, sin depender de Android a partir de aquí. */
    private fun Location.toObservedFix() = com.alexisgordr.icdetector.core.ObservedFix(
        latitude = latitude,
        longitude = longitude,
        accuracyM = if (hasAccuracy()) accuracy else null
    )

    private fun observeMotionFix(location: Location) {
        // 3.0 — Orden, frescura y duración con el reloj monotónico del fix (elapsedRealtimeNanos),
        // comparado con SystemClock.elapsedRealtime. location.time (hora del GPS/civil) ya no entra.
        stableSiteMotion.observe(
            com.alexisgordr.icdetector.core.MotionFix(
                location.latitude,
                location.longitude,
                location.elapsedRealtimeNanos / 1_000_000L,
                location.accuracy,
                if (location.hasSpeed()) location.speed else null
            ),
            SystemClock.elapsedRealtime()
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


    private fun onGpsAvailable() {
        val now = System.currentTimeMillis()
        if (now - lastGpsTriggerTime < 60000L) return

        // Solo comprobamos que HAY alguna celda activa; la celda concreta se recaptura DESPUÉS del
        // delay (puede haber handover en esos 3 s).
        if (_cellFlow.value.none { it.isRegistered }) return

        lastGpsTriggerTime = now
        scope.launch {
            delay(3000L.milliseconds)

            val loc = getCurrentLocation()
            if (loc == null) {
                appendLog("[GPS]", "GPS inestable tras espera, reintentando más tarde")
                lastGpsTriggerTime = 0L
                return@launch
            }

            // FIX race condition: recapturar la celda activa AHORA, no la de hace 3 s. Si hubo
            // handover durante la espera, las coordenadas deben ir a nombre de
            // la celda REALMENTE activa, no de la anterior — si no, inyectaríamos un GPS fresco en
            // los registros de la antena equivocada (ruido para H11/H13).
            val currentCell = _cellFlow.value.firstOrNull { it.isRegistered } ?: return@launch

            // Rellenar coordenadas SOLO con un fix casi de tiempo real (<15 s, la cadencia del
            // stream). Antes de un cambio de celda / rebote a la misma celda queremos la posición
            // real del momento: ante una clonación de Cell ID, la H11/H13 comparan contra estas
            // coordenadas, así que una posición vieja podría enmascarar la incoherencia. Si no hay
            // fix tan fresco, se omite y lo cubre el fix forzado (fresco por definición).
            // 3.1.0 (#36) — La edad sale del reloj monotónico, no de la hora civil: con la hora del
            // teléfono adelantada se descartaba un fix recién llegado, y con la hora atrasada se
            // aceptaba uno viejo (edad negativa). `loc.time` se sigue usando para casar el fix con
            // la hora de las filas (fixTimeMs), que se guardan en hora civil.
            val coordAgeMs = com.alexisgordr.icdetector.core.GpsFixAge.ageMs(SystemClock.elapsedRealtime(), loc.elapsedRealtimeNanos)
            if (com.alexisgordr.icdetector.core.GpsFixAge.usableForBackfill(coordAgeMs)) {
                locationController.resolvePendingCoordinates()
                writeGate.launch(scope, Dispatchers.IO) {
                    val updated = dbHelper.updateNullCoordinates(
                        currentCell.cellId,
                        currentCell.mnc,
                        currentCell.tac,
                        currentCell.mcc,
                        currentCell.radioTech,
                        loc.latitude,
                        loc.longitude,
                        fixTimeMs = loc.time,
                        accuracyM = loc.takeIf { it.hasAccuracy() }?.accuracy
                    )
                    if (updated > 0) {
                        appendLog("[GPS]", "Coordenadas frescas rellenadas en $updated registro(s)")
                    }
                }
            } else {
                appendLog("[GPS]", "Fix disponible pero no lo bastante fresco (${coordAgeMs?.let { "${it / 1000}s" } ?: "edad desconocida"}) — se omite el relleno; espera al fix preciso")
            }
        }
    }

    private fun checkAlerts(
        cell: CellData,
        confirmed: Boolean,
        context: com.alexisgordr.icdetector.core.ObservationContext,
        episodeKey: String = cell.identityKey
    ) {
        val cid = cell.cellId
        val dbm = cell.dbm
        val net = cell.networkType

        // 3.0 (#19) — Handover = cambia la identidad completa (MCC, MNC, TAC, CID o tecnología),
        // no solo el CID. Un campo desconocido en un lado no cuenta como cambio.
        // 3.0 — Un CID ilegible («N/A») no abre handover ni reinicia el episodio de alarma; la
        // identidad anterior solo completa sus campos desconocidos (ServingIdentityChange.step).
        val servingIdentity = com.alexisgordr.icdetector.core.ServingIdentityChange.of(cell)
        val identityStep = com.alexisgordr.icdetector.core.ServingIdentityChange.step(prevServingIdentity, servingIdentity)
        if (identityStep.handover) {
            telemetryHistory.onHandover()
            // 3.0 — Cierra el episodio de alarma, salvo el de un ping-pong (ver AlarmEpisodeGate).
            securityAlerts.onHandover()
            appendLog("[RADIO]", "Handover celular completado -> Nueva celda CID: $cid ($net)")
            // Esta celda queda pendiente de coordenadas frescas hasta que un fix las rellene.
            locationController.markCoordinatesPending()
            // Inteligente: una antena nueva abre o alarga una ventana de GPS de 60 s (con mala
            // recepción, un intento corto); una antena reciente no encadena ventanas.
            locationController.onHandover(servingIdentity.key, prevServingIdentity?.key)
            auditController.generate(cell)

            // 3.0 (corte de campaña) — El cambio ya está en el historial de H10 desde que llegó la lectura
            // (cellChanges.observe, antes del análisis).

            // El informe de heurísticas es la observación cruda del ciclo. No debe producir un
            // tono aquí: TemporalConfidence necesita tres ciclos antes de convertirla en alarma.
            // Si el ping-pong contribuye a una amenaza confirmada, lo registra la rama general
            // cell.isSuspicious de abajo, junto al resto de las heurísticas confirmadas.
            if (!cell.heuristicReport.pingPongPassed) {
                // Evidencia para la campaña, sin alerta: el informe sigue siendo crudo y todavía
                // no ha superado los tres ciclos de TemporalConfidence.
                appendLog("[RADIO]", "Ping-Pong observado (sin confirmar; sin tono).")
            } else if (cellChanges.size >= 3) {
                // v2.10.7 — Antes afirmaba que la alerta se descartaba, pero esta rama no decide nada: H10 se
                // evalúa en ThreatAnalyzer y puede confirmarse en los ciclos siguientes. Texto
                // neutro; la lógica no cambia.
                val speedMps = getCurrentLocation()?.takeIf { it.hasSpeed() }?.speed
                if (speedMps == null) {
                    appendLog("[RADIO]", "Cambios rápidos de celda observados sin velocidad GPS (H10 en N/A).")
                } else {
                    val speedKmh = speedMps * 3.6f
                    appendLog("[RADIO]", "Cambios rápidos de celda observados a ${String.format(Locale.getDefault(), "%.1f", speedKmh)} km/h.")
                }
            }

            observationPersistence.recordHandover(cell, context)

            prevServingIdentity = identityStep.next
        } else {
            prevServingIdentity = identityStep.next
        }
        if (!identityStep.handover && confirmed) {
            // Misma celda que en el ciclo anterior: muestreo periódico (ver más abajo). Solo en la
            // ruta del bucle principal (confirmed = true), nunca en la de re-análisis tras la API,
            // para no duplicar filas por el mismo instante.
            observationPersistence.recordPeriodicIfDue(cell, isScreenOn, context)
        }

        telemetryHistory.record(cell)

        securityAlerts.evaluate(cell, confirmed, context, episodeKey)
    }

    private fun updateNotification(cell: CellData, force: Boolean = false) {
        // 3.1.0 — Sin verificación externa: la notificación ya no lleva estado de base pública.
        val content = "${cell.networkType} | CID: ${cell.cellId} | ${cell.dbm} dBm"
        updateNotificationText(content, force)
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
                updateNotificationText(getString(R.string.write_failure_notification), force = true)
            }
            CollectionHealthController.Change.RECOVERED -> {
                appendLog("[SYS]", "Escritura en base de datos restablecida.")
            }
            CollectionHealthController.Change.NONE -> Unit
        }
    }

    /** 3.0 — Prioridad y límite de repintado de la notificación (ver MonitoringNotificationText). */
    private val monitoringNotificationText = com.alexisgordr.icdetector.core.MonitoringNotificationText()

    @Synchronized
    private fun updateNotificationText(
        text: String,
        force: Boolean = false,
        kind: com.alexisgordr.icdetector.core.MonitoringNotificationText.TextKind =
            com.alexisgordr.icdetector.core.MonitoringNotificationText.TextKind.OTHER
    ) {
        // Throttle: no repintar la notificación más de una vez cada 2 s. El callback de telefonía
        // puede dispararse muchas veces por segundo en zonas de transición; repintar cada vez satura
        // el System Server (IPC + batería) y Android acaba silenciando con "rate limit exceeded".
        // Los datos (BD, logs y el TONO de alarma) van en tiempo real por su cuenta: esto solo
        // limita el DIBUJO visual de la notificación, no la detección ni el registro.
        // 3.0 — El texto se resuelve con el estado de salud en CADA actualización: mientras el
        // análisis no esté disponible, ningún otro texto (salvo falta de señal) lo sustituye. Se lee
        // del StateFlow, seguro desde cualquier hilo.
        val now = System.currentTimeMillis()
        val resolved = monitoringNotificationText.next(
            requested = text, kind = kind, force = force,
            analysisUnavailable = _analysisHealth.value.unavailable,
            unavailableText = getString(R.string.analysis_unavailable),
            nowMs = now
        ) ?: return
        // Un fallo de escritura tapa cualquier otro texto: es la única condición en la que la app
        // parece funcionar y no está recogiendo nada.
        val visible = collectionHealthController.visibleNotification(resolved, now)
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

    companion object {
        /** 3.0 — Ver IsolatedCellConfidence.stuckOnRepeatedToken. */
        const val ISOLATED_TOKEN_STUCK_LOG =
            "H1 en N/A: el módem repite la misma marca de tiempo en sus lecturas, así que no se puede confirmar una celda aislada."
        const val CHANNEL_ID = ServiceNotificationController.CHANNEL_ID
        const val ALERT_CHANNEL_ID = ServiceNotificationController.ALERT_CHANNEL_ID
        const val NOTIFICATION_ID = ServiceNotificationController.NOTIFICATION_ID
        const val ACTION_STOP = "com.alexisgordr.icdetector.STOP"
        // v2.5 — Evidencia del diagnóstico de Timing Advance, superviviente a reinicios del
        // servicio. Ver TimingAdvanceSanity: se guarda la evidencia, nunca el veredicto.
        // 3.0 (#33) — Por unidad: ta_seen_real_LTE_INDEX, ta_zero_cells_GSM_INDEX, … Desde 3.1.0
        // (#43) solo se leen para migrarlas y se borran.
        private const val KEY_TA_SEEN_REAL_PREFIX = "ta_seen_real_"
        private const val KEY_TA_ZERO_CELLS_PREFIX = "ta_zero_cells_"
        /** 3.1.0 (#43) — Resumen de la ventana por unidad (TimingAdvanceSanity.summary). */
        private const val KEY_TA_WINDOW_PREFIX = "ta_window_"
        // Ciclos consecutivos de sospecha necesarios para confirmar una alarma.
        private const val CONFIRMATION_CYCLES = 3
        private const val INTENSIVE_MONITORING_TAIL_MS = 60_000L
        private const val MAX_INTENSIVE_MONITORING_MS = 5L * 60L * 1000L
        private const val UI_VISIBLE_SCAN_INTERVAL_MS = 1_000L
        private const val SCREEN_ON_SCAN_INTERVAL_MS = 3_000L
        private const val SCREEN_OFF_SCAN_INTERVAL_MS = 10_000L

    }
}
