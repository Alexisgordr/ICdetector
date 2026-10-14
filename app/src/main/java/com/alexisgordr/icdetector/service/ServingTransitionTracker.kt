package com.alexisgordr.icdetector.service

import android.location.Location
import android.os.SystemClock
import com.alexisgordr.icdetector.core.HandoverReference
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
    /**
     * [elapsedMs]: SystemClock.elapsedRealtime de la instantánea. [location] es la posición aceptada
     * que había (puede tener hasta 120 s o repetirse entre instantáneas); su propio instante
     * monotónico (Location.elapsedRealtimeNanos) se valida en TransitionCoherence.gpsProblem.
     */
    private data class Snapshot(
        val cell: CellData,
        val location: Location?,
        val elapsedMs: Long,
        val neighbors: Set<String>
    ) {
        val endpoint get() = TransitionCoherence.Endpoint(elapsedMs, location?.elapsedRealtimeNanos)
    }

    private val lock = Any()
    private var last: Snapshot? = null
    private var activeResult = TransitionCoherenceResult()
    private var activeIdentity: String? = null
    private var activeExpiresAt = 0L

    /**
     * [serviceContinuous] (G4): false si desde la observación anterior hubo una pérdida de servicio
     * o un hueco (CoverageContinuity). Entonces la celda es la nueva referencia: no se evalúa ni se
     * registra un handover desde la anterior (ver HandoverReference).
     */
    fun evaluate(active: CellData, location: Location?, neighbors: List<CellData>, serviceContinuous: Boolean = true): TransitionCoherenceResult {
        val now = SystemClock.elapsedRealtime()
        val current = Snapshot(active, location?.let(::Location), now, neighbors.map { it.identityKey }.toSet())
        val prior = synchronized(lock) {
            val previous = last
            last = current
            when (HandoverReference.step(previous?.cell?.identityKey, active.identityKey, serviceContinuous)) {
                HandoverReference.Step.FIRST -> return TransitionCoherenceResult()
                HandoverReference.Step.NEW_REFERENCE_AFTER_INTERRUPTION -> {
                    activeIdentity = null
                    activeResult = TransitionCoherenceResult()
                    return TransitionCoherenceResult(explanation = HandoverReference.INTERRUPTION_EXPLANATION)
                }
                HandoverReference.Step.SAME_CELL ->
                    return if (activeIdentity == active.identityKey && now <= activeExpiresAt) activeResult
                    else TransitionCoherenceResult(explanation = "N/A: sin handover reciente; esperando el siguiente para comprobar la movilidad.")
                HandoverReference.Step.HANDOVER -> Unit
            }
            activeIdentity = null
            activeResult = TransitionCoherenceResult()
            previous!!
        }
        val previousLocation = prior.location
        val result = TransitionCoherence.evaluate(
            TransitionCoherence.Input(
                fromIdentity = prior.cell.identityKey,
                toIdentity = active.identityKey,
                elapsedSeconds = ((now - prior.elapsedMs) / 1_000L).coerceAtLeast(0L),
                previous = prior.endpoint,
                current = current.endpoint,
                deviceDistanceMeters = if (previousLocation != null && location != null) previousLocation.distanceTo(location).toDouble() else null,
                currentPosition = location?.let { com.alexisgordr.icdetector.models.CellLocationSample(it.latitude, it.longitude) },
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
