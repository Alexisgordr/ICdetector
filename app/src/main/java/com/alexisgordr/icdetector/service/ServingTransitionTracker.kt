package com.alexisgordr.icdetector.service

import android.location.Location
import android.os.SystemClock
import com.alexisgordr.icdetector.core.TransitionCoherence
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.TransitionCoherenceResult
import com.alexisgordr.icdetector.models.identityKey
import com.alexisgordr.icdetector.storage.CellDbHelper

/** Owns handover history and evaluates geographical coherence between serving cells. */
internal class ServingTransitionTracker(
    private val db: CellDbHelper,
    private val log: (String) -> Unit
) {
    private data class Snapshot(
        val cell: CellData,
        val location: Location?,
        val elapsedMs: Long,
        val neighbors: Set<String>
    )

    private val lock = Any()
    private var last: Snapshot? = null
    private var activeResult = TransitionCoherenceResult()
    private var activeIdentity: String? = null
    private var activeExpiresAt = 0L

    fun evaluate(active: CellData, location: Location?, neighbors: List<CellData>): TransitionCoherenceResult {
        val now = SystemClock.elapsedRealtime()
        val current = Snapshot(active, location?.let(::Location), now, neighbors.map { it.identityKey }.toSet())
        val prior = synchronized(lock) {
            val previous = last
            last = current
            if (previous == null) return TransitionCoherenceResult()
            if (previous.cell.identityKey == active.identityKey) {
                return if (activeIdentity == active.identityKey && now <= activeExpiresAt) activeResult
                else TransitionCoherenceResult(explanation = "N/A: esperando el siguiente handover para comprobar la movilidad.")
            }
            activeIdentity = null
            activeResult = TransitionCoherenceResult()
            previous
        }
        val previousLocation = prior.location
        val result = TransitionCoherence.evaluate(
            TransitionCoherence.Input(
                fromIdentity = prior.cell.identityKey,
                toIdentity = active.identityKey,
                elapsedSeconds = ((now - prior.elapsedMs) / 1_000L).coerceAtLeast(0L),
                deviceDistanceMeters = if (previousLocation != null && location != null) previousLocation.distanceTo(location).toDouble() else null,
                previousAccuracyMeters = previousLocation?.accuracy,
                currentAccuracyMeters = location?.accuracy,
                fromSamples = db.getCellLocationSamples(prior.cell),
                toSamples = db.getCellLocationSamples(active),
                priorTrustedTransitions = db.getTrustedTransitionCount(prior.cell.identityKey, active.identityKey),
                destinationWasNeighbor = active.identityKey in prior.neighbors
            )
        )
        db.recordCellTransition(result)
        synchronized(lock) {
            activeIdentity = active.identityKey
            activeResult = result
            activeExpiresAt = now + RESULT_TTL_MS
        }
        log(when (result.status) {
            HeuristicStatus.PASSED -> "Transición coherente: ${result.explanation}"
            HeuristicStatus.FAILED -> "Transición incoherente: ${result.explanation}"
            HeuristicStatus.NOT_EVALUATED -> result.explanation
        })
        return result
    }

    private companion object { const val RESULT_TTL_MS = 20_000L }
}
