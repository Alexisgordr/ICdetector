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
import com.alexisgordr.icdetector.core.CollectionHealth
import com.alexisgordr.icdetector.core.ThreatAnalyzer
import com.alexisgordr.icdetector.core.VerificationDecision
import com.alexisgordr.icdetector.models.*
import com.alexisgordr.icdetector.network.OpenCellIdClient
import com.alexisgordr.icdetector.network.WigleClient
import com.alexisgordr.icdetector.network.VerificationFailure
import com.alexisgordr.icdetector.storage.CellDbHelper
import com.alexisgordr.icdetector.telephony.CellParser
import com.alexisgordr.icdetector.forensics.ForensicRecorder
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

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var dbHelper: CellDbHelper
    private lateinit var forensicRecorder: ForensicRecorder
    // v2.3.3 — Vigilancia de la recolección: escrituras fallidas e interrupciones.
    private val collectionHealth = CollectionHealth()
    // Ventana durante la cual la notificación sigue anunciando que hubo una interrupción, para
    // que un reinicio nocturno no se quede solo en una línea del terminal que nadie lee.
    private var interruptionNoticeUntil = 0L
    private var interruptionNoticeText = ""
    private lateinit var locationManager: LocationManager
    private var toneGenerator: ToneGenerator? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var displayInfoCallback: TelephonyCallback? = null
    private var securityCallback: TelephonyCallback? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var lastDisplayInfo: TelephonyDisplayInfo? = null
    private var isScreenOn = true
    private var locationUpdatesActive = false
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

    // "Cola" de coordenadas frescas pendientes: cuando una celda se registra sin un fix
    // fresco, queda marcada como pendiente. Cualquier fix fresco (stream, forzado o el
    // despertar periódico en reposo) la rellena; en cuanto se rellena, se limpia. Así nunca
    // estampamos coordenadas viejas y no nos rendimos hasta tener una real del momento.
    private var awaitingFreshCoords = false
    private var pendingCoordsSince = 0L
    private var lastReposeCoordFixAttempt = 0L

    // v2.1: la confirmación temporal vive en core/TemporalConfidence para poder testearla de
    // extremo a extremo (ver ScenarioTest). El servicio solo la usa.
    private val temporalConfidence = com.alexisgordr.icdetector.core.TemporalConfidence(CONFIRMATION_CYCLES)
    // Todas las lecturas comparten cachés, transición y confirmación temporal. Serializarlas evita
    // que dos callbacks publiquen la firma de una celda con el historial calculado para otra.
    private val cellProcessingMutex = Mutex()
    private val enqueuedCellProcessing = AtomicLong(0L)
    private var lastIncidentIdentity: String? = null
    // v2.1 — ¿el TA de este módem es una medida o un campo sin rellenar? Ver TimingAdvanceSanity.
    private val taSanity = com.alexisgordr.icdetector.core.TimingAdvanceSanity()
    private var hasLoggedMissingCredentials = false

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
    // v2.1 — Posición de la antena (api_lat/api_lon) para mostrar la distancia. Solo display.
    private val apiLocationCache = ConcurrentHashMap<String, Pair<Double, Double>>()
    private var cachedRfSignature = ""
    private var cachedRfTimestamp = 0L
    private var cacheTimestamp = 0L
    private val CELL_CACHE_TTL = 60_000L

    // Latency anomaly detection
    private val latencyHistory = ConcurrentHashMap<String, MutableList<Long>>()
    private var lastLatencyCheckTime = 0L
    private var latencyAnomalyStreak = 0
    private val LATENCY_CHECK_INTERVAL = 30000L   // cada 30 segundos
    private val LATENCY_HISTORY_SIZE = 10          // últimas 10 mediciones
    private val LATENCY_ANOMALY_MULTIPLIER = 2.5   // 2.5x el promedio = anómalo
    private val LATENCY_CONFIRMATION_CYCLES = 3    // 3 ciclos para confirmar
    private val LATENCY_ENDPOINTS = listOf(
        "https://www.google.com/generate_204",    // ✅ enorme, sin límites
        "https://one.one.one.one/",               // ✅ Cloudflare, sin límites
        "https://dns.quad9.net/"                  // ✅ Quad9, DNS público grande
    )

    var openCellIdKey: String = ""
    var wigleApiName: String = ""
    var wigleApiToken: String = ""
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
    // El límite de WiGLE es global para la cuenta, no para una celda. Persistirlo impide que un
    // reinicio del servicio vuelva a consumir peticiones cuando WiGLE ya ha dicho que la cuota
    // diaria está agotada.
    private var wigleRateLimitedUntil = 0L
    private var hasLoggedWigleCooldown = false
    // Marca temporal del último NOT_FOUND por celda. A diferencia de ERROR (fallo de red
    // transitorio, reintento a 60s), NOT_FOUND es una respuesta afirmativa de la API ("no está
    // en la base"). Reintentamos en vivo cada 1h por si una torre legítima recién desplegada se
    // incorpora a WiGLE/OpenCellID y debe reclasificarse a VERIFIED. Una vez VERIFIED, el guard
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
    private var lastAlarmLoggedCellId: String? = null
    // Identidad y estado de verificación de la celda cuya auditoría está escrita ahora mismo en el
    // terminal. El terminal es un buffer corrido de 40 líneas: mezcla celdas y momentos, y la
    // auditoría se escribe en el handover, cuando la celda todavía está PENDING. La respuesta de
    // las bases públicas llega después y cambia la puntuación, así que sin esto el último veredicto
    // escrito podía decir "100% — SEGURO" mientras la cabecera ya mostraba la cifra definitiva.
    private var auditedCellKey: String? = null
    private var auditedVerified: VerificationStatus? = null
    private var prevNetType: String? = null
    private var prevDbm: Int? = null
    // Estado para heurística 14 (band downgrade intra-LTE). Guardan la última celda
    // REGISTRADA analizada; el buffer de tendencia sobrevive al handover para detectar si
    // la señal venía degradándose progresivamente (excepción del garaje/sótano).
    private var prevBand: Int? = null
    private var prevRegisteredDbm: Int? = null
    private val recentRegisteredDbmTrend = CopyOnWriteArrayList<Int>()
    // Watchdog del callback de telefonía: marca de tiempo del ÚLTIMO evento recibido. Antes se usaba
    // un booleano (isCallbackWorking) que se quedaba clavado en true tras el primer evento, así que el
    // watchdog nunca volvía a dispararse: si el HAL del módem colgaba el callback (típico tras Doze o
    // en sesiones de varios días), se dejaba de registrar datos EN SILENCIO. Con la marca de tiempo
    // detectamos esa muerte: si pasan más de callbackTimeoutMs sin ningún evento, re-registramos.
    private var lastCallbackTime = 0L
    private val callbackTimeoutMs = 30_000L
    private var connectionRetryCount = 0
    private val cellChangeHistory = CopyOnWriteArrayList<Pair<String, Long>>()

    private data class ServingSnapshot(
        val cell: CellData,
        val location: Location?,
        val elapsedRealtimeMs: Long,
        val neighborIdentities: Set<String>
    )
    private val transitionLock = Any()
    private var lastServingSnapshot: ServingSnapshot? = null
    private var activeTransitionResult = TransitionCoherenceResult()
    private var activeTransitionIdentity: String? = null
    private var activeTransitionExpiresAt = 0L
    private val TRANSITION_RESULT_TTL_MS = 20_000L

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
        forensicRecorder = ForensicRecorder(dbHelper)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        // Poda de histórico antiguo al arrancar (en segundo plano, no bloquea onCreate).
        // Evita que la tabla crezca sin límite registrando 24/7. v2.3.3: conserva
        // DEFAULT_RETENTION_DAYS (120) — antes 60, que se quedaban CORTOS para una campaña de 90
        // días y borraban el primer mes justo al abrir la app para exportar.
        scope.launch(Dispatchers.IO) {
            try {
                // Si el proceso anterior terminó durante un episodio, no puede quedar marcado
                // eternamente como "en curso". Se conserva y se cierra como interrumpido.
                dbHelper.closeOpenIncidents(activeIdentity = null, interrupted = true)
                dbHelper.interruptOpenForensicCases()
                val deleted = dbHelper.pruneOldRecords()
                if (deleted > 0) {
                    appendLog("[SYS]", "Poda de histórico: $deleted registros de más de ${CellDbHelper.DEFAULT_RETENTION_DAYS} días eliminados.")
                }
                val trimmed = dbHelper.enforceForensicSampleCap()
                if (trimmed > 0) {
                    appendLog("[SYS]", "Muestras forenses recortadas al tope de ${CellDbHelper.MAX_FORENSIC_SAMPLES}: $trimmed eliminadas.")
                }
            } catch (_: Exception) {}
        }
        
        val prefs = getSharedPreferences("miniic_prefs", MODE_PRIVATE)
        openCellIdKey = prefs.getString("opencellid_key", "") ?: ""
        wigleApiName = prefs.getString("wigle_api_name", "") ?: ""
        wigleApiToken = prefs.getString("wigle_api_token", "") ?: ""
        wigleRateLimitedUntil = prefs.getLong(KEY_WIGLE_RATE_LIMITED_UNTIL, 0L)
        if (wigleRateLimitedUntil <= System.currentTimeMillis()) {
            wigleRateLimitedUntil = 0L
            prefs.edit().remove(KEY_WIGLE_RATE_LIMITED_UNTIL).apply()
        }
        // v2.3.3 — Continuidad de la recolección. Si el proceso murió (reinicio del móvil, OTA,
        // batería agotada) nadie lo anunciaba: la notificación se iba con el proceso y la
        // recolección quedaba parada sin dejar rastro. Ahora el hueco se calcula al arrancar y se
        // deja escrito, para que una interrupción sea un hecho observable y no una sorpresa en
        // diciembre al ver un agujero en el CSV.
        collectionHealth.restore(prefs.getLong(KEY_LAST_SUCCESSFUL_WRITE, 0L))
        collectionHealth.interruptionBefore(System.currentTimeMillis())?.let { gapMs ->
            val horas = gapMs / 3_600_000L
            val minutos = (gapMs % 3_600_000L) / 60_000L
            interruptionNoticeText = "⚠ Recolección interrumpida ${horas}h ${minutos}min"
            interruptionNoticeUntil = System.currentTimeMillis() + INTERRUPTION_NOTICE_MS
            appendLog("[SYS]", "$interruptionNoticeText — sin registrar nada desde la última escritura. Revisa si el servicio se detuvo (reinicio del móvil, OTA o batería agotada).")
        }
        isProxyEnabled = prefs.getBoolean("proxy_enabled", false)
        isLatencyDetectionEnabled = prefs.getBoolean("latency_detection_enabled", false)
        loadPersistedLastAcceptedLocation()  // referencia de plausibilidad superviviente a reinicios

        if (wigleApiName.isNotBlank() || openCellIdKey.isNotBlank()) {
            hasLoggedMissingCredentials = false
        }

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        createNotificationChannel()
        // En Android 14+ es obligatorio pasar el foregroundServiceType en startForeground.
        // Además, startForeground puede lanzar excepción (p. ej. ForegroundServiceStartNotAllowed
        // o SecurityException) si el permiso de ubicación no está concedido o el SO reinicia el
        // servicio en un estado restringido. Si eso ocurre, paramos limpiamente en vez de crashear.
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, buildNotification("Sondeo activo"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Sondeo activo"))
            }
        } catch (e: Exception) {
            Log.e("MiniIC", "No se pudo iniciar el servicio en primer plano: ${e.message}", e)
            stopSelf()
            return
        }

        registerTelephonyCallback()
        registerDisplayInfoCallback()
        registerScreenReceiver()

        startLocationUpdates()

        scope.launch(Dispatchers.Default) {
            var wasAirplaneModeOn = false
            while (isActive && isServiceRunning) {
                val delayTime = if (isScreenOn) 3000L else 10000L

                try {
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
                            // Al salir de modo avión forzamos un fix GPS fresco SÍ o SÍ (sin
                            // debounce): es un evento raro y crítico —si hubiera un IMSI-catcher
                            // empujándote o saltando de celda, necesitamos coordenadas reales ya,
                            // no cacheadas. El coste extra de batería puntual es aceptable.
                            requestHighAccuracyFix(force = true)
                        }
                        requestFreshCellInfo()
                        checkLatencyAnomaly()
                        val activeForPrune = _cellFlow.value.firstOrNull { it.isRegistered }
                        pruneCaches(
                            activeForPrune?.cellId,
                            activeForPrune?.identityKey
                        )

                        // En reposo (pantalla apagada) con una celda esperando coordenadas
                        // frescas: despertamos el GPS despacio (cada 90 s, hasta 10 min) hasta
                        // conseguir un fix fresco que la rellene. Se detiene solo en cuanto se
                        // resuelve (awaitingFreshCoords pasa a false). Con pantalla encendida lo
                        // cubre el stream, así que esto es solo para reposo.
                        if (!isScreenOn && awaitingFreshCoords) {
                            val nowMs = System.currentTimeMillis()
                            val pendingFor = nowMs - pendingCoordsSince
                            if (pendingFor < 600000L && nowMs - lastReposeCoordFixAttempt > 90000L) {
                                lastReposeCoordFixAttempt = nowMs
                                appendLog("[GPS]", "Celda pendiente de coordenadas — despertando GPS para fix fresco")
                                requestHighAccuracyFix()
                            }
                        }
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
        if (latencyHistory.size > MAX_TRACKED_CELLS) {
            latencyHistory.keys.filter { it != activeCellId }
                .take(latencyHistory.size - MAX_TRACKED_CELLS)
                .forEach { latencyHistory.remove(it) }
        }
        if (verificationCache.size > MAX_TRACKED_CELLS) {
            verificationCache.keys.filter { it != activeCacheKey }
                .take(verificationCache.size - MAX_TRACKED_CELLS)
                .forEach { verificationCache.remove(it) }
        }
    }

    private fun checkLatencyAnomaly() {
        // La sonda solo mide si está activada y NO hay WiFi, VPN ni Tor (SOCKS5):
        // en esos casos la latencia no es representativa de la red celular. Cuando no
        // puede medir, el indicador refleja "N/A" (no un "OK" sin verificar) y no se mide.
        if (!isLatencyProbeActive()) {
            _networkLatencyState.value = "N/A"   // StateFlow no re-emite si el valor no cambia
            latencyAnomalyStreak = 0
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastLatencyCheckTime < LATENCY_CHECK_INTERVAL) return

        val activeCellId = _cellFlow.value
            .firstOrNull { it.isRegistered }?.cellId ?: return

        lastLatencyCheckTime = now

        scope.launch(Dispatchers.IO) {
            // Medir los 3 endpoints EN PARALELO (antes era secuencial pese al comentario).
            // El resultado se sigue promediando en un único valor (una sola línea de log).
            val latencies = coroutineScope {
                LATENCY_ENDPOINTS.map { endpoint ->
                    async {
                        try {
                            val start = System.currentTimeMillis()
                            val request = okhttp3.Request.Builder()
                                .url(endpoint)
                                .head()  // HEAD: sin body, mínimo tráfico
                                .build()
                            latencyClient.newCall(request).execute().use { }
                            System.currentTimeMillis() - start
                        } catch (_: Exception) {
                            null  // endpoint no disponible, ignorar
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            // Necesitamos al menos 2 de 3 endpoints respondiendo
            if (latencies.size < 2) {
                appendLog("[NET]", "Latencia: sin suficientes endpoints disponibles")
                return@launch
            }

            val avgLatency = latencies.average().toLong()
            val history = latencyHistory.getOrPut(activeCellId) { mutableListOf() }

            if (history.size >= 5) {
                val historicalAvg = history.average()
                val isAnomalous = avgLatency > historicalAvg * LATENCY_ANOMALY_MULTIPLIER

                if (isAnomalous) {
                    _networkLatencyState.value = "ANOMALA"
                    latencyAnomalyStreak++
                    appendLog(
                        "[NET]",
                        "⚠ Latencia anómala [${latencyAnomalyStreak}/$LATENCY_CONFIRMATION_CYCLES]:" +
                                " ${avgLatency}ms (media: ${historicalAvg.toInt()}ms) — 3 endpoints"
                    )

                    if (latencyAnomalyStreak >= LATENCY_CONFIRMATION_CYCLES) {
                        appendLog("[NET]", "🔴 ANOMALÍA DE RED PERSISTENTE — posible interferencia MITM")

                        // Pitido suave diferenciado + notificación
                        scope.launch(Dispatchers.Main) {
                            // Pitido — distinto al de amenaza principal
                            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 300)

                            // Notificación visible aunque pantalla apagada
                            val notification = NotificationCompat.Builder(this@MiniICService, CHANNEL_ID)
                                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                                .setContentTitle("⚠ Anomalía de Red")
                                .setContentText("Latencia anómala persistente — posible interferencia MITM")
                                .setPriority(NotificationCompat.PRIORITY_HIGH)
                                .setAutoCancel(true)
                                .build()

                            val nm = getSystemService(NotificationManager::class.java)
                            nm.notify(LATENCY_NOTIFICATION_ID, notification)
                        }

                        latencyAnomalyStreak = 0
                    }
                } else {
                    _networkLatencyState.value = "OK"
                    if (latencyAnomalyStreak > 0) {
                        appendLog("[NET]", "✅ Latencia normalizada: ${avgLatency}ms")
                    }
                    latencyAnomalyStreak = 0
                    appendLog("[NET]", "Latencia OK: ${avgLatency}ms (media: ${historicalAvg.toInt()}ms)")
                }
            } else {
                // Aprendiendo baseline — no alarmar todavía
                appendLog("[NET]", "Latencia: ${avgLatency}ms (aprendiendo baseline ${history.size + 1}/$LATENCY_HISTORY_SIZE...)")
            }

            // Guardar en histórico
            history.add(avgLatency)
            if (history.size > LATENCY_HISTORY_SIZE) history.removeAt(0)
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
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOn = true
                        startLocationUpdates()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn = false
                        stopLocationUpdates()
                    }
                }
            }
        }
        registerReceiver(screenReceiver, filter)
    }

    /**
     * Pide ubicación a GPS satelital + NETWORK. Intervalo relajado (15 s / 20 m) para
     * ahorrar batería: las celdas no se mueven y no hace falta un fix cada pocos segundos.
     * El GPS satelital se mantiene a propósito porque es independiente de la red, así que
     * sigue siendo fiable ante un IMSI-catcher (a diferencia de la ubicación por red).
     */
    private fun startLocationUpdates() {
        if (locationUpdatesActive) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    15000L, 20f, locationListener, Looper.getMainLooper()
                )
            }
            // GPS-only por diseño: NO nos suscribimos a NETWORK_PROVIDER. La localización de red se
            // deriva en parte de las propias torres (circular para un detector de antenas) y devolvía
            // una posición fija/cacheada falsa cuando el GPS no llegaba, envenenando el historial y
            // las heurísticas geográficas (H11/H13). Sin GPS válido, la coordenada queda desconocida.
            locationUpdatesActive = true
        } catch (_: SecurityException) {}
    }

    /**
     * Suspende el stream de localización (se llama al apagar la pantalla). El escaneo de
     * celdas sigue corriendo; para los chequeos de fondo se usa getLastKnownLocation, que
     * no consume batería extra.
     */
    private fun stopLocationUpdates() {
        if (!locationUpdatesActive) return
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {}
        locationUpdatesActive = false
    }

    private var lastForcedFixTime = 0L
    private val forcedFixLock = Any()
    private var forcedFixListener: LocationListener? = null

    /**
     * Fase 2: ante una celda sospechosa, pide un fix GPS preciso de UN SOLO USO para
     * "clavar" la coordenada del incidente, aunque el stream continuo esté pausado
     * (pantalla apagada). El GPS satelital es independiente de la red, así que sigue
     * siendo fiable frente a un IMSI-catcher. Reutiliza updateNullCoordinates (camino ya
     * probado). Debounce de 30 s, timeout de 20 s y auto-desregistro al primer fix para
     * no drenar batería.
     */
    private fun requestHighAccuracyFix(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return

        lateinit var listener: LocationListener
        synchronized(forcedFixLock) {
            if (!force && now - lastForcedFixTime < 30000L) return
            if (forcedFixListener != null) return
            lastForcedFixTime = now
            listener = LocationListener { location ->
                // Validar el fix ANTES de aceptarlo: precisión, frescura y plausibilidad (el mismo
                // isPlausibleFix anti-salto que protege getCurrentLocation). Si NO sirve, dejamos el
                // listener VIVO para esperar uno mejor hasta el timeout.
                val freshFix = (System.currentTimeMillis() - location.time) < 120000L
                if (location.accuracy > 100f || !freshFix || !isPlausibleFix(location)) return@LocationListener

                val ownsRegistration = synchronized(forcedFixLock) {
                    if (forcedFixListener === listener) {
                        forcedFixListener = null
                        true
                    } else false
                }
                if (!ownsRegistration) return@LocationListener
                try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
                acceptLocation(location)
                awaitingFreshCoords = false
                val cell = _cellFlow.value.firstOrNull { it.isRegistered } ?: return@LocationListener
                scope.launch(Dispatchers.IO) {
                    val updated = dbHelper.updateNullCoordinates(
                        cell.cellId, cell.mnc, cell.tac, cell.mcc,
                        cell.radioTech,
                        location.latitude, location.longitude
                    )
                    appendLog("[GPS]",
                        if (updated > 0) "Incidente localizado: $updated registro(s) con coordenadas precisas"
                        else "Fix preciso obtenido (sin registros pendientes de coordenadas)")
                }
            }
            forcedFixListener = listener
        }
        appendLog("[GPS]", "Solicitando fix GPS preciso (fresco) para fijar coordenadas")

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper()
            )
        } catch (error: Exception) {
            synchronized(forcedFixLock) {
                if (forcedFixListener === listener) forcedFixListener = null
            }
            Log.e("MiniIC", "No se pudo registrar el fix GPS preciso: ${error.message}", error)
            return
        }

        // Timeout: desregistrar si no llega fix, para no dejar el GPS a tope. Con force
        // (salida de modo avión) damos 90 s porque el GPS arranca "en frío" y puede tardar
        // ~30-60 s en el primer fix; en el resto, 20 s basta (GPS ya caliente). En ambos casos
        // el listener se desregistra ANTES si llega un fix válido, así que el GPS se apaga en
        // cuanto tiene las coordenadas: el timeout es solo el límite, no un tiempo fijo.
        val timeoutMs = if (force) 90000L else 20000L
        scope.launch {
            delay(timeoutMs.milliseconds)
            val timedOut = synchronized(forcedFixLock) {
                if (forcedFixListener === listener) {
                    forcedFixListener = null
                    true
                } else false
            }
            if (timedOut) {
                try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
                appendLog("[GPS]", "Fix GPS preciso no disponible en ${timeoutMs / 1000} s (timeout)")
            }
        }
    }

    private fun registerTelephonyCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val callback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                            lastCallbackTime = System.currentTimeMillis()
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
                if (securityCallback != null) return
                securityCallback = ImsiCatcherSecurityCallback().also {
                    telephonyManager.registerTelephonyCallback(mainExecutor, it)
                }
                appendLog("[SYS]", "Callback de telefonía registrado (detección de cifrado nulo/IMSI: pendiente API Android 16).")
            }
        } catch (e: Exception) {
            isHardwareCipheringAvailable = false
            Log.e("MiniIC", "Aviso: No se pudo registrar el callback de seguridad avanzado: ${e.message}")
            appendLog("[SYS]", "Error al registrar callback L3: ${e.message}")
        }
    }

    // Reservada para la ruta de alerta de la detección de cifrado nulo (A5/0) e identificadores
    // (IMSI), pendiente de las APIs de Android 16. Actualmente NO tiene llamadores
    // porque esa detección aún no está implementada (ver ImsiCatcherSecurityCallback). Se mantiene
    // completa y lista para conectarla cuando se implemente; el suppress evita el warning de "sin uso".
    @Suppress("unused")
    private fun triggerSecurityAlert(message: String) {
        Log.e("MiniIC_Security", message)
        appendLog("[SEC]", "🚨 ALERTA: $message")
        toneGenerator?.startTone(ToneGenerator.TONE_SUP_ERROR, 500)
        updateNotificationText("⚠️ $message")
        
        scope.launch(Dispatchers.IO) {
            val rowId = dbHelper.logConnection(
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
            noteWriteResult(rowId)
        }
    }

    private fun registerDisplayInfoCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    displayInfoCallback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                        override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                            lastCallbackTime = System.currentTimeMillis()
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
        // Si no llega ningún evento del callback en callbackTimeoutMs, el módem probablemente lo
        // colgó (Doze / sesión larga / reinicio del HAL): lo desregistramos y re-registramos. Al
        // arranque, lastCallbackTime = 0 => se considera "colgado" hasta el primer evento, igual que
        // antes hacía el booleano en false (preserva el reintento de registro inicial).
        val callbackStale = (System.currentTimeMillis() - lastCallbackTime) > callbackTimeoutMs
        if (callbackStale && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)) {
            connectionRetryCount++
            if (connectionRetryCount >= 4) {
                connectionRetryCount = 0
                try {
                    telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                    telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
                    displayInfoCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
                    securityCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
                    telephonyCallback = null
                    displayInfoCallback = null
                    securityCallback = null
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
                                // allCellInfo es el snapshot cacheado de la plataforma. Puede
                                // actualizar la pantalla, pero nunca abrir la salida de emergencia
                                // temporal como si fuera una entrega fresca del módem.
                                processCellInfo(telephonyManager.allCellInfo, isFreshDelivery = false)
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
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {}
        val pendingForcedFix = synchronized(forcedFixLock) {
            forcedFixListener.also { forcedFixListener = null }
        }
        pendingForcedFix?.let {
            try { locationManager.removeUpdates(it) } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
            displayInfoCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
            securityCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
            telephonyCallback = null
            displayInfoCallback = null
            securityCallback = null
        }
    }

    private fun processCellInfo(infoList: List<CellInfo>?, isFreshDelivery: Boolean = true) {
        try {
            if (!infoList.isNullOrEmpty()) {
                lastCallbackTime = System.currentTimeMillis()
                connectionRetryCount = 0
            }

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
                updateNotificationText("Sin señal / Modo Avión")
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
                taSanity.observe(taKey, current.timingAdvance)
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
                _networkLatencyState.value = idleLatencyState()
                latencyAnomalyStreak = 0
            }

            scope.launch(Dispatchers.IO) {
                cellProcessingMutex.withLock {
                if (processingSequence < enqueuedCellProcessing.get()) return@withLock
                val currentLocation = getCurrentLocation()

                // H16 se prepara antes de analizar la celda: aquí todavía conservamos la última
                // servidora. El resultado se retiene 20 s para que los tres ciclos temporales
                // puedan observar el mismo handover, sin convertirlo en un fallo permanente.
                val transitionCoherence = if (activeRaw != null && activeRaw.cellId != "N/A") {
                    evaluateServingTransition(activeRaw, currentLocation, neighbors)
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
                        ThreatAnalyzer.analyzeThreats(
                            active = cell.copy(verified = knownVerified),
                            neighbors = neighbors,
                            isHardwareCipheringActive = isHardwareCipheringActive,
                            isHardwareCipheringAvailable = isHardwareCipheringAvailable,
                            cellChangeHistory = cellChangeHistory,
                            currentLocation = currentLocation,
                            preloadedHistory = preloadedHistory,
                            isWifiActive = isWifiConnected(),
                            isNetworkLatencyAnomalous = networkLatencyState.value == "ANOMALA",
                            isNetworkLatencyAvailable = networkLatencyState.value != "N/A",
                            signalBaseline = signalBaseline,
                            previousBand = prevBand,
                            previousDbm = prevRegisteredDbm,
                            recentRegisteredDbm = recentRegisteredDbmTrend.toList(),
                            rfStability = rfStability,
                            reputation = reputation,
                            rfFingerprint = rfFingerprint,
                            transitionCoherence = transitionCoherence
                        ).copy(distanceToTowerMeters = towerDistance)
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
                        // 1. Obtener estado conocido (Caché o DB) para no mostrar PENDING si ya existe
                        val cacheKey = active.identityKey
                        val knownStatus = verificationCache[cacheKey] ?: VerificationStatus.PENDING

                        // Aplicar confirmación temporal antes de alertas
                        val temporalActive = temporalConfidence.apply(
                            active,
                            observationToken.takeIf { it > 0L },
                            isFreshDelivery = isFreshDelivery
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
                        scope.launch(Dispatchers.IO) {
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
                        scope.launch(Dispatchers.IO) {
                            forensicRecorder.observe(
                                active = confirmedActive.copy(verified = knownStatus),
                                neighbors = neighbors,
                                location = currentLocation,
                                latencyState = networkLatencyState.value,
                                logs = liveLogs.value
                            )
                        }

                        // 2. Lanzar alertas y registro con el estado actual
                        checkAlerts(confirmedActive.copy(verified = knownStatus), confirmed = true)

                        // 3. Iniciar proceso de verificación (solo si es necesario)
                        verifyCell(active, neighbors)
                        
                        val finalStatus = verificationCache[cacheKey] ?: knownStatus
                        
                        _cellFlow.value = sorted.map { 
                            if (it.isRegistered) confirmedActive.copy(verified = finalStatus) else it 
                        }
                        
                        updateNotification(confirmedActive.copy(verified = finalStatus))

                        // Actualizar estado de banda para la heurística 14 (tras el análisis,
                        // de modo que el PRÓXIMO ciclo compare contra estos valores).
                        val isLteActive = active.networkType.contains("4G") || active.networkType.contains("LTE")
                        prevBand = if (isLteActive) {
                            active.band ?: active.arfcn?.let { com.alexisgordr.icdetector.core.BandPlan.earfcnToBandLte(it) }
                        } else null
                        prevRegisteredDbm = active.dbm
                        if (active.dbm != Int.MAX_VALUE && active.dbm != -999) {
                            recentRegisteredDbmTrend.add(active.dbm)
                            while (recentRegisteredDbmTrend.size > 6) recentRegisteredDbmTrend.removeAt(0)
                        }
                    } else {
                        _cellFlow.value = emptyList()
                        updateNotificationText("Buscando red...")
                    }
                }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun evaluateServingTransition(
        active: CellData,
        currentLocation: Location?,
        neighbors: List<CellData>
    ): TransitionCoherenceResult {
        val now = SystemClock.elapsedRealtime()
        val current = ServingSnapshot(
            cell = active,
            location = currentLocation?.let { Location(it) },
            elapsedRealtimeMs = now,
            neighborIdentities = neighbors.map { it.identityKey }.toSet()
        )

        val prior = synchronized(transitionLock) {
            val previous = lastServingSnapshot
            lastServingSnapshot = current
            if (previous == null) return TransitionCoherenceResult()
            if (previous.cell.identityKey == active.identityKey) {
                return if (activeTransitionIdentity == active.identityKey && now <= activeTransitionExpiresAt) {
                    activeTransitionResult
                } else TransitionCoherenceResult(
                    explanation = "N/A: esperando el siguiente handover para comprobar la movilidad."
                )
            }
            // Evita que un resultado de la celda anterior sobreviva durante el cálculo del nuevo.
            activeTransitionIdentity = null
            activeTransitionResult = TransitionCoherenceResult()
            previous
        }

        val elapsedSeconds = ((now - prior.elapsedRealtimeMs) / 1_000L).coerceAtLeast(0L)
        val previousLocation = prior.location
        val moved = if (previousLocation != null && currentLocation != null) {
            previousLocation.distanceTo(currentLocation).toDouble()
        } else null
        val result = com.alexisgordr.icdetector.core.TransitionCoherence.evaluate(
            com.alexisgordr.icdetector.core.TransitionCoherence.Input(
                fromIdentity = prior.cell.identityKey,
                toIdentity = active.identityKey,
                elapsedSeconds = elapsedSeconds,
                deviceDistanceMeters = moved,
                previousAccuracyMeters = previousLocation?.accuracy,
                currentAccuracyMeters = currentLocation?.accuracy,
                fromSamples = dbHelper.getCellLocationSamples(prior.cell),
                toSamples = dbHelper.getCellLocationSamples(active),
                priorTrustedTransitions = dbHelper.getTrustedTransitionCount(
                    prior.cell.identityKey, active.identityKey
                ),
                destinationWasNeighbor = active.identityKey in prior.neighborIdentities
            )
        )
        dbHelper.recordCellTransition(result)
        synchronized(transitionLock) {
            activeTransitionIdentity = active.identityKey
            activeTransitionResult = result
            activeTransitionExpiresAt = now + TRANSITION_RESULT_TTL_MS
        }
        appendLog(
            "[H16]",
            when (result.status) {
                HeuristicStatus.PASSED -> "Transición coherente: ${result.explanation}"
                HeuristicStatus.FAILED -> "Transición incoherente: ${result.explanation}"
                HeuristicStatus.NOT_EVALUATED -> result.explanation
            }
        )
        return result
    }

    // Fix #2: último fix GPS aceptado como bueno, para validar plausibilidad del siguiente.
    private var lastAcceptedLocation: Location? = null
    private var implausibleFixCount = 0
    private var lastPersistedLocTime = 0L  // evita reescribir prefs con el mismo getLastKnownLocation

    // ¿Es plausible este fix respecto al último aceptado? Un fix puede pasar los filtros de
    // precisión y antigüedad y aun así situarte a cientos de km por un error del GPS (visto en
    // campo: una observación en Pamplona con coordenada en los Países Bajos, ~1.100 km). Si la
    // velocidad implícita respecto al último fix bueno supera 400 km/h —por encima de cualquier
    // desplazamiento terrestre normal (coche/tren)— el fix es basura y se descarta. Si se
    // rechazan varios seguidos, la referencia es la sospechosa: se suelta y se acepta el nuevo
    // para no quedarnos bloqueados sin coordenada indefinidamente (auto-recuperación).
    private fun isPlausibleFix(candidate: Location): Boolean {
        val prev = lastAcceptedLocation ?: return true
        val meters = prev.distanceTo(candidate)
        val seconds = ((candidate.time - prev.time) / 1000.0).coerceAtLeast(1.0)
        val speedKmh = (meters / seconds) * 3.6
        if (speedKmh <= 400.0) {
            implausibleFixCount = 0
            return true
        }
        implausibleFixCount++
        if (implausibleFixCount >= 3) {
            implausibleFixCount = 0
            return true
        }
        return false
    }

    // Acepta un fix como nueva referencia de plausibilidad y lo persiste, de modo que la
    // referencia sobreviva a un reinicio del servicio. Solo reescribe prefs si el fix es más
    // nuevo que el último persistido (getLastKnownLocation devuelve el mismo fix repetido).
    private fun acceptLocation(loc: Location) {
        lastAcceptedLocation = loc
        if (loc.time > lastPersistedLocTime) {
            lastPersistedLocTime = loc.time
            try {
                getSharedPreferences("miniic_prefs", MODE_PRIVATE).edit()
                    .putString(KEY_LAST_LOC, "${loc.latitude},${loc.longitude},${loc.time}")
                    .apply()
            } catch (_: Exception) {}
        }
    }

    // Carga la referencia persistida al arrancar, salvo que sea muy vieja o tenga marca de
    // tiempo en el futuro (clock raro): en ese caso se deja null y el próximo fix es bootstrap.
    private fun loadPersistedLastAcceptedLocation() {
        try {
            val raw = getSharedPreferences("miniic_prefs", MODE_PRIVATE)
                .getString(KEY_LAST_LOC, null) ?: return
            val parts = raw.split(",")
            if (parts.size != 3) return
            val lat = parts[0].toDoubleOrNull() ?: return
            val lon = parts[1].toDoubleOrNull() ?: return
            val t = parts[2].toLongOrNull() ?: return
            val age = System.currentTimeMillis() - t
            if (age in 0..LOCATION_REFERENCE_MAX_AGE) {
                lastAcceptedLocation = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = lat
                    longitude = lon
                    time = t
                }
                lastPersistedLocTime = t
            }
        } catch (_: Exception) {}
    }

    private fun getCurrentLocation(): Location? {
        return try {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED) return null

            // GPS-only por diseño: NO usamos NETWORK_PROVIDER (ver requestLocationUpdates). La
            // localización de red es circular para validar antenas y devolvía posiciones falsas
            // cuando el GPS no llegaba. Sin fix GPS válido devolvemos null y se falla seguro.
            val gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)

            val now = System.currentTimeMillis()
            val maxAge = 120000L    // 2 minutos
            val maxAccuracy = 100f  // 100 metros

            // Filtrar por antigüedad y precisión (solo GPS)
            val best = gpsLoc?.takeIf {
                it.accuracy < maxAccuracy &&
                (now - it.time) < maxAge
            }

            // Fix #2: descartar fixes físicamente imposibles (saltos absurdos = GPS corrupto).
            // Si es plausible, se acepta y pasa a ser la nueva referencia; si no, se devuelve null
            // y la coordenada queda desconocida, en vez de contaminar el historial y las heurísticas
            // geográficas (H11/H13) con una posición falsa.
            if (best != null && isPlausibleFix(best)) {
                acceptLocation(best)
                best
            } else {
                null
            }
        } catch (_: Exception) { null }
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
                awaitingFreshCoords = false  // fix fresco del stream: pendiente resuelto
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
            verifyCell(currentCell, _cellFlow.value.filter { !it.isRegistered })
        }
    }

    /**
     * ¿Es creíble la coordenada que ha devuelto WiGLE/OpenCellID para esta antena?
     *
     * v2.1 — reescrita a partir de los datos de campo. La versión anterior hacía un chequeo de
     * distancia de 50 km, pero SOLO si había un fix GPS vivo; sin fix, aceptaba cualquier cosa.
     * Y "sin fix" es justo el caso frecuente (la app es GPS-only por diseño y bajo techo no hay
     * fix), así que una coordenada constante y absurda —la misma, hasta el sexto decimal, para
     * 46 celdas distintas y a ~1.100 km— entraba sin oposición y marcaba esas celdas como
     * VERIFIED (+15 de score). Eso no es un problema estético: es una vía de FALSO NEGATIVO,
     * porque una celda verificada por error queda mucho más difícil de marcar después.
     *
     * Ahora hay tres barreras, de más fuerte a más débil:
     *  1. Centinela: una coordenada que ya consta para varias Cell ID distintas no identifica a
     *     ninguna antena — es un valor por defecto de la API. Se rechaza. No necesita GPS.
     *  2. Distancia contra el fix GPS vivo (50 km), como antes.
     *  3. Si no hay fix vivo, se usa la última posición aceptada (persistida, máx. 6 h) con un
     *     margen mucho más generoso (500 km) para no rechazar nada legítimo tras un viaje. Un
     *     salto de 1.100 km sigue cayendo.
     * Sin ninguna referencia disponible, se acepta (comportamiento clásico): la barrera 1 sigue
     * activa igualmente.
     */
    private fun isValidCoordinate(lat: Double, lon: Double, cell: CellData? = null): Boolean {
        if (lat == 0.0 && lon == 0.0) return false
        if (lat < -90 || lat > 90) return false
        if (lon < -180 || lon > 180) return false

        // 1. Coordenada centinela: la misma respuesta en áreas de seguimiento sin relación entre
        //    sí. Se excluye el área de la propia celda: los sectores y bandas de un mismo mástil
        //    comparten coordenada de forma legítima y contarlos convertía una antena normal en
        //    sospechosa según se llenaba el historial.
        if (cell != null) {
            try {
                val sharedBy = dbHelper.countDistinctAreasWithApiCoordinate(
                    lat, lon, cell.mcc, cell.mnc, cell.tac
                )
                if (sharedBy >= API_SENTINEL_MIN_AREAS) {
                    appendLog("[API]", "Coordenada rechazada: ya consta en $sharedBy áreas de seguimiento sin relación entre sí (valor por defecto de la API, no identifica ninguna antena).")
                    return false
                }
            } catch (_: Exception) {
                // Si la consulta fallara, seguimos con las barreras de distancia.
            }
        }

        // 2 y 3. Distancia contra la mejor referencia disponible.
        val liveLoc = getCurrentLocation()
        val reference: Location?
        val maxDistanceMeters: Float
        if (liveLoc != null) {
            reference = liveLoc
            maxDistanceMeters = 50_000f
        } else {
            val fallback = lastAcceptedLocation
            val age = if (fallback != null) System.currentTimeMillis() - fallback.time else Long.MAX_VALUE
            reference = if (fallback != null && age in 0..LOCATION_REFERENCE_MAX_AGE) fallback else null
            maxDistanceMeters = 500_000f
        }

        if (reference == null) return true

        val results = FloatArray(1)
        Location.distanceBetween(
            reference.latitude, reference.longitude,
            lat, lon, results
        )
        if (results[0] > maxDistanceMeters) {
            appendLog("[API]", "Coordenada rechazada: a ${(results[0] / 1000f).toInt()} km de tu posición de referencia.")
            return false
        }
        return true
    }

    /**
     * Veredicto cuando las dos fuentes no dicen lo mismo.
     *
     * Antes cada bloque hacía `finalStatus = s`, de modo que **ganaba la última** en preguntarse.
     * Con eso, una base que contestaba de verdad "esta celda no está" quedaba borrada por un fallo
     * de red de la otra, o al revés. El orden de las llamadas no es un argumento.
     *
     * VERIFIED manda sobre todo. NOT_FOUND solo queda como conclusión cuando todas las fuentes
     * consultadas coinciden. Una mezcla de negativa, error o descarte es REJECTED: inconclusa.
     */
    private fun mejorRespuesta(actual: VerificationStatus, nueva: VerificationStatus): VerificationStatus =
        VerificationDecision.combine(actual, nueva)

    /**
     * ¿Se puede preguntar a una API por esta celda?
     *
     * Los cuatro identificadores (MCC, MNC, área y Cell ID) tienen que estar presentes y ser
     * numéricos. Cualquier otra cosa —"N/A", vacío, texto— no es una celda: es una lectura que el
     * módem no ha podido completar. Preguntar igualmente produce respuestas que parecen datos y no
     * lo son.
     */
    private fun esIdentidadConsultable(cell: CellData): Boolean =
        listOf(cell.mcc, cell.mnc, cell.tac, cell.cellId).all {
            it.isNotBlank() && it != "N/A" && it.toLongOrNull() != null
        }

    private fun verifyCell(cell: CellData, neighbors: List<CellData>) {
        // No se pregunta por una celda cuya identidad no es una identidad.
        //
        // El guard anterior miraba CID, MNC y MCC, pero dejaba pasar el área: con el TAC a "N/A" la
        // consulta salía literalmente como `lac=N/A`. Una API puede contestar a eso con su código
        // de "cell not found" —porque, en efecto, esa celda no existe— y la app registraba en el
        // historial forense un NOT_FOUND firme sobre una antena real, por una pregunta que nunca
        // fue válida. Se exige además que los cuatro sean numéricos: un identificador celular lo
        // es siempre, y lo que no lo sea viene de una lectura fallida del módem, no de la red.
        if (!esIdentidadConsultable(cell)) return

        val cacheKey = cell.identityKey
        
        // 1. Mirar caché rápida
        val cached = verificationCache[cacheKey]
        // VERIFIED y PENDING nunca se reverifican aquí (return inmediato). ERROR, NOT_FOUND y
        // REJECTED sí tienen camino de reintento, cada uno con su propia ventana temporal (abajo).
        if (cached != null &&
            cached != VerificationStatus.ERROR &&
            cached != VerificationStatus.NOT_FOUND &&
            cached != VerificationStatus.REJECTED) return
        // Si la última vez fue ERROR (p. ej. sin red), reintentar solo pasados 60s
        // para no machacar la API en cada ciclo de escaneo (~2s).
        if (cached == VerificationStatus.ERROR) {
            val last = lastVerificationErrorTime[cacheKey] ?: 0L
            if (System.currentTimeMillis() - last < 60000L) return
        }
        // Si la última vez fue NOT_FOUND, reintentar en vivo solo pasada 1h, por si una torre
        // legítima recién desplegada ya está en WiGLE/OpenCellID y debe pasar a VERIFIED.
        if (cached == VerificationStatus.NOT_FOUND) {
            val last = lastNotFoundTime[cacheKey] ?: 0L
            if (System.currentTimeMillis() - last < NOT_FOUND_REVERIFY_TTL) return
        }
        if (cached == VerificationStatus.REJECTED) {
            val last = lastRejectedTime[cacheKey] ?: 0L
            if (System.currentTimeMillis() - last < REJECTED_REVERIFY_TTL) return
        }

        // Verificar credenciales
        val hasWigle = wigleApiName.isNotBlank() && wigleApiToken.isNotBlank()
        val hasOpenCellId = openCellIdKey.isNotBlank() && !openCellIdKey.startsWith("pk.YOUR")

        if (!hasWigle && !hasOpenCellId) {
            if (!hasLoggedMissingCredentials) {
                appendLog("[API]", "⚠ Sin credenciales configuradas. Ve a Ajustes para añadir Wigle o OpenCellID.")
                hasLoggedMissingCredentials = true
            }
            return
        }

        // Reclamo atómico: solo un hilo puede pasar de (no-PENDING) a PENDING para esta
        // celda. Re-comprobamos dentro del lock para cerrar la ventana de carrera si dos
        // rutas llaman a verifyCell casi a la vez (evita peticiones a la API duplicadas).
        synchronized(verificationCache) {
            val current = verificationCache[cacheKey]
            if (current != null &&
                current != VerificationStatus.ERROR &&
                current != VerificationStatus.NOT_FOUND &&
                current != VerificationStatus.REJECTED) return
            if (current == VerificationStatus.ERROR) {
                val last = lastVerificationErrorTime[cacheKey] ?: 0L
                if (System.currentTimeMillis() - last < 60000L) return
            }
            if (current == VerificationStatus.NOT_FOUND) {
                val last = lastNotFoundTime[cacheKey] ?: 0L
                if (System.currentTimeMillis() - last < NOT_FOUND_REVERIFY_TTL) return
            }
            if (current == VerificationStatus.REJECTED) {
                val last = lastRejectedTime[cacheKey] ?: 0L
                if (System.currentTimeMillis() - last < REJECTED_REVERIFY_TTL) return
            }
            verificationCache[cacheKey] = VerificationStatus.PENDING
        }

        scope.launch(Dispatchers.IO) {
            // 2. Mirar Base de Datos (fuera del hilo principal)
            val currentLoc = getCurrentLocation()
            val statusFromDb = dbHelper.getKnownStatus(
                cell.mnc, cell.tac, cell.cellId, cell.mcc,
                currentLoc?.latitude, currentLoc?.longitude, cell.radioTech
            )
            
            if (statusFromDb != VerificationStatus.PENDING) {
                verificationCache[cacheKey] = statusFromDb
                // Si la DB ya lo tenía como NOT_FOUND, arrancamos la ventana de 1h desde ahora
                // para no reconsultar la DB cada ciclo; se reverificará pasado el TTL.
                if (statusFromDb == VerificationStatus.NOT_FOUND) {
                    lastNotFoundTime[cacheKey] = System.currentTimeMillis()
                }
                updateFlowWithStatus(cell, statusFromDb)
                // Actualizar la fila recién insertada que quedó en PENDING
                dbHelper.updateVerificationStatus(
                    cell.mnc, cell.tac, cell.cellId,
                    statusFromDb,
                    mcc = cell.mcc,
                    radio = cell.radioTech
                )
                return@launch
            }

            // 3. Consultar APIs si es realmente nueva
            var finalStatus = VerificationStatus.PENDING
            // La identidad exacta que se pregunta, en el terminal: es lo que hace falta para
            // comprobar a mano en wigle.net u opencellid.org si la antena está o no está, y por
            // tanto para distinguir un fallo de la app de una laguna de las bases públicas.
            appendLog("[API]", "Verificando antena MCC ${cell.mcc} · MNC ${cell.mnc} · TAC ${cell.tac} · CID ${cell.cellId}")

            // ORDEN: OpenCellID primero, WiGLE después.
            //
            // El endpoint de celdas de WiGLE no está abierto a todas las cuentas y su cobertura de
            // celdas es mucho más floja que la de OpenCellID, así que preguntarle primero gastaba
            // una petición y una espera en la fuente que menos resuelve. Se pregunta primero a la
            // que contesta, y WiGLE queda como refuerzo. Si la primera verifica, no hay segunda
            // llamada: se sale por `return@launch`.
            if (openCellIdKey.isNotBlank() && !openCellIdKey.startsWith("pk.YOUR")) {
                val res = OpenCellIdClient.tryOpenCellIdSyncWithData(cell, openCellIdKey, isProxyEnabled, client)
                val s = res.status
                val data = res.record
                appendLog("[API]", "OpenCellID → ${s.name}")
                // El motivo real llega hasta el terminal: una cuota agotada no puede leerse igual
                // que una antena desconocida, porque no significa lo mismo ni se registra igual.
                res.reason?.let { appendLog("[API]", it) }
                if (s == VerificationStatus.VERIFIED && data != null) {
                    val lat = data.optDouble("lat", Double.NaN)
                    val lon = data.optDouble("lon", Double.NaN)
                    val hasCoords = !lat.isNaN() && !lon.isNaN()
                    if (hasCoords && isValidCoordinate(lat, lon, cell)) {
                        processSuccessfulVerification(lat, lon, cell, cacheKey, "OpenCellID")
                        return@launch
                    } else if (hasCoords) {
                        // La API respondió con una coordenada que NO es creíble: centinela, o a una
                        // distancia imposible. Eso invalida la RESPUESTA, no acusa a la antena — de
                        // ahí REJECTED y no NOT_FOUND. Decir "no está en las bases" cuando lo que
                        // pasa es "no me fío de lo que me han contestado" mete una afirmación falsa
                        // en el historial que luego hay que analizar.
                        appendLog("[API]", "OpenCellID: respuesta descartada por coordenada no creíble. No se concluye nada sobre la antena.")
                        finalStatus = mejorRespuesta(finalStatus, VerificationStatus.REJECTED)
                    } else {
                        appendLog("[API]", "OpenCellID: respuesta sin coordenadas; no se puede comprobar.")
                        finalStatus = mejorRespuesta(finalStatus, VerificationStatus.REJECTED)
                    }
                } else {
                    finalStatus = mejorRespuesta(finalStatus, s)
                }
            }

            // Refuerzo WiGLE. Se pregunta cuando OpenCellID no ha resuelto, salvo que WiGLE haya
            // comunicado que la cuota de TODA la cuenta está agotada. Ese bloqueo es global y
            // persistente: reintentar por celda cada 60 s no puede recuperar cuota y solo genera
            // tráfico inútil.
            val wigleConfigured = wigleApiName.isNotBlank() && wigleApiToken.isNotBlank()
            val now = System.currentTimeMillis()
            val wigleCoolingDown = now < wigleRateLimitedUntil
            if (wigleConfigured && wigleCoolingDown) {
                // WiGLE no se ha consultado: el cooldown no es una respuesta y no puede aportar
                // evidencia a la agregación. Inyectar ERROR aquí convertía un NOT_FOUND real de
                // OpenCellID en REJECTED durante toda la pausa global.
                if (!hasLoggedWigleCooldown) {
                    val minutes = ((wigleRateLimitedUntil - now + 59_999L) / 60_000L).coerceAtLeast(1L)
                    appendLog("[API]", "WiGLE: cuota diaria agotada; consultas pausadas (${minutes} min restantes). OpenCellID y el análisis local siguen activos.")
                    hasLoggedWigleCooldown = true
                }
            } else if (wigleConfigured) {
                val res = WigleClient.tryWigleSync(cell, wigleApiName, wigleApiToken, isProxyEnabled, client)
                val s = res.status
                val data = res.record
                appendLog("[API]", "WiGLE → ${s.name}")
                res.reason?.let { appendLog("[API]", it) }

                if (res.failure == VerificationFailure.RATE_LIMITED) {
                    val cooldown = (res.retryAfterMillis ?: WIGLE_RATE_LIMIT_FALLBACK_MS)
                        .coerceAtLeast(WIGLE_RATE_LIMIT_MINIMUM_MS)
                    wigleRateLimitedUntil = System.currentTimeMillis() + cooldown
                    getSharedPreferences("miniic_prefs", MODE_PRIVATE).edit()
                        .putLong(KEY_WIGLE_RATE_LIMITED_UNTIL, wigleRateLimitedUntil)
                        .apply()
                    hasLoggedWigleCooldown = true
                    appendLog("[API]", "WiGLE: cuota diaria agotada. No se volverá a consultar durante 24 h (o el plazo indicado por el servidor). OpenCellID y el análisis local siguen activos.")
                } else if (wigleRateLimitedUntil != 0L) {
                    // La pausa ya venció y WiGLE volvió a contestar: eliminamos el estado antiguo.
                    wigleRateLimitedUntil = 0L
                    hasLoggedWigleCooldown = false
                    getSharedPreferences("miniic_prefs", MODE_PRIVATE).edit()
                        .remove(KEY_WIGLE_RATE_LIMITED_UNTIL)
                        .apply()
                }
                if (s == VerificationStatus.VERIFIED && data != null) {
                    val lat = data.optDouble("trilat", data.optDouble("lat", Double.NaN))
                    val lon = data.optDouble("trilong", data.optDouble("lon", Double.NaN))
                    val hasCoords = !lat.isNaN() && !lon.isNaN()
                    if (hasCoords && isValidCoordinate(lat, lon, cell)) {
                        processSuccessfulVerification(lat, lon, cell, cacheKey, "WiGLE")
                        return@launch
                    } else if (hasCoords) {
                        appendLog("[API]", "WiGLE: respuesta descartada por coordenada no creíble. No se concluye nada sobre la antena.")
                        finalStatus = mejorRespuesta(finalStatus, VerificationStatus.REJECTED)
                    } else {
                        appendLog("[API]", "WiGLE: respuesta sin coordenadas; no se puede comprobar.")
                        finalStatus = mejorRespuesta(finalStatus, VerificationStatus.REJECTED)
                    }
                } else {
                    finalStatus = mejorRespuesta(finalStatus, s)
                }
            }

            // Una consulta negativa NO borra una verificación anterior — v2.1.
            //
            // WiGLE y OpenCellID no dan de baja antenas: si esta celda estuvo en sus bases, sigue
            // estándolo. Cuando una reconsulta vuelve vacía lo que ha cambiado casi siempre es la
            // cuota diaria, el permiso de la cuenta o la cobertura del momento, no la antena. Sin
            // esta regla, una sola reconsulta desafortunada convertía una celda verificada en
            // "desconocida" y escribía esa contradicción en el historial
            // forense junto a las filas que la daban por verificada.
            //
            // Lo que sí es una señal —la misma Cell ID reaparecida a cientos de kilómetros— no
            // pasa por aquí: eso lo detecta la comprobación de distancia de getKnownStatus, que
            // exige una respuesta AFIRMATIVA con otra coordenada.
            if (finalStatus != VerificationStatus.VERIFIED &&
                dbHelper.hasRecentVerifiedRecord(cell.cellId, cell.mnc, cell.tac, cell.mcc, cell.radioTech)) {
                verificationCache[cacheKey] = VerificationStatus.VERIFIED
                appendLog("[API]", "Esta antena ya constaba verificada y ahora la consulta no la devuelve. Se mantiene la verificación: las bases públicas no dan de baja antenas, y una consulta vacía o fallida no es una prueba.")
                dbHelper.updateVerificationStatus(cell.mnc, cell.tac, cell.cellId, VerificationStatus.VERIFIED, mcc = cell.mcc, radio = cell.radioTech)
                updateFlowWithStatus(cell, VerificationStatus.VERIFIED)
                return@launch
            }

            // Guardar resultado negativo si ninguna lo encontró
            if (finalStatus == VerificationStatus.NOT_FOUND) {
                verificationCache[cacheKey] = VerificationStatus.NOT_FOUND
                lastNotFoundTime[cacheKey] = System.currentTimeMillis()
                dbHelper.updateVerificationStatus(cell.mnc, cell.tac, cell.cellId, VerificationStatus.NOT_FOUND, mcc = cell.mcc, radio = cell.radioTech)
                appendLog("[API]", "Antena no identificada en bases públicas. Se reintentará en 1h.")
                updateFlowWithStatus(cell, VerificationStatus.NOT_FOUND)
                // El camino VERIFIED vuelve a puntuar la celda aquí mismo; este no lo hacía, así
                // que la pantalla se quedaba con el score anterior (calculado cuando todavía era
                // PENDING) hasta el siguiente muestreo — hasta 5 minutos enseñando un número que
                // el motor ya no sostiene. Una lectura nueva reanaliza y cierra el desfase. No
                // hay recursión: el guard de NOT_FOUND de verifyCell no reintenta hasta 1 h.
                requestFreshCellInfo()
            } else if (finalStatus == VerificationStatus.ERROR) {
                // FIX: antes la celda se quedaba atascada en PENDING para siempre tras
                // un fallo de red, porque el guard de arriba solo reintenta si es ERROR.
                // Marcamos ERROR (con timestamp) para permitir un reintento posterior.
                verificationCache[cacheKey] = VerificationStatus.ERROR
                lastVerificationErrorTime[cacheKey] = System.currentTimeMillis()
                appendLog("[API]", "Error de red al verificar. Se reintentará más tarde.")
                updateFlowWithStatus(cell, VerificationStatus.ERROR)
            } else if (finalStatus == VerificationStatus.REJECTED) {
                // La API contestó, pero su respuesta no supera nuestras comprobaciones: identidad
                // que no cuadra, coordenada centinela, coordenada imposible, respuesta incompleta.
                //
                // Esto NO es una negativa sobre la antena y por eso tiene estado propio. Se guarda
                // en el historial —interesa saber cuántas respuestas hubo que descartar y de qué
                // fuente— y se reintenta pasada la misma hora que una negativa, porque lo más
                // probable es que la siguiente consulta traiga exactamente lo mismo.
                verificationCache[cacheKey] = VerificationStatus.REJECTED
                lastRejectedTime[cacheKey] = System.currentTimeMillis()
                dbHelper.updateVerificationStatus(cell.mnc, cell.tac, cell.cellId, VerificationStatus.REJECTED, mcc = cell.mcc, radio = cell.radioTech)
                appendLog("[API]", "Respuesta descartada: no permite afirmar ni desmentir nada sobre esta antena. Se reintentará en 1 h si la celda sigue activa o reaparece.")
                updateFlowWithStatus(cell, VerificationStatus.REJECTED)
            } else if (finalStatus == VerificationStatus.VERIFIED) {
                // La API confirmó la identidad de la celda pero no se pudo guardar coordenada.
                // Ojo: llegar aquí es raro desde v2.1, porque una respuesta sin coordenada
                // comprobable se marca REJECTED antes. Se conserva la rama para no dejar nunca la
                // caché en PENDING, que es el único estado del que no se sale.
                verificationCache[cacheKey] = VerificationStatus.VERIFIED
                dbHelper.updateVerificationStatus(cell.mnc, cell.tac, cell.cellId, VerificationStatus.VERIFIED, mcc = cell.mcc, radio = cell.radioTech)
                appendLog("[API]", "Antena verificada (sin coordenada disponible).")
                updateFlowWithStatus(cell, VerificationStatus.VERIFIED)
            } else {
                // Ninguna rama anterior: no debería ocurrir, porque sin credenciales se sale antes
                // y todo cliente devuelve uno de los cinco estados. Si algún día ocurre, lo que
                // NO puede pasar es quedarse en PENDING: el guard de arriba nunca reintenta una
                // celda PENDING, así que se quedaría colgada el resto de la sesión. ERROR la deja
                // en el camino de reintento a los 60 s.
                verificationCache[cacheKey] = VerificationStatus.ERROR
                lastVerificationErrorTime[cacheKey] = System.currentTimeMillis()
                updateFlowWithStatus(cell, VerificationStatus.ERROR)
            }
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

    private fun processSuccessfulVerification(
        lat: Double,
        lon: Double,
        cell: CellData,
        cacheKey: String,
        source: String
    ) {
        appendLog("[API]", "Validación OK ($source). Firmas geográficas obtenidas.")
        // La auditoría del terminal se escribe en el handover, cuando la celda todavía está
        // PENDING. La verificación llega después y cambia la etiqueta de la celda, pero nadie
        // volvía a escribir una línea: la consola se quedaba diciendo "100% —
        // entorno SEGURO" mientras el panel de arriba ya mostraba otra cifra. Desde aquí se cierra
        // el círculo con una línea que dice el resultado real.
        verificationCache[cacheKey] = VerificationStatus.VERIFIED
        // v2.1 — La distancia a la antena se muestra desde la coordenada cacheada, que se
        // refresca con el TTL de 60 s. Acabamos de recibirla aquí mismo, así que la ponemos ya:
        // sin esto, tras verificar una celda nueva habría hasta un minuto de espera para ver la
        // distancia, justo en el momento en el que la persona está mirando.
        apiLocationCache[cacheKey] = lat to lon
        dbHelper.updateVerificationStatus(
            cell.mnc,
            cell.tac,
            cell.cellId,
            VerificationStatus.VERIFIED,
            lat,
            lon,
            cell.mcc,
            cell.radioTech)
        
        // Una respuesta API cambia contexto, no constituye una nueva observación de radio.
        // No puede avanzar TemporalConfidence ni alcanzar tonos/alertas por una ruta lateral.
        // Se solicita una lectura real, que recorrerá el pipeline normal completo.
        scope.launch(Dispatchers.Main) {
            _cellFlow.value = _cellFlow.value.map { current ->
                if (current.identityKey == cell.identityKey) {
                    current.copy(verified = VerificationStatus.VERIFIED)
                } else {
                    current
                }
            }

            logVerificationOutcome(cell.copy(verified = VerificationStatus.VERIFIED))
            requestFreshCellInfo()
        }
    }

    // v2.1 — Marca del último registro de muestreo periódico (ver maybeLogPeriodicSample).
    private var lastPeriodicLogTime = 0L

    /**
     * MUESTREO PERIÓDICO DE LA CELDA SERVIDORA (v2.1).
     *
     * Hasta v2.0 el historial solo se escribía en el handover. Consecuencia medida en 59 días de
     * campo: MEDIANA DE 2 MUESTRAS POR CELDA, el 38 % de las celdas vistas una sola vez, y solo 6
     * celdas de 173 llegando a las 30 muestras que exige la huella RF — es decir, la huella RF
     * llevaba dos meses dormida y a ese ritmo lo habría seguido estando en marzo. Estar ocho horas
     * en casa camping en la misma celda producía CERO muestras.
     *
     * Esto registra una observación cada pocos minutos mientras sigues en la misma celda, que es
     * lo que de verdad alimenta los baselines (H13, huella RSRQ/SINR, reputación). Coste real:
     *  - Batería: ninguna apreciable. NO despierta el GPS (usa getLastKnownLocation, que es lo que
     *    ya haya) y NO fuerza lecturas de radio: se limita a persistir el análisis que el bucle
     *    acaba de hacer de todas formas.
     *  - Disco: ~150 filas/día, unas 9.000 en los 60 días de retención. Trivial para SQLite.
     *
     * La cadencia es más lenta con la pantalla apagada, coherente con el diseño de bajo consumo.
     */
    private fun maybeLogPeriodicSample(cell: CellData) {
        if (cell.cellId == "N/A") return
        if (cell.dbm == -999 || cell.dbm == Int.MAX_VALUE) return

        val now = System.currentTimeMillis()
        val interval = if (isScreenOn) PERIODIC_LOG_INTERVAL_SCREEN_ON else PERIODIC_LOG_INTERVAL_SCREEN_OFF
        if (now - lastPeriodicLogTime < interval) return
        lastPeriodicLogTime = now

        scope.launch(Dispatchers.IO) {
            val loc = getCurrentLocation()
            val rowId = dbHelper.logConnection(
                netType = cell.networkType,
                cid = cell.cellId,
                mnc = cell.mnc,
                tac = cell.tac,
                mcc = cell.mcc,
                dbm = cell.dbm,
                verified = cell.verified,
                score = cell.securityScore,
                failedHeuristics = cell.suspiciousReason ?: "OK",
                lat = loc?.latitude,
                lon = loc?.longitude,
                pci = cell.pci,
                arfcn = cell.arfcn,
                rsrq = cell.rsrq,
                sinr = cell.sinr,
                anomalyConfidence = cell.anomalyConfidence,
                timingAdvance = cell.timingAdvance,
                timingAdvanceUnit = cell.timingAdvanceUnit,
                radio = cell.radioTech
            )
            noteWriteResult(rowId)
        }
    }

    private fun checkAlerts(cell: CellData, confirmed: Boolean = false) {
        val cid = cell.cellId
        val dbm = cell.dbm
        val net = cell.networkType

        // Cierra en el terminal la auditoría de esta misma celda si la verificación ya ha
        // contestado desde que se escribió. No hace nada si nada ha cambiado.
        logVerificationOutcome(cell)

        if (cid != prevCid) {
            _dbmHistory.value = emptyList()
            // Reset del estado de latencia en el handover: el veredicto de la celda anterior
            // NO es válido para la nueva. Limpiamos "ANOMALA" (icono y heurística 12) y la
            // racha; la celda nueva volverá a "OK"/"ANOMALA" según sus propias mediciones una
            // vez aprenda su baseline. Evita que la heurística 12 se contamine con datos viejos.
            _networkLatencyState.value = idleLatencyState()
            latencyAnomalyStreak = 0
            lastAlarmLoggedCellId = null  // Fix #1: nuevo episodio de celda -> permitir registrar su alarma
            appendLog("[RADIO]", "Handover celular completado -> Nueva celda CID: $cid ($net)")
            // Esta celda queda pendiente de coordenadas frescas hasta que un fix las rellene.
            awaitingFreshCoords = true
            pendingCoordsSince = System.currentTimeMillis()
            // Cambio de celda: pedimos un fix GPS fresco SOLO con la pantalla encendida (donde
            // el stream ya está activo). Con pantalla apagada el GPS está pausado a propósito y
            // NO queremos despertarlo en cada handover rutinario yendo en el bolsillo; si la
            // celda fuera sospechosa, el disparador de sospecha (más abajo) lo fuerza igualmente.
            // En reposo, el reintento periódico del bucle se encarga mientras siga pendiente.
            // Debounce normal de 30 s.
            if (isScreenOn) requestHighAccuracyFix()
            generateAuditLog(cell) 

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

            // --- NUEVO: REGISTRO INMEDIATO DE LA NUEVA CELDA ---
            scope.launch(Dispatchers.IO) {
                val loc = getCurrentLocation()
                val rowId = dbHelper.logConnection(
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
                    arfcn = cell.arfcn,
                    rsrq = cell.rsrq,
                    sinr = cell.sinr,
                    anomalyConfidence = cell.anomalyConfidence,
                timingAdvance = cell.timingAdvance,
                timingAdvanceUnit = cell.timingAdvanceUnit,
                radio = cell.radioTech
                )
                noteWriteResult(rowId)
            }
            // --------------------------------------------------

            prevCid = cid
            // El registro del handover ya es una muestra: reinicia el reloj del muestreo periódico.
            lastPeriodicLogTime = System.currentTimeMillis()
        } else if (confirmed) {
            // Misma celda que en el ciclo anterior: muestreo periódico (ver más abajo). Solo en la
            // ruta del bucle principal (confirmed = true), nunca en la de re-análisis tras la API,
            // para no duplicar filas por el mismo instante.
            maybeLogPeriodicSample(cell)
        }

        if (dbm != -999 && dbm != Int.MAX_VALUE) {
            val currentHistory = _dbmHistory.value.toMutableList()
            currentHistory.add(dbm)
            if (currentHistory.size > 50) currentHistory.removeAt(0)
            _dbmHistory.value = currentHistory

            val rsrq = cell.rsrq
            if (rsrq != null && rsrq != Int.MAX_VALUE) {
                val currentRsrq = _rsrqHistory.value.toMutableList()
                currentRsrq.add(rsrq)
                if (currentRsrq.size > 50) currentRsrq.removeAt(0)
                _rsrqHistory.value = currentRsrq
            }

            // v2.1 — La serie geométrica guarda METROS, no el TA crudo.
            //
            // Antes se acumulaba el valor bruto, y un TA de 2 en LTE (156 m), en GSM (1.108 m) y en
            // NR (desconocido) acababan como tres puntos "2" en la misma curva, dibujados como si
            // fueran comparables bajo el rótulo "TELEMETRÍA GEOMÉTRICA". No lo son. Ahora solo
            // entran en la serie las observaciones cuya unidad admite una conversión defendible:
            // así todos los puntos están en la misma magnitud y la gráfica significa algo.
            val currentTa = cell.timingAdvance
            val taMeters = currentTa?.let { cell.timingAdvanceUnit.toMeters(it) }
            if (taMeters != null) {
                val currentGeo = _geoHistory.value.toMutableList()
                currentGeo.add(taMeters.toFloat())
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
            requestHighAccuracyFix()
            if (!cell.heuristicReport.pingPongPassed) {
                appendLog("[SEC]", "🚨 Efecto Ping-Pong confirmado por TemporalConfidence.")
            }

            // Fix #1: persistir la evidencia de la alarma en el historial. El registro normal
            // (más arriba) solo ocurre al CAMBIAR de celda, pero una amenaza se confirma en la
            // MISMA celda durante 3 ciclos sin cambio, así que la alarma quedaba sin rastro en el
            // CSV (la celda figuraba con su score 100 / OK inicial). Aquí grabamos la observación
            // que disparó la alarma con su score real y su motivo. Una sola vez por episodio
            // (mismo cellId, reseteado en cada handover) para no inundar la BD ciclo a ciclo.
            //
            // Coherencia (confirmed): SOLO se persiste si la alarma viene de la ruta que pasó por
            // TemporalConfidence.apply() (los 3 ciclos). Las respuestas API solo actualizan
            // contexto y nunca llaman directamente a esta ruta con una sospecha cruda.
            if (confirmed && lastAlarmLoggedCellId != cid) {
                lastAlarmLoggedCellId = cid
                scope.launch(Dispatchers.IO) {
                    val loc = getCurrentLocation()
                    val rowId = dbHelper.logConnection(
                        netType = net,
                        cid = cid,
                        mnc = cell.mnc,
                        tac = cell.tac,
                        mcc = cell.mcc,
                        dbm = dbm,
                        verified = cell.verified,
                        score = cell.securityScore,
                        failedHeuristics = cell.suspiciousReason ?: "ALARMA CONFIRMADA",
                        lat = loc?.latitude,
                        lon = loc?.longitude,
                        pci = cell.pci,
                        arfcn = cell.arfcn,
                        rsrq = cell.rsrq,
                        sinr = cell.sinr,
                        anomalyConfidence = cell.anomalyConfidence,
                timingAdvance = cell.timingAdvance,
                timingAdvanceUnit = cell.timingAdvanceUnit,
                radio = cell.radioTech
                    )
                    noteWriteResult(rowId)
                }
            }
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
        val vStatus = when (cell.verified) {
            VerificationStatus.VERIFIED -> "✅ VERIFICADA"
            // El aspa roja sobraba: que una base colaborativa no conozca una antena no es un fallo
            // de la antena ni un indicio de nada, y desde v2.1 tampoco resta puntos. La
            // notificación lo dice como lo que es, un dato de contexto.
            VerificationStatus.NOT_FOUND -> "➖ SIN REGISTRO"
            VerificationStatus.REJECTED -> "⚠️ RESPUESTA DESCARTADA"
            VerificationStatus.ERROR -> "⚠️ ERROR API"
            VerificationStatus.PENDING -> "⏳ PENDIENTE"
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
        val estadoCambio = collectionHealth.noteWrite(rowId, System.currentTimeMillis())
        if (rowId != -1L) {
            getSharedPreferences("miniic_prefs", MODE_PRIVATE).edit()
                .putLong(KEY_LAST_SUCCESSFUL_WRITE, collectionHealth.lastSuccessMs).apply()
        }
        if (estadoCambio) {
            if (collectionHealth.isFailing) {
                appendLog("[SYS]", "⚠ ESCRITURA FALLIDA: la base de datos rechaza las filas (¿disco lleno?). La recolección NO está guardando datos.")
                updateNotificationText("⚠ ESCRITURA FALLIDA — no se están guardando datos", force = true)
            } else {
                appendLog("[SYS]", "Escritura en base de datos restablecida.")
            }
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
        val visible = when {
            collectionHealth.isFailing -> "⚠ ESCRITURA FALLIDA — no se están guardando datos"
            now < interruptionNoticeUntil -> "$interruptionNoticeText · $text"
            else -> text
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(visible))
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
    private fun logVerificationOutcome(cell: CellData) {
        val ck = cell.identityKey
        if (ck != auditedCellKey || cell.verified == auditedVerified) return
        auditedVerified = cell.verified
        val detalle = when (cell.verified) {
            VerificationStatus.VERIFIED -> "registrada en bases públicas"
            VerificationStatus.NOT_FOUND ->
                "sin registro en WiGLE/OpenCellID. No resta puntos: esas bases están incompletas y " +
                "una antena nueva tarda meses en aparecer, así que su ausencia no dice nada de la antena."
            VerificationStatus.REJECTED ->
                "respuesta descartada: no corresponde a esta celda o no es creíble. No dice nada de la antena"
            VerificationStatus.ERROR -> "las bases públicas no han contestado"
            VerificationStatus.PENDING -> "pendiente de verificar"
        }
        val report = cell.heuristicReport
        appendLog(
            "[AUDIT]",
            "Celda ${cell.cellId} — índice heurístico: ${cell.securityScore}% " +
                "(${report.evaluatedCount}/${report.totalCount} reglas evaluadas; " +
                "${report.totalCount - report.evaluatedCount} sin datos), $detalle"
        )
    }

    private fun generateAuditLog(cell: CellData) {
        _auditStatus.value = "Auditoría en curso..."
        // La auditoría lleva la identidad de la celda: cuarenta líneas corridas de varias celdas
        // sin decir cuál es cuál son un registro forense inservible.
        auditedCellKey = cell.identityKey
        auditedVerified = cell.verified
        appendLog("[AUDIT]", "--- CICLO DE AUDITORÍA (15 REGLAS) · Celda ${cell.cellId} (${cell.mcc}-${cell.mnc}-${cell.tac}) ---")
        val report = cell.heuristicReport
        val results = mapOf(
            "1. Celda Aislada" to report.isolatedCell,
            "2. Estabilidad Potencia" to report.powerJump,
            "3. Consistencia MCC" to report.mccConsistency,
            "4. Límite MNC" to report.mncCount,
            "5. Validación Regional TAC" to report.tacDeviation,
            "6. Geometría (TA)" to report.taDistance,
            "7. Espectro Fantasma" to report.ghostNeighbors,
            "8. Sanidad ARFCN" to report.arfcnSanity,
            "9. Cifrado Hardware" to report.hardwareCiphering,
            "10. Anti Ping-Pong" to report.pingPong,
            "11. Consistencia Geográfica (Cell ID móvil)" to report.mobileCellId,
            "12. Correlación Latencia + RF" to report.latencyCorrelation,
            "13. Potencia vs Histórico (Baseline + huella RSRQ/SINR)" to report.signalBaseline,
            "14. Downgrade de Banda (Intra-LTE)" to report.bandDowngrade,
            "15. Estabilidad de Identidad RF (PCI)" to report.rfStability
        )

        results.forEach { (regla, result) ->
            val status = when (result) {
                HeuristicStatus.PASSED -> "PASSED"
                HeuristicStatus.FAILED -> "FAILED"
                HeuristicStatus.NOT_EVALUATED -> "N/A"
            }
            appendLog("[HEUR]", "$regla: $status")
        }

        // Diagnóstico del Timing Advance. Está aquí porque es la pregunta que no se podía
        // contestar desde fuera: "¿reporta TA este teléfono?". Ahora se ve en el terminal y se
        // exporta al CSV, en vez de deducirse de que una heurística no salte nunca.
        val ta = cell.timingAdvance
        if (ta == null) {
            appendLog("[TA]", "El módem no reporta Timing Advance en esta celda (H6 no juzga geometría).")
        } else if (cell.timingAdvanceUnit == TimingAdvanceUnit.STUB_ZERO) {
            appendLog("[TA]", "El módem devuelve 0 en todas las celdas (${taSanity.zeroOnlyCellCount} distintas comprobadas): no es una medida, es un campo sin rellenar. No se deduce distancia.")
        } else if (ta == 0 && cell.timingAdvanceUnit.isUsableForGeometry) {
            // Un TA de 0 no son "0 metros": es el escalón más bajo del contador, o sea "más cerca
            // que un paso". Decir "~0 m de la antena" prometía una precisión que el dato no tiene,
            // y encima es el mismo valor que devuelve un módem que no implementa TA — de ahí el
            // aviso: si se repite en celdas distintas, no es geometría, es un campo sin rellenar.
            val paso = cell.timingAdvanceUnit.toMeters(1)
            appendLog("[TA]", "TA=0 (${cell.timingAdvanceUnit.name}) → a menos de un paso de TA de la antena (< $paso m). No es una medida de 0 m: es el escalón mínimo. Si se repite en varias celdas distintas, el módem no reporta TA.")
        } else {
            val metros = cell.timingAdvanceUnit.toMeters(ta)
            if (metros != null) {
                appendLog("[TA]", "TA=$ta (${cell.timingAdvanceUnit.name}) → ~${metros} m de la antena.")
            } else {
                appendLog("[TA]", "TA=$ta (${cell.timingAdvanceUnit.name}) — sin conversión defendible: se registra pero NO se usa para geometría.")
            }
        }

        // El calificativo del resultado depende de si la verificación ya ha contestado. En el
        // handover casi nunca ha contestado —la consulta acaba de salir—, así que llamar a esto
        // "Resultado Global" y rematarlo con "validado como SEGURO" era afirmar como definitivo un
        // número que iba a cambiar en segundos. Es provisional, y ahora lo dice.
        val pendiente = cell.verified == VerificationStatus.PENDING
        val titulo = if (pendiente) "Resultado provisional (falta la verificación)" else "Resultado Global"
        appendLog(
            "[AUDIT]",
            "$titulo: índice heurístico ${cell.securityScore}% sobre " +
                "${report.evaluatedCount}/${report.totalCount} reglas evaluadas; " +
                "${report.totalCount - report.evaluatedCount} sin datos."
        )
        if (cell.isSuspicious) {
            appendLog("[SEC]", "🚨 CRÍTICO: Antena sospechosa detectada: ${cell.suspiciousReason}")
        } else if (cell.suspiciousReason != null) {
            // v2.1: la celda no alcanza el umbral de alarma pero SÍ ha fallado heurísticas.
            // Decir "SEGURO" aquí sería tan deshonesto como lo era guardar la fila como "OK".
            appendLog("[SYS]", "Sin alarma, pero con observaciones: ${cell.suspiciousReason}")
        } else if (pendiente) {
            appendLog("[SYS]", "${report.evaluatedCount}/${report.totalCount} reglas evaluadas sin fallos; ${report.totalCount - report.evaluatedCount} sin datos. Falta la respuesta de las bases públicas.")
        } else {
            appendLog("[SYS]", "✅ Sin anomalías en las reglas que pudieron evaluarse.")
        }
        _auditStatus.value = "Auditoría completada"
    }

    // HONESTIDAD TÉCNICA — detección de cifrado nulo (A5/0) e identificadores (IMSI) NO implementada (pendiente de hardware y API).
    //
    // Versiones anteriores tenían aquí dos métodos (onCipheringStatusChanged / onCellularIdentifierDisclosure)
    // con nombres inventados que se asumía que Android invocaría "por reflexión". Eso era un malentendido:
    // registerTelephonyCallback despacha eventos SOLO a los métodos de las interfaces de listener que la clase
    // implementa, con sus firmas exactas — nunca por nombre. Aquellos métodos eran código muerto: jamás se
    // ejecutaban, así que no detectaban nada. Se han eliminado para no aparentar una capacidad que no existe.
    //
    // La detección REAL requiere las APIs de Android 16 (API 36):
    //   - TelephonyCallback.SecurityAlgorithmsListener        -> onSecurityAlgorithmsChanged(...)
    //   - TelephonyCallback.CellularIdentifierDisclosedListener -> onCellularIdentifierDisclosed(...)
    // sujetas a permisos y a que el modem/HAL del dispositivo reporte esos eventos. Implementarlas bien
    // requiere prueba en hardware Android 16+ y queda para una versión futura.
    //
    // Hasta entonces: isHardwareCipheringAvailable permanece en false, ThreatAnalyzer omite ese factor y
    // NO se genera ninguna alarma por cifrado/IMSI (falla de forma segura, sin falsos positivos).
    @RequiresApi(Build.VERSION_CODES.S)
    class ImsiCatcherSecurityCallback : TelephonyCallback(), TelephonyCallback.CellInfoListener {
        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {}
    }

    companion object {
        const val CHANNEL_ID = "miniic_channel"
        const val NOTIFICATION_ID = 202
        const val LATENCY_NOTIFICATION_ID = 3  // distinto al NOTIFICATION_ID principal
        const val ACTION_STOP = "com.alexisgordr.icdetector.STOP"
        // Cap de seguridad para los mapas en memoria indexados por celda: evita crecimiento
        // ilimitado en sesiones muy largas o viajes con miles de celdas distintas.
        const val MAX_TRACKED_CELLS = 500
        // Persistencia de la referencia de plausibilidad GPS entre reinicios (cierra el hueco
        // de arranque de isPlausibleFix, donde el primer fix se aceptaba sin comparar).
        private const val KEY_LAST_LOC = "last_accepted_loc"
        private const val KEY_WIGLE_RATE_LIMITED_UNTIL = "wigle_rate_limited_until"
        private const val KEY_LAST_SUCCESSFUL_WRITE = "last_successful_write_ms"
        /** Tiempo que la notificación sigue recordando que hubo un hueco en la recolección. */
        private const val INTERRUPTION_NOTICE_MS = 10L * 60_000L
        private const val WIGLE_RATE_LIMIT_FALLBACK_MS = 24L * 60L * 60L * 1000L
        // Evita que un Retry-After anormalmente corto reactive un bucle de cuota por celda.
        private const val WIGLE_RATE_LIMIT_MINIMUM_MS = 60L * 60L * 1000L
        // Si la referencia persistida es más vieja que esto (p. ej. viaje largo con la app
        // cerrada), se ignora y el próximo fix se trata como bootstrap, evitando falsos rechazos.
        private const val LOCATION_REFERENCE_MAX_AGE = 6L * 60 * 60 * 1000  // 6 h
        // v2.1 — Una coordenada de API que ya consta en este número de ÁREAS DE SEGUIMIENTO
        // distintas (MCC-MNC-TAC), ajenas a la de la celda consultada, es un valor por defecto de
        // la API y no la posición de una antena. Se cuentan áreas y no celdas porque los sectores
        // y bandas de un mismo mástil comparten coordenada de forma legítima: contarlos hacía que
        // una antena normal dejara de verificarse según crecía el historial.
        private const val API_SENTINEL_MIN_AREAS = 3
        // v2.1 — Cadencia del muestreo periódico de la celda servidora (ver maybeLogPeriodicSample).
        // Más lenta con la pantalla apagada, coherente con el diseño de bajo consumo.
        private const val PERIODIC_LOG_INTERVAL_SCREEN_ON = 5L * 60 * 1000    // 5 min
        private const val PERIODIC_LOG_INTERVAL_SCREEN_OFF = 15L * 60 * 1000  // 15 min
        // Ciclos consecutivos de sospecha necesarios para confirmar una alarma.
        private const val CONFIRMATION_CYCLES = 3
    }
}
