package com.alexisgordr.icdetector.forensics

import android.location.Location
import android.os.Build
import android.os.SystemClock
import com.alexisgordr.icdetector.models.*
import com.alexisgordr.icdetector.storage.CellDbHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/** Observador pasivo: copia ciclos del detector, pero nunca participa en su decisión. */
class ForensicRecorder(private val db: CellDbHelper) {
    companion object {
        const val PRE_WINDOW_MS = 60_000L
        const val POST_WINDOW_MS = 60_000L
        const val MAX_CASE_MS = 30L * 60_000L
        const val MAX_BUFFER_SAMPLES = 180
    }

    private data class Buffered(val wall: Long, val elapsed: Long, val event: String, val json: String)
    private val mutex = Mutex()
    private val buffer = ArrayDeque<Buffered>()
    private var caseId: Long? = null
    private var caseStartedElapsed = 0L
    private var postCaptureUntil = 0L
    private var lastPhase = 0

    suspend fun observe(
        active: CellData,
        neighbors: List<CellData>,
        location: Location?,
        latencyState: String,
        logs: List<String>
    ) = mutex.withLock {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val phase = active.temporalProgress.phase
        val event = when {
            phase > 0 && lastPhase == 0 -> "ANOMALY_STARTED"
            phase > lastPhase -> if (phase >= active.temporalProgress.required) "CONFIRMED" else "PHASE_CHANGED"
            phase == 0 && lastPhase > 0 -> "RECOVERY_STARTED"
            else -> "OBSERVATION"
        }
        val payload = payload(active, neighbors, location, latencyState, logs)
        val sample = Buffered(wall, elapsed, event, payload)
        buffer.addLast(sample)
        while (buffer.size > MAX_BUFFER_SAMPLES || (buffer.isNotEmpty() && wall - buffer.peekFirst().wall > PRE_WINDOW_MS)) {
            buffer.removeFirst()
        }

        if (phase > 0 && caseId == null) {
            val newCaseId = db.createForensicCase(active)
            caseId = newCaseId
            caseStartedElapsed = elapsed
            buffer.forEach { db.insertForensicSample(newCaseId, it.wall, it.elapsed, it.event, it.json) }
        } else {
            caseId?.let { db.insertForensicSample(it, wall, elapsed, event, payload) }
        }

        val activeCaseId = caseId
        if (activeCaseId != null) {
            db.updateForensicCaseProgress(activeCaseId, active)
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
                postCaptureUntil = 0L
                caseStartedElapsed = 0L
            }
        }
        lastPhase = phase
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
