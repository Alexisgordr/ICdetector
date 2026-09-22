package com.alexisgordr.icdetector.forensics

import android.location.Location
import android.os.Build
import android.os.SystemClock
import com.alexisgordr.icdetector.core.CollectionHealth
import com.alexisgordr.icdetector.core.ForensicCasePolicy
import com.alexisgordr.icdetector.models.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

interface ForensicStore {
    fun createForensicCase(cell: CellData, origin: ForensicCaseOrigin): Long

    /**
     * Devuelve el rowId insertado, o **-1** si la escritura falló.
     *
     * v2.8.0 — Antes no devolvía nada. `SQLiteDatabase.insert()` no lanza: captura el disco lleno,
     * el bloqueo y la violación de clave ajena, y devuelve -1. Sin valor de retorno, una captura
     * forense podía no guardar ni una muestra sin que nadie se enterara — el mismo fallo silencioso
     * que [CollectionHealth] arregló para el historial.
     */
    fun insertForensicSample(caseId: Long, wall: Long, elapsed: Long, event: String, json: String): Long

    fun updateForensicCaseProgress(caseId: Long, cell: CellData)
    fun setForensicCaseState(caseId: Long, state: ForensicCaseState)
    fun finishForensicCase(caseId: Long, state: ForensicCaseState)
    fun promoteForensicCase(caseId: Long)

    /**
     * ¿Existe ya algún caso forense con al menos una muestra para [identity], creado a partir de
     * [sinceWallMs]?
     *
     * v2.8.0 — Deduplicación persistente de [TrustContradictionSignal.ON_START]. El rastreador en
     * memoria se vacía en cada arranque, así que sin una pregunta a disco un servicio que se
     * reinicia con frecuencia abriría un caso por arranque para la misma celda.
     */
    fun hasRecentForensicCaseFor(identity: String, sinceWallMs: Long): Boolean
}

/** Observador pasivo: copia ciclos del detector, pero nunca participa en su decisión. */
class ForensicRecorder(
    private val db: ForensicStore,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    /** Se invoca solo cuando el estado visible cambia: true = escritura forense rota, false = recuperada. */
    private val onStorageHealthChange: (Boolean) -> Unit = {}
) {
    companion object {
        const val PRE_WINDOW_MS = 60_000L
        const val POST_WINDOW_MS = 60_000L
        const val MAX_CASE_MS = 30L * 60_000L
        const val MAX_BUFFER_SAMPLES = 180

        /**
         * Ventana de deduplicación de los casos abiertos por [TrustContradictionSignal.ON_START].
         *
         * Una celda cuyo perfil guardado quedó contradicho lo sigue estando en el arranque
         * siguiente, y en el siguiente. Un caso al día por identidad documenta el hecho sin
         * convertir un reinicio en una avalancha de paquetes idénticos de 6-10 KB por muestra.
         */
        const val ON_START_DEDUP_MS = 24L * 60 * 60 * 1000
    }

    private data class Buffered(val wall: Long, val elapsed: Long, val event: String, val json: String)
    private val mutex = Mutex()
    /** Misma lógica que la salud del historial: 3 fallos seguidos antes de declararlo roto. */
    private val storageHealth = CollectionHealth()

    /** True cuando la escritura de muestras forenses lleva fallando lo suficiente como para avisar. */
    val isStorageFailing: Boolean get() = storageHealth.isFailing
    private val buffer = ArrayDeque<Buffered>()
    private var caseId: Long? = null
    private var caseStartedElapsed = 0L
    private var postCaptureUntil = 0L
    private var lastPhase = 0
    private var activeOrigin: ForensicCaseOrigin? = null
    // v2.3.3 — Identidad cuyo caso se cerró por tiempo agotado. Ver ForensicCasePolicy.
    private var lockedIdentity: String? = null

    suspend fun observe(
        active: CellData,
        neighbors: List<CellData>,
        location: Location?,
        latencyState: String,
        logs: List<String>,
        trustContradictionTransition: TrustContradictionSignal = TrustContradictionSignal.NONE
    ) = mutex.withLock {
        val wall = wallClock()
        val elapsed = elapsedClock()
        val phase = active.temporalProgress.phase
        val contradictionEvent = when (trustContradictionTransition) {
            TrustContradictionSignal.TRANSITION -> "TRUST_CONTRADICTION"
            TrustContradictionSignal.ON_START -> "TRUST_CONTRADICTION_ON_START"
            TrustContradictionSignal.SITE_NOVELTY -> "STABLE_SITE_NEW_SERVING"
            TrustContradictionSignal.NONE -> null
        }
        val event = when {
            contradictionEvent != null && phase > 0 && lastPhase == 0 -> "${contradictionEvent}_AND_ANOMALY_STARTED"
            contradictionEvent != null -> contradictionEvent
            phase > 0 && lastPhase == 0 -> "ANOMALY_STARTED"
            phase > lastPhase -> if (phase >= active.temporalProgress.required) "CONFIRMED" else "PHASE_CHANGED"
            phase == 0 && lastPhase > 0 -> "RECOVERY_STARTED"
            else -> "OBSERVATION"
        }
        val payload = payload(active, neighbors, location, latencyState, logs)
        val sample = Buffered(wall, elapsed, event, payload)
        buffer.addLast(sample)
        // elapsedRealtime es monotónico: un ajuste manual/NTP de la hora no vacía ni congela el
        // prebuffer. El acceso seguro elimina además el aviso nullable de peekFirst() en Kotlin.
        while (
            buffer.size > MAX_BUFFER_SAMPLES ||
            (buffer.peekFirst()?.let { elapsed - it.elapsed > PRE_WINDOW_MS } == true)
        ) {
            buffer.removeFirst()
        }

        val normalCaseRequested = ForensicCasePolicy.shouldOpenCase(
            phase, caseId != null, lockedIdentity, active.identityKey
        )
        // TRANSITION es un flanco observado en vivo: solo puede ocurrir una vez por cambio de
        // estado, así que no necesita más guarda. ON_START se repite en cada arranque mientras la
        // celda siga contradicha, y por eso pregunta a disco antes de abrir nada.
        val silentCaseRequested = !normalCaseRequested && caseId == null &&
            when (trustContradictionTransition) {
                TrustContradictionSignal.TRANSITION -> true
                TrustContradictionSignal.ON_START ->
                    !db.hasRecentForensicCaseFor(active.identityKey, wall - ON_START_DEDUP_MS)
                TrustContradictionSignal.SITE_NOVELTY ->
                    !db.hasRecentForensicCaseFor(active.identityKey, wall - ON_START_DEDUP_MS)
                TrustContradictionSignal.NONE -> false
            }
        if (normalCaseRequested || silentCaseRequested) {
            val origin = if (normalCaseRequested) ForensicCaseOrigin.ALARM else ForensicCaseOrigin.TRUST_CONTRADICTION
            val newCaseId = db.createForensicCase(active, origin)
            caseId = newCaseId
            activeOrigin = origin
            caseStartedElapsed = elapsed
            buffer.forEach {
                noteWrite(db.insertForensicSample(newCaseId, it.wall, it.elapsed, it.event, it.json), wall)
            }
            if (origin == ForensicCaseOrigin.TRUST_CONTRADICTION) {
                postCaptureUntil = elapsed + POST_WINDOW_MS
                db.setForensicCaseState(newCaseId, ForensicCaseState.POST_CAPTURE)
            }
        } else {
            caseId?.let { noteWrite(db.insertForensicSample(it, wall, elapsed, event, payload), wall) }
        }

        var closedByTimeout = false
        val activeCaseId = caseId
        if (activeCaseId != null) {
            db.updateForensicCaseProgress(activeCaseId, active)
            if (phase > 0 && activeOrigin == ForensicCaseOrigin.TRUST_CONTRADICTION) {
                // The normal detector has now started a real episode. Reuse and promote the
                // observation case so its prebuffer is preserved without creating a duplicate.
                db.promoteForensicCase(activeCaseId)
                activeOrigin = ForensicCaseOrigin.ALARM
                postCaptureUntil = 0L
                db.setForensicCaseState(activeCaseId, ForensicCaseState.CAPTURING)
            }
            if (phase > 0 && postCaptureUntil > 0L) {
                // Una recaída dentro de la ventana posterior pertenece al mismo episodio.
                postCaptureUntil = 0L
                db.setForensicCaseState(activeCaseId, ForensicCaseState.CAPTURING)
            }
            if (phase == 0 && lastPhase > 0) {
                postCaptureUntil = elapsed + POST_WINDOW_MS
                db.setForensicCaseState(activeCaseId, ForensicCaseState.POST_CAPTURE)
            }
            val timedOut = elapsed - caseStartedElapsed >= MAX_CASE_MS
            val postComplete = postCaptureUntil > 0L && elapsed >= postCaptureUntil
            if (timedOut || postComplete) {
                db.finishForensicCase(activeCaseId, if (timedOut) ForensicCaseState.INTERRUPTED else ForensicCaseState.READY)
                caseId = null
                activeOrigin = null
                postCaptureUntil = 0L
                caseStartedElapsed = 0L
                closedByTimeout = timedOut
            }
        }
        lockedIdentity = ForensicCasePolicy.nextLock(lockedIdentity, active.identityKey, phase, closedByTimeout)
        lastPhase = phase
    }

    /**
     * Contabiliza el resultado de un `insert`. -1 es un fallo; tres seguidos declaran rota la
     * escritura forense y lo notifican una sola vez, no en cada ciclo.
     */
    private fun noteWrite(rowId: Long, wall: Long) {
        if (storageHealth.noteWrite(rowId, wall)) onStorageHealthChange(storageHealth.isFailing)
    }

    private fun payload(active: CellData, neighbors: List<CellData>, loc: Location?, latency: String, logs: List<String>): String {
        fun cellJson(c: CellData) = JSONObject().apply {
            put("registered", c.isRegistered); put("identity", c.identityKey)
            put("mcc", c.mcc); put("mnc", c.mnc); put("tac", c.tac); put("cid", c.cellId)
            put("radio", c.radioTech.name); put("networkType", c.networkType); put("dbm", c.dbm)
            put("pci", c.pci ?: JSONObject.NULL); put("arfcn", c.arfcn ?: JSONObject.NULL)
            put("band", c.band ?: JSONObject.NULL); put("rsrq", c.rsrq ?: JSONObject.NULL)
            put("sinr", c.sinr ?: JSONObject.NULL); put("ta", c.timingAdvance ?: JSONObject.NULL)
            put("taUnit", c.timingAdvanceUnit.name)
        }
        return JSONObject().apply {
            put("schemaVersion", 1); put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", Build.VERSION.RELEASE); put("serving", cellJson(active))
            put("neighbors", JSONArray().apply { neighbors.forEach { put(cellJson(it)) } })
            put("gps", if (loc == null) JSONObject.NULL else JSONObject().apply {
                put("lat", loc.latitude); put("lon", loc.longitude); put("accuracyM", loc.accuracy)
            })
            put("latencyState", latency); put("phase", active.temporalProgress.phase)
            put("requiredPhases", active.temporalProgress.required); put("score", active.securityScore)
            put("anomalyConfidence", active.anomalyConfidence); put("verified", active.verified.name)
            put("reason", active.suspiciousReason ?: "")
            put("localTrust", JSONObject().apply {
                put("state", active.localCellTrust.state.name)
                put("confidencePercent", active.localCellTrust.confidencePercent)
                put("contradictions", JSONArray(active.localCellTrust.contradictions.toList()))
            })
            put("stableSite", JSONObject().apply {
                val s = active.stableSiteDecision
                put("siteKey", s.siteKey ?: JSONObject.NULL); put("featureState", s.featureState.name)
                put("novelty", s.novelty.name); put("wouldTrigger", s.wouldTrigger)
                put("enforced", s.enforced); put("reason", s.reason)
                put("staticDurationSeconds", s.evidence?.motion?.durationSeconds ?: 0)
                put("locationAccuracyM", s.evidence?.motion?.accuracyM ?: JSONObject.NULL)
                put("servingDays", s.evidence?.currentServingDays ?: 0)
                put("neighbourDays", s.evidence?.currentNeighbourDays ?: 0)
            })
            put("heuristics", active.heuristicReport.snapshot())
            put("diagnostics", JSONArray().apply { active.heuristicDiagnostics.forEach { d ->
                put(JSONObject().apply { put("id", d.id); put("name", d.name); put("status", d.status.name); put("explanation", d.explanation) })
            } })
            put("capabilities", JSONObject().apply {
                put("timingAdvance", active.timingAdvance != null); put("rsrq", active.rsrq != null)
                put("sinr", active.sinr != null); put("arfcn", active.arfcn != null); put("gps", loc != null)
                put("hardwareCiphering", active.heuristicReport.hardwareCipheringAvailable)
            })
            put("logs", JSONArray(logs.takeLast(40)))
        }.toString()
    }
}
