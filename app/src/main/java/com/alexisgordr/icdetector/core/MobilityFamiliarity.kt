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

/**
 * 3.0 (G2) — Única política de familiaridad histórica, la misma para el servicio (en directo) y
 * para Geometry/exportación. Mide lo aprendido de VIAJES ANTERIORES YA CONFIRMADOS: cada arista
 * cuenta los viajes cerrados válidos que la recorrieron (trip_count). Una celda es:
 *  - KNOWN_ON_ROUTE: al menos [MobilityFamiliarityConfig.goodEdgesRequired] (2) aristas suyas, de
 *    entrada o de salida, recorridas en [MobilityFamiliarityConfig.priorTripsRequired] (3) viajes;
 *  - OBSERVED_ON_ROUTE: alguna arista suya aparece en algún viaje anterior;
 *  - UNKNOWN_ON_ROUTE: ninguna.
 * El viaje abierto nunca cuenta (sus aristas solo suman al cerrarse), así que un viaje no puede
 * validarse a sí mismo. Es memoria contextual, no confianza ni legitimidad.
 */
object MobilityFamiliarityPolicy {
    data class Assessment(val familiarity: MobilityFamiliarity, val goodEdges: Int, val maxPriorTrips: Int)

    /** [priorTripCounts]: viajes anteriores confirmados de cada arista incidente en la celda. */
    fun assess(priorTripCounts: Collection<Int>, config: MobilityFamiliarityConfig = MobilityFamiliarityConfig()): Assessment {
        val good = priorTripCounts.count { it >= config.priorTripsRequired }
        val familiarity = when {
            good >= config.goodEdgesRequired -> MobilityFamiliarity.KNOWN_ON_ROUTE
            priorTripCounts.any { it > 0 } -> MobilityFamiliarity.OBSERVED_ON_ROUTE
            else -> MobilityFamiliarity.UNKNOWN_ON_ROUTE
        }
        return Assessment(familiarity, good, priorTripCounts.maxOrNull() ?: 0)
    }

    /** ¿La arista por la que se acaba de llegar está aprendida (≥ K viajes anteriores)? */
    fun arrivalKnown(arrivalPriorTrips: Int, config: MobilityFamiliarityConfig = MobilityFamiliarityConfig()): Boolean =
        arrivalPriorTrips >= config.priorTripsRequired
}
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
    /** 3.0 (G2) — Todas las aristas guardadas que entran o salen de [identity], con sus viajes anteriores confirmados. */
    fun historicalEdgeCounts(identity: String): Map<MobilityEdge, Int>
    fun commitMobilityTrip(tripId: String, reason: MobilityTripCloseReason, closedAtMs: Long)
    fun discardMobilityTrip(tripId: String, reason: MobilityTripCloseReason, closedAtMs: Long)
    fun pruneMobilityTripDetails(beforeMs: Long)
}

/**
 * [familiarity]/[goodEdges]: familiaridad HISTÓRICA de la celda actual ([MobilityFamiliarityPolicy]),
 * la misma que muestra Geometry. [arrivalPriorTrips]: lo observado en ESTE viaje, la arista por la
 * que se llegó a la celda y sus viajes anteriores confirmados; null si no hubo llegada (misma celda
 * o primera celda del viaje). Son conceptos distintos y se presentan por separado.
 */
data class MobilityObservation(
    val familiarity: MobilityFamiliarity,
    val tripId: String? = null,
    val goodEdges: Int = 0,
    val event: String? = null,
    val arrivalPriorTrips: Int? = null
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
        // 3.0 (G2) — Se evalúa ANTES de incorporar esta observación al viaje, y solo con viajes
        // anteriores confirmados: el viaje abierto no puede validarse a sí mismo.
        val historical = MobilityFamiliarityPolicy.assess(store.historicalEdgeCounts(identity).values, config)
        val arrivalEdge = if (priorServing != null && priorServing != identity) MobilityEdge(priorServing, identity) else null
        val arrivalPriorTrips = arrivalEdge?.let { store.priorTripCounts(setOf(it))[it] ?: 0 }
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
        if (arrivalEdge != null) {
            if (arrivalEdge !in store.tripEdges(trip.id)) event = "edge_seen ${arrivalEdge.from}->${arrivalEdge.to}"
            store.addTripEdge(trip.id, arrivalEdge)
        }
        store.updateTrip(trip)

        var result = MobilityObservation(historical.familiarity, trip.id, historical.goodEdges, event, arrivalPriorTrips)

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
