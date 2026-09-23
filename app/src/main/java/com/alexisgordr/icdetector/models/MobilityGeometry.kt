package com.alexisgordr.icdetector.models

import com.alexisgordr.icdetector.core.MobilityEdge
import com.alexisgordr.icdetector.core.MobilityFamiliarity
import com.alexisgordr.icdetector.core.MobilityFamiliarityConfig

data class MobilityTripSummary(
    val tripId: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val state: String,
    val closeReason: String?,
    val hadMoving: Boolean,
    val distinctCells: Int,
    val edgeCount: Int,
    val lastServing: String?
)

data class MobilityCellPresentation(
    val identity: String,
    val radioTech: String,
    val localTrustState: String?,
    val familiarity: MobilityFamiliarity,
    val goodEdges: Int,
    val relevantTripCount: Int,
    val incomingRoutes: Int,
    val outgoingRoutes: Int,
    val incomingTransitions: Int,
    val outgoingTransitions: Int,
    val trustedIncoming: Int,
    val trustedOutgoing: Int,
    val firstMobilitySeenMs: Long?,
    val lastMobilitySeenMs: Long?
)

data class MobilityGeometrySnapshot(
    val transitions: List<CellTransitionSummary>,
    val trips: List<MobilityTripSummary>,
    val pendingEdges: Set<MobilityEdge>,
    val cells: List<MobilityCellPresentation>
) {
    val openTrip: MobilityTripSummary? get() = trips.firstOrNull { it.state == "OPEN" }
}

/** Read-only presentation projection. It never writes or feeds a security decision. */
object MobilityGeometryProjection {
    fun build(
        transitions: List<CellTransitionSummary>,
        trips: List<MobilityTripSummary>,
        pendingEdges: Set<MobilityEdge>,
        config: MobilityFamiliarityConfig = MobilityFamiliarityConfig()
    ): MobilityGeometrySnapshot {
        val identities = buildSet {
            transitions.forEach { add(it.fromIdentity); add(it.toIdentity) }
            pendingEdges.forEach { add(it.from); add(it.to) }
            trips.firstOrNull { it.state == "OPEN" }?.lastServing?.let(::add)
        }
        val cells = identities.sorted().map { identity ->
            val incoming = transitions.filter { it.toIdentity == identity }
            val outgoing = transitions.filter { it.fromIdentity == identity }
            val incident = incoming + outgoing
            val good = incident.count { it.tripCount >= config.priorTripsRequired }
            val familiarity = when {
                good >= config.goodEdgesRequired -> MobilityFamiliarity.KNOWN_ON_ROUTE
                incident.any { it.tripCount > 0 } -> MobilityFamiliarity.OBSERVED_ON_ROUTE
                else -> MobilityFamiliarity.UNKNOWN_ON_ROUTE
            }
            MobilityCellPresentation(
                identity, identity.substringAfterLast('-', "UNKNOWN"), null, familiarity, good,
                incident.maxOfOrNull { it.tripCount } ?: 0,
                incoming.size, outgoing.size, incoming.sumOf { it.observations }, outgoing.sumOf { it.observations },
                incoming.sumOf { it.trustedObservations }, outgoing.sumOf { it.trustedObservations },
                incident.mapNotNull { it.mobilityFirstSeenMs }.minOrNull(),
                incident.mapNotNull { it.mobilityLastSeenMs }.maxOrNull()
            )
        }
        return MobilityGeometrySnapshot(transitions, trips, pendingEdges, cells)
    }
}
