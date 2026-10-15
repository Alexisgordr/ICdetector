package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.0 (G3) — Los viajes miden intervalos con el reloj monotónico de un solo arranque y solo fechan
 * con el civil. Un cambio del reloj civil no deja viajes abiertos para siempre, no produce
 * duraciones negativas ni inventa continuidad tras un reinicio. Límites de siempre.
 */
class MobilityTripClockTest {
    private val moving = MotionEvidence(MotionState.MOVING)
    private val still = MotionEvidence(MotionState.STATIC_CONFIRMED)
    private val unknown = MotionEvidence(MotionState.UNKNOWN)
    private val config = MobilityFamiliarityConfig()
    private val hour = 3_600_000L
    private val civil0 = 1_760_000_000_000L      // fecha civil cualquiera

    private class Store : MobilityFamiliarityStore {
        var trip: MobilityTrip? = null
        val cells = linkedSetOf<String>(); val edges = linkedSetOf<MobilityEdge>()
        val counts = linkedMapOf<MobilityEdge, Int>()
        val closed = mutableListOf<Triple<String, MobilityTripCloseReason, Long>>()
        val started = mutableMapOf<String, Long>()
        override fun openTrip() = trip
        override fun createTrip(trip: MobilityTrip, firstCell: String) { this.trip = trip; started[trip.id] = trip.startedAtMs; cells.clear(); edges.clear(); cells += firstCell }
        override fun updateTrip(trip: MobilityTrip) { this.trip = trip }
        override fun addTripCell(tripId: String, identity: String) { cells += identity }
        override fun addTripEdge(tripId: String, edge: MobilityEdge) { edges += edge }
        override fun tripCells(tripId: String) = cells.toSet()
        override fun tripEdges(tripId: String) = edges.toSet()
        override fun priorTripCounts(edges: Set<MobilityEdge>) = edges.associateWith { counts[it] ?: 0 }
        override fun historicalEdgeCounts(identity: String) = counts.filter { (e, n) -> n > 0 && (e.from == identity || e.to == identity) }
        override fun commitMobilityTrip(tripId: String, reason: MobilityTripCloseReason, closedAtMs: Long) {
            if (trip?.id != tripId) return; edges.forEach { counts[it] = (counts[it] ?: 0) + 1 }; closed += Triple(tripId, reason, closedAtMs); trip = null
        }
        override fun discardMobilityTrip(tripId: String, reason: MobilityTripCloseReason, closedAtMs: Long) { closed += Triple(tripId, reason, closedAtMs); trip = null }
        override fun pruneMobilityTripDetails(beforeMs: Long) {}
        /** Ninguna duración guardada es negativa. */
        fun assertNoNegativeDuration() = closed.forEach { (id, _, at) -> assertTrue("$id cierra antes de empezar", at >= started.getValue(id)) }
    }

    private var ids = 0
    private fun engine(s: Store, anchors: MobilityAnchorStore = InMemoryMobilityAnchorStore()) =
        MobilityFamiliarityEngine(s, config, true, anchors) { "t${++ids}" }
    private fun clock(civil: Long, elapsed: Long, boot: String? = "boot-1") = MobilityClock(civil, elapsed, boot)

    /** Un viaje válido A→B→C a partir de (civil, elapsed). */
    private fun startTrip(e: MobilityFamiliarityEngine, civil: Long, elapsed: Long) {
        listOf("A", "B", "C").forEachIndexed { i, c -> e.observe(c, moving, clock(civil + i * 10_000, elapsed + i * 10_000)) }
    }

    @Test fun `a civil clock moved back does not keep the trip open forever nor give negative durations`() {
        val s = Store(); val e = engine(s)
        startTrip(e, civil0, 1_000_000)
        // El usuario retrasa el reloj 1 h. El monotónico sigue avanzando.
        val back = e.observe("C", still, clock(civil0 + 30_000 - hour, 1_030_000))
        assertNull("el viaje no sigue abierto con fechas invertidas", s.trip)
        assertEquals(MobilityTripCloseReason.CLOCK_CHANGED, s.closed.single().second)
        assertEquals("se cierra en su última hora civil observada", civil0 + 20_000, s.closed.single().third)
        assertEquals("trip_closed reason=CLOCK_CHANGED", back.event)
        assertEquals("era válido: se confirma", 1, s.counts[MobilityEdge("A", "B")])
        s.assertNoNegativeDuration()
    }

    @Test fun `a civil clock moved forward does not stretch the trip nor close it by maximum duration`() {
        val s = Store(); val e = engine(s)
        startTrip(e, civil0, 1_000_000)
        e.observe("D", moving, clock(civil0 + 7 * hour, 1_040_000))     // +7 h civiles en 20 s reales
        val reason = s.closed.single().second
        assertEquals(MobilityTripCloseReason.CLOCK_CHANGED, reason)
        assertTrue("nunca MAX_DURATION por un salto del reloj", reason != MobilityTripCloseReason.MAX_DURATION)
        assertEquals(civil0 + 20_000, s.closed.single().third)
        assertNotNull("empieza otro viaje con las fechas nuevas", s.trip)
        s.assertNoNegativeDuration()
    }

    @Test fun `small NTP corrections do not split the trip`() {
        val s = Store(); val e = engine(s)
        startTrip(e, civil0, 1_000_000)
        e.observe("D", moving, clock(civil0 + 30_000 + 90_000, 1_030_000))   // +90 s de corrección
        assertTrue(s.closed.isEmpty())
        assertEquals("t1", s.trip?.id)
    }

    @Test fun `durations use monotonic time, so the usual limits still close the trip`() {
        val s = Store(); val e = engine(s)
        startTrip(e, civil0, 1_000_000)
        e.observe("C", still, clock(civil0 + 30_000, 1_030_000))
        e.observe("C", still, clock(civil0 + 30_000 + config.staticCloseDurationMs, 1_030_000 + config.staticCloseDurationMs))
        assertEquals(MobilityTripCloseReason.STATIC_CONFIRMED, s.closed.single().second)
        // Duración máxima: 6 h monotónicas con el civil avanzando igual.
        val s2 = Store(); val e2 = engine(s2)
        startTrip(e2, civil0, 0)
        var t = 30_000L
        while (s2.trip != null && t <= config.maxTripDurationMs) {
            e2.observe(if ((t / 30_000) % 2 == 0L) "A" else "B", moving, clock(civil0 + t, t)); t += 30_000
        }
        assertEquals(MobilityTripCloseReason.MAX_DURATION, s2.closed.single().second)
        assertEquals(config.maxTripDurationMs, t - 30_000)
        // Misma servidora sin movimiento concluyente: 10 min monotónicos.
        val s3 = Store(); val e3 = engine(s3)
        startTrip(e3, civil0, 0)
        e3.observe("C", unknown, clock(civil0 + 30_000, 30_000))
        e3.observe("C", unknown, clock(civil0 + 20_000 + config.sameServingCloseDurationMs, 20_000 + config.sameServingCloseDurationMs))
        assertEquals(MobilityTripCloseReason.SAME_SERVING_TIMEOUT, s3.closed.single().second)
    }

    @Test fun `a process restart in the same boot within the window resumes the persisted trip`() {
        val s = Store(); val anchors = InMemoryMobilityAnchorStore()
        startTrip(engine(s, anchors), civil0, 1_000_000)
        val restarted = engine(s, anchors)
        assertEquals("trip_resumed", restarted.recover(clock(civil0 + 80_000, 1_080_000)).event)
        restarted.observe("D", moving, clock(civil0 + 90_000, 1_090_000))
        assertEquals("t1", s.trip?.id)
        assertTrue(s.closed.isEmpty())
    }

    @Test fun `a restart without demonstrable continuity closes the trip without inventing duration`() {
        listOf(
            "otro arranque" to clock(civil0 + 60_000, 5_000, boot = "boot-2"),
            "arranque desconocido" to clock(civil0 + 60_000, 1_060_000, boot = null),
            "fuera de la ventana" to clock(civil0 + 20_000 + config.resumeWindowMs + 60_000, 1_020_000 + config.resumeWindowMs + 60_000),
            "reloj civil movido" to clock(civil0 + 60_000 - 2 * hour, 1_060_000),
            "monotónico hacia atrás" to clock(civil0 + 60_000, 900_000)
        ).forEach { (case, at) ->
            val s = Store(); val anchors = InMemoryMobilityAnchorStore()
            startTrip(engine(s, anchors), civil0, 1_000_000)
            val r = engine(s, anchors).recover(at)
            assertEquals(case, "trip_closed reason=ABANDONED", r.event)
            assertEquals(case, civil0 + 20_000, s.closed.single().third)
            s.assertNoNegativeDuration()
        }
        // Sin ancla guardada (restauración, actualización desde una versión anterior): igual.
        val s = Store()
        startTrip(engine(s, InMemoryMobilityAnchorStore()), civil0, 1_000_000)
        assertEquals("trip_closed reason=ABANDONED", engine(s, InMemoryMobilityAnchorStore()).recover(clock(civil0 + 40_000, 1_040_000)).event)
    }

    @Test fun `an abandoned invalid trip is discarded and its anchor cleared`() {
        val s = Store(); val anchors = InMemoryMobilityAnchorStore()
        engine(s, anchors).observe("A", moving, clock(civil0, 1_000_000))
        assertNotNull(anchors.load())
        val r = engine(s, anchors).recover(clock(civil0, 50, boot = "boot-2"))
        assertEquals("trip_discarded reason=INVALID_ABANDONED", r.event)
        assertNull(anchors.load())
        assertTrue(s.counts.isEmpty())
    }

    @Test fun `the anchor round-trips and garbage is ignored`() {
        val a = MobilityTripAnchor("t", "7", 1, 2, 3, 4, null)
        assertEquals(a, MobilityTripAnchor.parse(a.serialize()))
        val b = a.copy(bootId = null, staticSinceElapsedMs = 9)
        assertEquals(b, MobilityTripAnchor.parse(b.serialize()))
        listOf(null, "", "x|y", "t|7|a|2|3|4|", "|7|1|2|3|4|").forEach { assertNull(it, MobilityTripAnchor.parse(it)) }
    }

    @Test fun `the anchor is internal, never backed up and cleared by a restore`() {
        assertTrue(!com.alexisgordr.icdetector.backup.BackupSettings.KEYS.containsKey(MobilityTripAnchor.PREF_KEY))
        assertNull(com.alexisgordr.icdetector.backup.BackupSettings.fromJson("{\"${MobilityTripAnchor.PREF_KEY}\":\"x\"}")[MobilityTripAnchor.PREF_KEY])
        val main = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        val restore = main.resolve("com/alexisgordr/icdetector/backup/RestoreStartup.kt").readText()
        val apply = restore.substringAfter("internal fun applySettings(").substringBefore("fun prefs(")
        assertTrue(apply.indexOf("editor.remove(com.alexisgordr.icdetector.core.MobilityTripAnchor.PREF_KEY)") in 0 until apply.indexOf("editor.commit()"))
    }
}
