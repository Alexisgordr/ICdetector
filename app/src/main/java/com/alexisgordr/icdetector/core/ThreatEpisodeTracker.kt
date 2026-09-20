package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.identityKey

/**
 * Correlates weak evidence across fresh observations without changing any individual heuristic.
 *
 * A single RF oddity remains sub-threshold. Promotion requires evidence from at least two
 * independent physical families, seen in at least two fresh modem deliveries inside a short
 * window, and the current observation must still contain evidence. This closes the gap between
 * isolated per-sample scoring and a short-lived attack sequence while preserving the existing
 * temporal confirmation performed by [TemporalConfidence].
 */
class ThreatEpisodeTracker(
    private val windowMs: Long = 90_000L,
    private val promotionHoldMs: Long = 30_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    enum class Family { RF_DOMINANCE, IDENTITY, MOBILITY, CROSS_LAYER }

    data class Evaluation(
        val cell: CellData,
        val watching: Boolean,
        val startedWatching: Boolean,
        val promoted: Boolean,
        val families: Set<Family>
    )

    private data class Evidence(
        val atMs: Long,
        val identity: String,
        val token: Long?,
        val families: Set<Family>
    )

    private val evidence = ArrayDeque<Evidence>()
    private var lastAcceptedToken: Long? = null
    private var promotedIdentity: String? = null
    private var promotedUntilMs = 0L

    fun apply(
        cell: CellData,
        observationToken: Long? = null,
        isFreshDelivery: Boolean = true
    ): Evaluation {
        val now = nowMs()
        while (evidence.firstOrNull()?.let { now - it.atMs > windowMs } == true) {
            evidence.removeFirst()
        }
        val hadLiveEvidence = evidence.isNotEmpty()

        val currentFamilies = familiesOf(cell)
        val tokenIsNew = observationToken == null || observationToken != lastAcceptedToken
        var acceptedEvidence = false
        if (isFreshDelivery && tokenIsNew) {
            if (observationToken != null) lastAcceptedToken = observationToken
            if (currentFamilies.isNotEmpty()) {
                evidence.addLast(Evidence(now, cell.identityKey, observationToken, currentFamilies))
                acceptedEvidence = true
            }
        }

        // A handover ends the promotion immediately. Evidence remains in the correlation window
        // for diagnostics, but it can never accuse a different serving identity.
        if (promotedIdentity != null && promotedIdentity != cell.identityKey) {
            promotedIdentity = null
            promotedUntilMs = 0L
        }

        val relevant = evidence.filter { it.identity == cell.identityKey }
        val accumulatedFamilies = relevant.flatMapTo(linkedSetOf()) { it.families }
        val distinctObservations = relevant.map { it.token ?: it.atMs }.distinct().size
        val watching = evidence.isNotEmpty()
        val started = watching && !hadLiveEvidence

        val gateReached = accumulatedFamilies.size >= 2 && distinctObservations >= 2
        if (acceptedEvidence && gateReached) {
            promotedIdentity = cell.identityKey
            promotedUntilMs = now + promotionHoldMs
        }
        if (now > promotedUntilMs) {
            promotedIdentity = null
            promotedUntilMs = 0L
        }

        // Once independent evidence has opened an episode, bridge short normal gaps so that the
        // existing consecutive-cycle confirmation can observe the episode as a continuous state.
        // The bridge is deliberately much shorter than the 90 s correlation window.
        val promote = !cell.isSuspicious && promotedIdentity == cell.identityKey &&
            now <= promotedUntilMs
        val promotedCell = if (promote) {
            val names = accumulatedFamilies.joinToString("+") { it.name }
            cell.copy(
                isSuspicious = true,
                securityScore = minOf(cell.securityScore, EPISODE_ALARM_SCORE),
                suspiciousReason = listOfNotNull(
                    cell.suspiciousReason,
                    "Episodio multiseñal ($names)"
                ).joinToString(" | ")
            )
        } else cell

        return Evaluation(promotedCell, watching, started, promote, accumulatedFamilies)
    }

    fun reset() {
        evidence.clear()
        lastAcceptedToken = null
        promotedIdentity = null
        promotedUntilMs = 0L
    }

    private fun familiesOf(cell: CellData): Set<Family> = buildSet {
        val h = cell.heuristicReport
        fun failed(vararg values: HeuristicStatus) = values.any { it == HeuristicStatus.FAILED }

        if (failed(h.isolatedCell, h.powerJump, h.ghostNeighbors, h.signalBaseline)) {
            add(Family.RF_DOMINANCE)
        }
        if (failed(h.mccConsistency, h.mncCount, h.tacDeviation, h.arfcnSanity, h.rfStability)) {
            add(Family.IDENTITY)
        }
        if (failed(h.taDistance, h.pingPong, h.mobileCellId, h.bandDowngrade, h.transitionCoherence)) {
            add(Family.MOBILITY)
        }
        if (failed(h.hardwareCiphering, h.latencyCorrelation)) {
            add(Family.CROSS_LAYER)
        }
    }

    private companion object {
        const val EPISODE_ALARM_SCORE = 69
    }
}
