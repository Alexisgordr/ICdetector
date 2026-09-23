package com.alexisgordr.icdetector.core

import java.util.UUID

const val MOBILITY_FAMILIARITY_ENABLED = true

enum class MobilityFamiliarity { UNKNOWN_ON_ROUTE, OBSERVED_ON_ROUTE, KNOWN_ON_ROUTE }
enum class MobilityTripCloseReason { STATIC_CONFIRMED, SAME_SERVING_TIMEOUT, MAX_DURATION, ABANDONED }

data class MobilityFamiliarityConfig(
    val priorTripsRequired: Int = 3,
    val goodEdgesRequired: Int = 2,
    val minimumDistinctCells: Int = 3,
    // Provisional field parameters. They must be calibrated from exported field evidence.
    val staticCloseDurationMs: Long = 3 * 60_000L,
    val sameServingCloseDurationMs: Long = 10 * 60_000L,
    val maxTripDurationMs: Long = 6 * 60 * 60_000L,
    val resumeWindowMs: Long = 15 * 60_000L,
    val closedTripDetailRetentionMs: Long = 90L * 24 * 60 * 60_000L
)

data class MobilityEdge(val from: String, val to: String)
data class MobilityTrip(
    val id: String,
    val startedAtMs: Long,
    val lastSeenMs: Long,
    val hasMoving: Boolean,
    val lastServing: String?,
    val sameServingSinceMs: Long,
    val staticSinceMs: Long?
)

interface MobilityFamiliarityStore {
    fun openTrip(): MobilityTrip?
    fun createTrip(trip: MobilityTrip, firstCell: String)
    fun updateTrip(trip: MobilityTrip)
    fun addTripCell(tripId: String, identity: String)
    fun addTripEdge(tripId: String, edge: MobilityEdge)
    fun tripCells(tripId: String): Set<String>
    fun tripEdges(tripId: String): Set<MobilityEdge>
    fun priorTripCounts(edges: Set<MobilityEdge>): Map<MobilityEdge, Int>
    fun commitMobilityTrip(tripId: String, reason: MobilityTripCloseReason, closedAtMs: Long)
    fun discardMobilityTrip(tripId: String, reason: MobilityTripCloseReason, closedAtMs: Long)
    fun pruneMobilityTripDetails(beforeMs: Long)
}

data class MobilityObservation(
    val familiarity: MobilityFamiliarity,
    val tripId: String? = null,
    val priorTrips: Int = 0,
    val goodEdges: Int = 0,
    val event: String? = null
)

/**
 * Service-owned trip state machine. Evaluation is deliberately read-only and always precedes a
 * possible commit, so the open trip cannot validate itself.
 */
class MobilityFamiliarityEngine(
    private val store: MobilityFamiliarityStore,
    private val config: MobilityFamiliarityConfig = MobilityFamiliarityConfig(),
    private val enabled: Boolean = MOBILITY_FAMILIARITY_ENABLED,
    private val newTripId: () -> String = { UUID.randomUUID().toString() }
) {
    private var recovered = false

    @Synchronized
    fun observe(identity: String, motion: MotionEvidence, nowMs: Long): MobilityObservation {
        if (!enabled || identity.isBlank()) return MobilityObservation(MobilityFamiliarity.UNKNOWN_ON_ROUTE)
        recoverIfNeeded(nowMs)
        var trip = store.openTrip()
        if (trip == null) {
            if (motion.state != MotionState.MOVING) return MobilityObservation(MobilityFamiliarity.UNKNOWN_ON_ROUTE)
            trip = MobilityTrip(newTripId(), nowMs, nowMs, true, identity, nowMs, null)
            store.createTrip(trip, identity)
            return MobilityObservation(MobilityFamiliarity.UNKNOWN_ON_ROUTE, trip.id, event = "trip_started")
        }

        val priorServing = trip.lastServing
        var event: String? = null
        val sameServingSince = if (priorServing == identity) trip.sameServingSinceMs else nowMs
        val staticSince = when (motion.state) {
            MotionState.STATIC_CONFIRMED -> trip.staticSinceMs ?: nowMs
            else -> null
        }
        trip = trip.copy(
            lastSeenMs = nowMs,
            hasMoving = trip.hasMoving || motion.state == MotionState.MOVING,
            lastServing = identity,
            sameServingSinceMs = sameServingSince,
            staticSinceMs = staticSince
        )
        store.addTripCell(trip.id, identity)
        if (priorServing != null && priorServing != identity) {
            val edge = MobilityEdge(priorServing, identity)
            if (edge !in store.tripEdges(trip.id)) event = "edge_seen ${edge.from}->${edge.to}"
            store.addTripEdge(trip.id, edge)
        }
        store.updateTrip(trip)

        // Snapshot comes exclusively from committed prior trips. Pending edges are a separate table.
        val related = store.tripEdges(trip.id).filterTo(linkedSetOf()) { it.from == identity || it.to == identity }
        val prior = store.priorTripCounts(related)
        val good = related.count { (prior[it] ?: 0) >= config.priorTripsRequired }
        val anyPrior = related.any { (prior[it] ?: 0) > 0 }
        val familiarity = when {
            good >= config.goodEdgesRequired -> MobilityFamiliarity.KNOWN_ON_ROUTE
            anyPrior -> MobilityFamiliarity.OBSERVED_ON_ROUTE
            else -> MobilityFamiliarity.UNKNOWN_ON_ROUTE
        }
        var result = MobilityObservation(familiarity, trip.id, prior.values.maxOrNull() ?: 0, good, event)

        val closeReason = when {
            nowMs - trip.startedAtMs >= config.maxTripDurationMs -> MobilityTripCloseReason.MAX_DURATION
            staticSince != null && nowMs - staticSince >= config.staticCloseDurationMs -> MobilityTripCloseReason.STATIC_CONFIRMED
            motion.state != MotionState.MOVING && nowMs - sameServingSince >= config.sameServingCloseDurationMs -> MobilityTripCloseReason.SAME_SERVING_TIMEOUT
            else -> null
        }
        if (closeReason != null) {
            val committed = close(trip, closeReason, nowMs)
            result = result.copy(event = if (committed) "trip_closed reason=${closeReason.name}" else "trip_discarded reason=${closeReason.name}")
        }
        return result
    }

    @Synchronized fun recover(nowMs: Long): MobilityObservation {
        if (!enabled) return MobilityObservation(MobilityFamiliarity.UNKNOWN_ON_ROUTE)
        recovered = true
        val trip = store.openTrip() ?: return MobilityObservation(MobilityFamiliarity.UNKNOWN_ON_ROUTE)
        if (nowMs - trip.lastSeenMs <= config.resumeWindowMs && nowMs - trip.startedAtMs < config.maxTripDurationMs) {
            return MobilityObservation(MobilityFamiliarity.UNKNOWN_ON_ROUTE, trip.id, event = "trip_resumed")
        }
        val committed = close(trip, MobilityTripCloseReason.ABANDONED, nowMs)
        return MobilityObservation(
            MobilityFamiliarity.UNKNOWN_ON_ROUTE,
            trip.id,
            event = if (committed) "trip_closed reason=ABANDONED" else "trip_discarded reason=INVALID_ABANDONED"
        )
    }

    private fun recoverIfNeeded(nowMs: Long) { if (!recovered) recover(nowMs) }

    private fun close(trip: MobilityTrip, reason: MobilityTripCloseReason, nowMs: Long): Boolean {
        val valid = trip.hasMoving && store.tripCells(trip.id).size >= config.minimumDistinctCells
        if (valid) store.commitMobilityTrip(trip.id, reason, nowMs)
        else store.discardMobilityTrip(trip.id, reason, nowMs)
        store.pruneMobilityTripDetails(nowMs - config.closedTripDetailRetentionMs)
        return valid
    }
}
