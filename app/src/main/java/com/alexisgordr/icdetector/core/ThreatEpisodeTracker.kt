package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.identityKey

/**
 * Correlates weak evidence across fresh observations without changing any individual heuristic.
 *
 * A single RF oddity remains sub-threshold. What the implementation does:
 *  - Evidence is the set of rule families that FAILED in a fresh modem delivery (a repeated token
 *    is not new evidence). It is kept for [windowMs] (90 s) and only for the same serving identity.
 *  - A promotion STARTS or is RENEWED only when the current observation adds new evidence and,
 *    together with the window, there are at least two different families in at least two
 *    distinct observations.
 *  - Once started, the promotion is HELD for up to [promotionHoldMs] (30 s) even if the current
 *    observation has no failures at all; a handover to another identity ends it at once.
 *  - A promoted cell is only marked suspicious here. It still goes through [TemporalConfidence]
 *    (consecutive phases) before any alert. A held cycle without new evidence
 *    ([Evaluation.heldWithoutEvidence]) neither advances nor breaks that streak: the hold bridges
 *    normal gaps, it does not supply the phases. An episode alarm needs three promoted
 *    observations that each carry fresh evidence (of any family).
 *
 * Different families are not guaranteed to be independent evidence: they are a grouping of rules,
 * and two of them can share inputs and causes (the GPS fix, the cell's stored history, one PCI or
 * channel change). Two families mean two different rules failed, not two independent facts. See
 * MANUAL, *Shared evidence*.
 */
class ThreatEpisodeTracker(
    private val windowMs: Long = 90_000L,
    private val promotionHoldMs: Long = 30_000L,
    /**
     * Monotonic clock in ms. The service injects SystemClock.elapsedRealtime, which keeps counting in
     * deep sleep, so both windows expire in real time. The default (System.nanoTime) stops in deep
     * sleep on Android; it stays only as a portable default, and tests inject a fake clock.
     */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    enum class Family { RF_DOMINANCE, IDENTITY, MOBILITY, CROSS_LAYER }

    data class Evaluation(
        val cell: CellData,
        val watching: Boolean,
        val startedWatching: Boolean,
        val promoted: Boolean,
        val families: Set<Family>,
        /**
         * Promoted only by the hold: this observation added no fresh evidence. [TemporalConfidence]
         * keeps the streak as it is (neither +1 nor reset).
         */
        val heldWithoutEvidence: Boolean = false
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

        // Once new evidence from two families has opened (or renewed) an episode, bridge short
        // normal gaps: for up to 30 s the cell stays promoted even when the current observation
        // has no failures, so a clean cycle does not break the confirmation streak. Those held
        // cycles are marked [Evaluation.heldWithoutEvidence] and do not advance it either: before,
        // two sub-threshold failures followed by two clean cycles confirmed an alarm in 15 s. The
        // bridge is deliberately much shorter than the 90 s correlation window.
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
                ).joinToString(com.alexisgordr.icdetector.models.StoredReasons.SEPARATOR)
            )
        } else cell

        return Evaluation(
            promotedCell, watching, started, promote, accumulatedFamilies,
            heldWithoutEvidence = promote && !acceptedEvidence
        )
    }

    /**
     * 3.0 (#20) — Pérdida de señal: la evidencia de antes del hueco no se correlaciona con la de
     * después, aunque siga dentro de la ventana de 90 s.
     */
    fun interrupt() = reset()

    fun reset() {
        evidence.clear()
        lastAcceptedToken = null
        promotedIdentity = null
        promotedUntilMs = 0L
    }

    // 3.0 (#7, O2) — Las familias salen de la lista canónica de reglas (HeuristicCatalog).
    private fun familiesOf(cell: CellData): Set<Family> =
        HeuristicCatalog.values()
            .filter { it.status(cell.heuristicReport) == HeuristicStatus.FAILED }
            .mapTo(linkedSetOf()) { it.episodeFamily }

    private companion object {
        const val EPISODE_ALARM_SCORE = 69
    }
}
