package com.alexisgordr.icdetector.core

/**
 * Rejects one-off empty-neighbour modem snapshots before they reach H1.
 *
 * Empty neighbour lists are common transient radio snapshots. H1 becomes evidence only after
 * [requiredFreshObservations] consecutive fresh deliveries for the same serving identity. Any
 * contradictory snapshot or handover clears the streak immediately.
 */
class IsolatedCellConfidence(
    private val requiredFreshObservations: Int = 3
) {
    private var identity: String? = null
    private var lastToken: Long? = null
    private var consecutive = 0

    fun observe(identity: String, candidate: Boolean, observationToken: Long?): Boolean {
        if (this.identity != identity) {
            this.identity = identity
            lastToken = null
            consecutive = 0
        }

        if (!candidate) {
            lastToken = observationToken
            consecutive = 0
            return false
        }

        val fresh = observationToken == null || observationToken != lastToken
        if (fresh) {
            lastToken = observationToken
            consecutive++
        }
        return consecutive >= requiredFreshObservations
    }

    fun reset() {
        identity = null
        lastToken = null
        consecutive = 0
    }
}
