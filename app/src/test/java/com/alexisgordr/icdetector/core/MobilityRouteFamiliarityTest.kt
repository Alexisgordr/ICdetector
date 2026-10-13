package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellTransitionSummary
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.MobilityGeometryProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.0 (G2) — La familiaridad de ruta en directo y en Geometry salen de la misma política
 * (MobilityFamiliarityPolicy) con los requisitos de siempre (K = 3 viajes, R = 2 aristas). La
 * familiaridad histórica de la celda y la llegada observada en este viaje son conceptos distintos.
 * Configuración real: no se rebaja goodEdgesRequired.
 */
class MobilityRouteFamiliarityTest {
    private val moving = MotionEvidence(MotionState.MOVING)
    private val config = MobilityFamiliarityConfig()

    /** Store en memoria con el mismo contrato que CellDbHelper: trip_count solo sube al confirmar un viaje. */
    private class Store : MobilityFamiliarityStore {
        var trip: MobilityTrip? = null
        val cells = linkedSetOf<String>(); val edges = linkedSetOf<MobilityEdge>()
        val counts = linkedMapOf<MobilityEdge, Int>()
        override fun openTrip() = trip
        override fun createTrip(trip: MobilityTrip, firstCell: String) { this.trip = trip; cells.clear(); edges.clear(); cells += firstCell }
        override fun updateTrip(trip: MobilityTrip) { this.trip = trip }
        override fun addTripCell(tripId: String, identity: String) { cells += identity }
        override fun addTripEdge(tripId: String, edge: MobilityEdge) { edges += edge }
        override fun tripCells(tripId: String) = cells.toSet()
        override fun tripEdges(tripId: String) = edges.toSet()
        override fun priorTripCounts(edges: Set<MobilityEdge>) = edges.associateWith { counts[it] ?: 0 }
        override fun historicalEdgeCounts(identity: String) = counts.filter { (e, n) -> n > 0 && (e.from == identity || e.to == identity) }
        override fun commitMobilityTrip(tripId: String, reason: MobilityTripCloseReason, closedAtMs: Long) {
            if (trip?.id != tripId) return; edges.forEach { counts[it] = (counts[it] ?: 0) + 1 }; trip = null
        }
        override fun discardMobilityTrip(tripId: String, reason: MobilityTripCloseReason, closedAtMs: Long) { trip = null }
        override fun pruneMobilityTripDetails(beforeMs: Long) {}
        /** Lo que leería Geometry de cell_transitions. */
        fun transitions() = counts.map { (e, n) -> CellTransitionSummary(e.from, e.to, 0, 0, HeuristicStatus.NOT_EVALUATED, 0L, tripCount = n) }
    }

    private fun engine(s: Store, id: String = "trip") = MobilityFamiliarityEngine(s, config, true) { id }

    @Test fun `first arrival at a cell is unknown and has no history`() {
        val s = Store(); val e = engine(s)
        e.observe("A", moving, 0)
        val b = e.observe("B", moving, 1_000)
        assertEquals(MobilityFamiliarity.UNKNOWN_ON_ROUTE, b.familiarity)
        assertEquals("se llegó por A→B, sin viajes anteriores", 0, b.arrivalPriorTrips)
        assertNull("la primera celda del viaje no tiene llegada", e.observe("B", moving, 2_000).arrivalPriorTrips)
    }

    @Test fun `a historically known route is KNOWN live with the real R = 2, not only after lowering it`() {
        val s = Store()
        s.counts[MobilityEdge("A", "B")] = 3; s.counts[MobilityEdge("B", "C")] = 3
        val e = engine(s)
        e.observe("A", moving, 0)
        val atB = e.observe("B", moving, 1_000)
        // Antes solo contaban las aristas de ESTE viaje que tocan B (una, A→B): nunca 2 al llegar.
        assertEquals(MobilityFamiliarity.KNOWN_ON_ROUTE, atB.familiarity)
        assertEquals(2, atB.goodEdges)
        assertEquals(3, atB.arrivalPriorTrips)
        assertTrue(MobilityFamiliarityPolicy.arrivalKnown(atB.arrivalPriorTrips!!, config))
        assertEquals(2, config.goodEdgesRequired)
    }

    @Test fun `a known cell reached by a new connection keeps both facts apart`() {
        val s = Store()
        s.counts[MobilityEdge("A", "B")] = 4; s.counts[MobilityEdge("B", "C")] = 5
        val e = engine(s)
        e.observe("X", moving, 0)
        val atB = e.observe("B", moving, 1_000)
        assertEquals("celda conocida por viajes anteriores", MobilityFamiliarity.KNOWN_ON_ROUTE, atB.familiarity)
        assertEquals("pero la conexión X→B es nueva en este viaje", 0, atB.arrivalPriorTrips)
        assertTrue(!MobilityFamiliarityPolicy.arrivalKnown(0, config))
    }

    @Test fun `insufficient evidence stays observed`() {
        val s = Store()
        s.counts[MobilityEdge("A", "B")] = 3; s.counts[MobilityEdge("B", "C")] = 2   // una sola arista buena
        val e = engine(s)
        e.observe("A", moving, 0)
        val atB = e.observe("B", moving, 1_000)
        assertEquals(MobilityFamiliarity.OBSERVED_ON_ROUTE, atB.familiarity)
        assertEquals(1, atB.goodEdges)
        val few = Store().apply { counts[MobilityEdge("A", "B")] = 1 }
        val f = engine(few); f.observe("A", moving, 0)
        assertEquals(MobilityFamiliarity.OBSERVED_ON_ROUTE, f.observe("B", moving, 1_000).familiarity)
    }

    @Test fun `ping-pong inside the trip never raises familiarity, and counts once per trip`() {
        val s = Store(); val e = engine(s)
        e.observe("A", moving, 0)
        repeat(40) { n ->
            val r = e.observe(if (n % 2 == 0) "B" else "A", moving, (n + 1) * 1_000L)
            assertEquals(MobilityFamiliarity.UNKNOWN_ON_ROUTE, r.familiarity)
            assertEquals(0, r.arrivalPriorTrips)
        }
        assertTrue("nada confirmado mientras el viaje sigue abierto", s.counts.isEmpty())
        e.observe("C", moving, 50_000)
        e.observe("C", MotionEvidence(MotionState.STATIC_CONFIRMED), 51_000)
        e.observe("C", MotionEvidence(MotionState.STATIC_CONFIRMED), 51_000 + config.staticCloseDurationMs)
        assertEquals(1, s.counts[MobilityEdge("A", "B")]); assertEquals(1, s.counts[MobilityEdge("B", "A")])
    }

    @Test fun `the screen shows exactly the live historical familiarity of the same cell`() {
        listOf(
            mapOf(MobilityEdge("A", "B") to 3, MobilityEdge("B", "C") to 3),
            mapOf(MobilityEdge("A", "B") to 3, MobilityEdge("C", "B") to 1),
            mapOf(MobilityEdge("A", "B") to 1),
            emptyMap()
        ).forEach { data ->
            val s = Store().apply { counts.putAll(data) }
            val e = engine(s)
            e.observe("A", moving, 0)
            val live = e.observe("B", moving, 1_000)
            val screen = MobilityGeometryProjection.build(s.transitions(), emptyList(), s.edges).cells.firstOrNull { it.identity == "B" }
            assertEquals(data.toString(), live.familiarity, screen?.familiarity ?: MobilityFamiliarity.UNKNOWN_ON_ROUTE)
            assertEquals(data.toString(), live.goodEdges, screen?.goodEdges ?: 0)
            val shared = MobilityFamiliarityPolicy.assess(s.historicalEdgeCounts("B").values, config)
            assertEquals(shared.familiarity, live.familiarity)
        }
    }

    @Test fun `the evaluation precedes the observation, so the trip cannot validate itself`() {
        val s = Store()
        s.counts[MobilityEdge("A", "B")] = 2
        val e = engine(s)
        e.observe("A", moving, 0)
        val first = e.observe("B", moving, 1_000)
        assertEquals(2, first.arrivalPriorTrips)
        repeat(10) { e.observe(if (it % 2 == 0) "A" else "B", moving, 2_000L + it) }
        assertEquals("el viaje abierto no suma", 2, s.counts[MobilityEdge("A", "B")])
    }

    @Test fun `one policy, the documented thresholds`() {
        assertEquals(MobilityFamiliarity.UNKNOWN_ON_ROUTE, MobilityFamiliarityPolicy.assess(emptyList()).familiarity)
        assertEquals(MobilityFamiliarity.UNKNOWN_ON_ROUTE, MobilityFamiliarityPolicy.assess(listOf(0, 0)).familiarity)
        assertEquals(MobilityFamiliarity.OBSERVED_ON_ROUTE, MobilityFamiliarityPolicy.assess(listOf(3, 2)).familiarity)
        assertEquals(MobilityFamiliarity.KNOWN_ON_ROUTE, MobilityFamiliarityPolicy.assess(listOf(3, 3)).familiarity)
        assertEquals(5, MobilityFamiliarityPolicy.assess(listOf(5, 1)).maxPriorTrips)
    }
}
