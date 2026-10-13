package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellLocationSample
import com.alexisgordr.icdetector.models.HeuristicStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (G4) — Una transición A→C solo es un handover observado si no hubo interrupción entre ambas.
 * [Phone] reproduce el ciclo del servicio con las piezas reales: CollectionGeneration (pérdidas),
 * CoverageContinuity (check antes de analizar, acknowledge al publicar), el motor de movilidad y la
 * referencia de H16/Topología (HandoverReference), que solo registra un handover cuando lo observa.
 */
class HandoverContinuityTest {
    private val moving = MotionEvidence(MotionState.MOVING)
    private val still = MotionEvidence(MotionState.STATIC_CONFIRMED)
    private val civil0 = 1_760_000_000_000L

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
    }

    private class Phone(val store: Store = Store(), val anchors: MobilityAnchorStore = InMemoryMobilityAnchorStore(), val boot: String = "boot-1") {
        var generation = CollectionGeneration(); var coverage = CoverageContinuity()
        var engine = MobilityFamiliarityEngine(store, MobilityFamiliarityConfig(), true, anchors) { "trip-${++trips}" }
        var trackerLast: String? = null
        /** Handovers registrados por el tracker (cell_transitions.observations). */
        val handovers = linkedMapOf<MobilityEdge, Int>()
        var elapsed = 1_000_000L
        private var trips = 0

        fun cell(id: String, afterMs: Long = 5_000, motion: MotionEvidence = MotionEvidence(MotionState.MOVING)): HandoverReference.Step {
            elapsed += afterMs
            val ticket = generation.deliver(id)
            val continuous = coverage.check(generation.losses, elapsed) == CoverageContinuity.Break.NONE
            engine.observe(id, motion, MobilityClock(civil(), elapsed, boot), serviceContinuous = continuous)
            val step = HandoverReference.step(trackerLast, id, continuous)
            if (step == HandoverReference.Step.HANDOVER) handovers.merge(MobilityEdge(trackerLast!!, id), 1, Int::plus)
            trackerLast = id
            if (generation.mayPublish(ticket)) coverage.acknowledge(generation.losses, elapsed)
            return step
        }
        /** Lista vacía, modo avión o primaria inutilizable: lo que hace onSignalLost(). */
        fun loseService(forMs: Long = 20_000) { elapsed += forMs; generation.lose() }
        /** El proceso muere y vuelve a arrancar: memoria nueva, base y preferencias conservadas. */
        fun restartProcess(afterMs: Long, newBoot: String = boot): MobilityObservation {
            elapsed += afterMs
            generation = CollectionGeneration(); coverage = CoverageContinuity(); trackerLast = null
            engine = MobilityFamiliarityEngine(store, MobilityFamiliarityConfig(), true, anchors) { "trip-${++trips}" }
            return engine.recover(MobilityClock(civil(), elapsed, newBoot))
        }
        fun civil() = 1_760_000_000_000L + elapsed
        fun tripEdges() = store.edges.toSet()
    }

    @Test fun `A to B without interruption is an observed handover`() {
        val p = Phone()
        p.cell("A"); assertEquals(HandoverReference.Step.HANDOVER, p.cell("B"))
        assertEquals(setOf(MobilityEdge("A", "B")), p.tripEdges())
        assertEquals(mapOf(MobilityEdge("A", "B") to 1), p.handovers)
    }

    @Test fun `A, no service, C makes C a new reference without an A to C edge`() {
        val p = Phone()
        p.cell("A"); p.cell("B")
        p.loseService()
        assertEquals(HandoverReference.Step.NEW_REFERENCE_AFTER_INTERRUPTION, p.cell("C"))
        assertFalse(MobilityEdge("B", "C") in p.tripEdges())
        assertNull(p.handovers[MobilityEdge("B", "C")])
        // Desde la nueva referencia todo vuelve a ser normal.
        assertEquals(HandoverReference.Step.HANDOVER, p.cell("D"))
        assertTrue(MobilityEdge("C", "D") in p.tripEdges())
        assertEquals(1, p.handovers[MobilityEdge("C", "D")])
        assertEquals("el viaje sigue", "trip-1", p.store.trip?.id)
    }

    @Test fun `airplane mode and recovery, short or long, never bridge the gap`() {
        listOf(15_000L, 10 * 60_000L).forEach { off ->
            val p = Phone()
            p.cell("A"); p.cell("B")
            p.loseService(forMs = off)                    // modo avión: onSignalLost en el hilo principal
            p.cell("C")
            assertFalse("$off ms", MobilityEdge("B", "C") in p.tripEdges())
            assertTrue("$off ms", p.handovers.keys.none { it.to == "C" })
        }
    }

    @Test fun `an unusable declared primary is an interruption too`() {
        val p = Phone()
        p.cell("A"); p.cell("B")
        p.loseService(forMs = 3_000)                      // ciclo en abstención: onSignalLost
        assertEquals(HandoverReference.Step.NEW_REFERENCE_AFTER_INTERRUPTION, p.cell("C", afterMs = 3_000))
        assertFalse(MobilityEdge("B", "C") in p.tripEdges())
    }

    @Test fun `a gap without readings longer than the continuity limit is an interruption`() {
        val p = Phone()
        p.cell("A"); p.cell("B")
        assertEquals(HandoverReference.Step.NEW_REFERENCE_AFTER_INTERRUPTION, p.cell("C", afterMs = CoverageContinuity.MAX_GAP_MS + 1_000))
        assertEquals(HandoverReference.Step.HANDOVER, p.cell("D", afterMs = CoverageContinuity.MAX_GAP_MS))
    }

    @Test fun `the same cell after a loss is not a transition and keeps its timers`() {
        val p = Phone()
        p.cell("A"); p.cell("B")
        p.loseService()
        assertEquals(HandoverReference.Step.NEW_REFERENCE_AFTER_INTERRUPTION, p.cell("B"))
        assertEquals(setOf(MobilityEdge("A", "B")), p.tripEdges())
        assertEquals(1, p.handovers.size)
    }

    @Test fun `a restart with demonstrable continuity keeps linking cells`() {
        val p = Phone()
        p.cell("A"); p.cell("B")
        assertEquals("trip_resumed", p.restartProcess(afterMs = 20_000).event)
        p.cell("C", afterMs = 5_000)
        assertTrue("25 s monotónicos, mismo arranque: continuidad demostrada", MobilityEdge("B", "C") in p.tripEdges())
    }

    @Test fun `a restart without demonstrable continuity starts a new reference or closes the trip`() {
        // Mismo arranque, viaje reanudado, pero el hueco supera el límite de continuidad.
        val p = Phone()
        p.cell("A"); p.cell("B")
        assertEquals("trip_resumed", p.restartProcess(afterMs = 5 * 60_000).event)
        p.cell("C", afterMs = 5_000)
        assertFalse(MobilityEdge("B", "C") in p.tripEdges())
        assertEquals("trip-1", p.store.trip?.id)
        // Otro arranque: el viaje se cierra y la primera celda solo abre otro, sin arista.
        val q = Phone()
        q.cell("A"); q.cell("B")
        assertEquals("trip_discarded reason=INVALID_ABANDONED", q.restartProcess(afterMs = 10_000, newBoot = "boot-2").event)
        q.cell("C", afterMs = 1_000)
        assertEquals(setOf<MobilityEdge>(), q.tripEdges())
        // El tracker de H16 vive en memoria: tras reiniciar no hay referencia, ni handover A→C.
        assertTrue(q.handovers.keys.none { it.to == "C" })
    }

    @Test fun `a valid cell without GPS keeps cellular continuity, only geometry is not evaluable`() {
        val p = Phone()
        p.cell("A", motion = MotionEvidence(MotionState.UNKNOWN))
        p.cell("A0")                                      // abre el viaje (movimiento)
        assertEquals(HandoverReference.Step.HANDOVER, p.cell("B", motion = MotionEvidence(MotionState.UNKNOWN)))
        assertTrue(MobilityEdge("A0", "B") in p.tripEdges())
        assertEquals(1, p.handovers[MobilityEdge("A0", "B")])
        // H16 sin fix: N/A por GPS, no por interrupción, y nunca suma a rutas fiables.
        val samples = List(4) { CellLocationSample(40.0, -3.0) }
        val r = TransitionCoherence.evaluate(TransitionCoherence.Input("A0", "B", 5,
            TransitionCoherence.Endpoint(100_000, null), TransitionCoherence.Endpoint(105_000, null), null, null, null,
            samples, samples, 3, true))
        assertEquals(HeuristicStatus.NOT_EVALUATED, r.status)
        assertTrue(r.explanation.contains("no hay fix GPS"))
        assertEquals(0, TransitionCoherence.trustedIncrement(r))
    }

    @Test fun `unobserved connections never add familiarity or checked handovers, and old edges stay`() {
        val store = Store().apply { counts[MobilityEdge("X", "Y")] = 7 }      // arista histórica previa
        repeat(3) {
            val p = Phone(store)
            p.cell("A"); p.cell("B")
            p.loseService()
            p.cell("C"); p.cell("D")
            p.cell("D", motion = still); p.cell("D", afterMs = 180_000, motion = still)   // cierra y confirma
            assertNull("viaje confirmado", store.trip)
            assertNull(p.handovers[MobilityEdge("B", "C")])
        }
        assertNull(store.counts[MobilityEdge("B", "C")])
        assertEquals(3, store.counts[MobilityEdge("A", "B")]); assertEquals(3, store.counts[MobilityEdge("C", "D")])
        assertEquals("las aristas existentes no se borran ni se reclasifican", 7, store.counts[MobilityEdge("X", "Y")])
        val c = MobilityFamiliarityPolicy.assess(store.historicalEdgeCounts("C").values)
        assertEquals("C solo conoce C→D", 1, c.goodEdges)
    }

    @Test fun `the rule, and both consumers use it`() {
        assertEquals(HandoverReference.Step.FIRST, HandoverReference.step(null, "A", false))
        assertEquals(HandoverReference.Step.SAME_CELL, HandoverReference.step("A", "A", true))
        assertEquals(HandoverReference.Step.HANDOVER, HandoverReference.step("A", "B", true))
        assertEquals(HandoverReference.Step.NEW_REFERENCE_AFTER_INTERRUPTION, HandoverReference.step("A", "B", false))
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val tracker = main.resolve("com/alexisgordr/icdetector/service/ServingTransitionTracker.kt").readText()
        assertTrue(tracker.contains("HandoverReference.step(previous?.cell?.identityKey, active.identityKey, serviceContinuous)"))
        assertTrue("solo registra tras un handover observado",
            tracker.indexOf("db.recordCellTransition(result)") > tracker.indexOf("HandoverReference.Step.HANDOVER -> Unit"))
        val service = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val cycle = service.substringAfter("cellProcessingMutex.withLock {")
        val check = cycle.indexOf("val continuityBroken =")
        assertTrue(check in 0 until cycle.indexOf("serviceContinuous = !continuityBroken)"))
        assertEquals(2, Regex("""serviceContinuous = !continuityBroken""").findAll(cycle).count())
    }
}
