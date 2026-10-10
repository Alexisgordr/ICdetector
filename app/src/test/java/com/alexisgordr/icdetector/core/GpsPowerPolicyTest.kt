package com.alexisgordr.icdetector.core

import org.junit.Assert.*
import org.junit.Test

class GpsPowerPolicyTest {
    @Test fun `handover keeps Smart GPS running for one minute even with screen off`() {
        val p = GpsPowerPolicy()
        assertFalse(p.streamWanted(LocationMode.INTELLIGENT, false, 10_000L))
        assertTrue(p.onHandover(10_000L))
        assertTrue(p.streamWanted(LocationMode.INTELLIGENT, false, 69_999L))
        assertFalse(p.streamWanted(LocationMode.INTELLIGENT, false, 70_000L))
        assertFalse(p.streamWanted(LocationMode.ADAPTIVE, false, 20_000L))
    }

    @Test fun `a later handover extends the same window from the last change`() {
        val p = GpsPowerPolicy()
        p.onHandover(10_000L)
        p.onFreshFix(20_000L)
        p.onHandover(40_000L)
        assertTrue(p.streamWanted(LocationMode.INTELLIGENT, false, 70_000L))
        assertTrue(p.streamWanted(LocationMode.INTELLIGENT, false, 99_999L))
        assertFalse(p.streamWanted(LocationMode.INTELLIGENT, false, 100_000L))
        p.clearHandoverWindow()
        assertFalse(p.streamWanted(LocationMode.INTELLIGENT, true, 50_000L))
    }

    @Test fun `handover storms cannot reopen a stalled stream until fresh GPS recovers`() {
        val p = GpsPowerPolicy()
        p.onHandover(0L)
        p.onHandover(150_000L)
        p.onStreamUnavailable(180_000L)
        repeat(30) { assertFalse(p.onHandover(181_000L + it * 1_000L)) }
        assertFalse(p.streamWanted(LocationMode.INTELLIGENT, true, 190_000L))
        assertTrue(p.streamWanted(LocationMode.CONTINUOUS, false, 190_000L))
        p.onFreshFix(300_000L)
        assertTrue(p.onHandover(301_000L))
        assertTrue(p.streamWanted(LocationMode.INTELLIGENT, false, 302_000L))
    }

    @Test fun `three windows without fixes also activate degraded reception`() {
        val p = GpsPowerPolicy()
        for (started in listOf(0L, 120_000L, 240_000L)) {
            assertTrue(p.onHandover(started))
            assertFalse(p.streamWanted(LocationMode.INTELLIGENT, false, started + 60_000L))
            p.onProbeFailed(started + 60_000L)
        }
        assertTrue(p.receptionDegraded)
        assertFalse(p.onHandover(301_000L))
    }

    private val smart = LocationMode.INTELLIGENT
    private fun GpsPowerPolicy.ho(now: Long, to: String, from: String?, fix: Long? = null) =
        onHandover(smart, now, to, from, fix)

    @Test fun `a new cell always wakes the GPS, also with degraded reception`() {
        val p = GpsPowerPolicy()
        assertEquals(GpsPowerPolicy.HandoverAction.WINDOW, p.ho(0L, "B", "A"))
        p.onStreamUnavailable(180_000L)
        assertTrue(p.receptionDegraded)
        // Mala recepción: sin ventana de 60 s, pero una antena nueva pide un intento corto sin
        // esperar los 2 minutos del límite compartido.
        assertEquals(GpsPowerPolicy.HandoverAction.BOUNDED_PROBE, p.ho(181_000L, "C", "B"))
        assertFalse(p.streamWanted(smart, false, 182_000L))
        // Un fix nuevo recupera las ventanas.
        p.onFreshFix(200_000L)
        assertEquals(GpsPowerPolicy.HandoverAction.WINDOW, p.ho(230_000L, "F", "E", fix = 200_000L))
    }

    @Test fun `without reception a run of new cells cannot keep the GPS searching`() {
        val p = GpsPowerPolicy()
        p.onStreamUnavailable(0L)
        // Tren en un túnel: una antena nueva cada 10 s durante 10 minutos.
        var probes = 0
        for (i in 0 until 60) {
            val now = 1_000L + i * 10_000L
            if (p.ho(now, "N$i", if (i == 0) null else "N${i - 1}") == GpsPowerPolicy.HandoverAction.BOUNDED_PROBE) {
                p.onProbeStarted(now)
                probes++
            }
            assertFalse(p.streamWanted(smart, false, now + 1))
        }
        // Como mucho uno por minuto: 10 intentos de 20 s en 10 minutos, un tercio del tiempo.
        assertEquals(10, probes)
    }

    @Test fun `ping-pong between two known cells does not chain GPS windows`() {
        val p = GpsPowerPolicy()
        assertEquals(GpsPowerPolicy.HandoverAction.WINDOW, p.ho(0L, "B", "A"))
        // Ventana terminada; quieto en casa, el móvil salta A <-> B cada 20 s.
        var now = 70_000L
        var to = "A"; var from = "B"
        repeat(20) {
            val action = p.ho(now, to, from, fix = 50_000L)
            assertTrue(action != GpsPowerPolicy.HandoverAction.WINDOW)
            assertFalse(p.streamWanted(smart, false, now + 1))
            now += 20_000L; to = from.also { from = to }
        }
    }

    @Test fun `a recent cell only asks for GPS when the last fix is older than 45 s`() {
        val p = GpsPowerPolicy()
        p.ho(0L, "B", "A")
        assertEquals(GpsPowerPolicy.HandoverAction.NONE, p.ho(100_000L, "A", "B", fix = 70_000L))
        assertEquals(GpsPowerPolicy.HandoverAction.PROBE, p.ho(120_000L, "B", "A", fix = 70_000L))
        assertEquals(GpsPowerPolicy.HandoverAction.PROBE, p.ho(130_000L, "A", "B", fix = null))
    }

    @Test fun `all attempts share one budget, whatever started them`() {
        val p = GpsPowerPolicy()
        p.onStreamUnavailable(0L)
        // Caso reproducido: intento por antena nueva en el segundo 110 (dura hasta el 130).
        assertEquals(GpsPowerPolicy.HandoverAction.BOUNDED_PROBE, p.ho(110_000L, "N1", "N0"))
        p.onProbeStarted(110_000L)
        p.onProbeFailed(130_000L)
        // Volver a una antena reciente en el 130 ya no puede iniciar otro intento seguido.
        assertFalse(p.probeAllowed(smart, 130_000L))
        assertEquals(GpsPowerPolicy.HandoverAction.PROBE, p.ho(130_000L, "N0", "N1"))
        assertFalse(p.probeAllowed(smart, 130_000L))
        // Otra antena nueva espera al minuto desde el último inicio, sea del tipo que sea.
        assertEquals(GpsPowerPolicy.HandoverAction.NONE, p.ho(169_999L, "N2", "N0"))
        assertEquals(GpsPowerPolicy.HandoverAction.BOUNDED_PROBE, p.ho(170_000L, "N3", "N2"))
        // Y un intento periódico iniciado también retrasa al siguiente por antena nueva.
        p.onProbeStarted(170_000L)
        assertEquals(GpsPowerPolicy.HandoverAction.NONE, p.ho(200_000L, "N4", "N3"))
    }

    @Test fun `deciding is not starting, only real starts consume the budget`() {
        val p = GpsPowerPolicy()
        p.onStreamUnavailable(0L)
        // Se decide un intento pero no llega a iniciarse (otro ya en marcha, o falla el registro).
        assertEquals(GpsPowerPolicy.HandoverAction.BOUNDED_PROBE, p.ho(10_000L, "A", null))
        assertEquals(GpsPowerPolicy.HandoverAction.BOUNDED_PROBE, p.ho(15_000L, "B", "A"))
    }

    @Test fun `a fresh fix never skips the window of a new cell, so a journey stays covered`() {
        val p = GpsPowerPolicy()
        // Antena nueva justo después de un fix: la ventana se abre igualmente.
        assertEquals(GpsPowerPolicy.HandoverAction.WINDOW, p.ho(10_000L, "B", "A", fix = 9_000L))
        // La ventana acaba de terminar con fixes de hace 1 s: la siguiente antena nueva la reabre.
        assertEquals(GpsPowerPolicy.HandoverAction.WINDOW, p.ho(71_000L, "C", "B", fix = 70_000L))
        assertTrue(p.streamWanted(smart, false, 130_999L))
        assertFalse(p.streamWanted(smart, false, 131_000L))
        // Una antena reciente sí aprovecha el fix fresco.
        assertEquals(GpsPowerPolicy.HandoverAction.NONE, p.ho(140_000L, "B", "C", fix = 130_000L))
    }

    @Test fun `a cell is new again after ten minutes`() {
        val p = GpsPowerPolicy()
        p.ho(0L, "B", "A")
        assertEquals(GpsPowerPolicy.HandoverAction.PROBE, p.ho(GpsPowerPolicy.RECENT_CELL_MS - 1, "A", "B"))
        assertEquals(GpsPowerPolicy.HandoverAction.WINDOW, p.ho(2 * GpsPowerPolicy.RECENT_CELL_MS, "B", "A"))
    }

    @Test fun `continuous and adaptive keep their usual on-demand request`() {
        val p = GpsPowerPolicy()
        assertEquals(GpsPowerPolicy.HandoverAction.SHARED_GATE, p.onHandover(LocationMode.CONTINUOUS, 0L, "B", "A", null))
        assertEquals(GpsPowerPolicy.HandoverAction.SHARED_GATE, p.onHandover(LocationMode.ADAPTIVE, 1L, "C", "B", null))
        assertFalse(p.streamWanted(LocationMode.ADAPTIVE, false, 2L))
    }

    @Test fun `the controller lets a degraded smart handover skip the spacing, nothing else`() {
        val controller = listOf(
            java.io.File("src/main/java/com/alexisgordr/icdetector/service/LocationCollectionController.kt"),
            java.io.File("app/src/main/java/com/alexisgordr/icdetector/service/LocationCollectionController.kt")
        ).first { it.exists() }.readText()
        assertTrue(controller.contains("GpsPowerPolicy.HandoverAction.BOUNDED_PROBE -> {\n                requestPreciseFixOnMain(force = false, newCellHandover = true)"))
        assertEquals(1, Regex("newCellHandover = true").findAll(controller).count())
        assertTrue(controller.contains("if (!powerPolicy.probeAllowed(mode, now, newCellHandover)) return"))
        // Solo cuenta un intento iniciado de verdad: después de registrar el listener sin error.
        val request = controller.substringAfter("private fun requestPreciseFixOnMain(")
        assertTrue(request.indexOf("manager.requestLocationUpdates(") < request.indexOf("powerPolicy.onProbeStarted(now)"))
        // Y el intento por antena nueva dura como mucho 20 s, también el primero del modo.
        assertTrue(request.indexOf("newCellHandover -> GpsPowerPolicy.SAVING_PROBE_TIMEOUT_MS") in 0 until
            request.indexOf("!savingProbeAttempted -> GpsPowerPolicy.INITIAL_PROBE_TIMEOUT_MS"))
        // Sigue habiendo un solo intento a la vez.
        assertTrue(controller.contains("if (!collectionEnabled || !gpsAvailable() || forcedFixListener != null) return"))
    }

    @Test fun `smart schedules from a successful fix and the periodic tick cannot run early`() {
        val p = GpsPowerPolicy()
        assertTrue(p.periodicProbeDue(LocationMode.INTELLIGENT, 0L))
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 0L))
        p.onFreshFix(1_000L)
        assertFalse(p.periodicProbeDue(LocationMode.INTELLIGENT, 45_999L))
        assertTrue(p.periodicProbeDue(LocationMode.INTELLIGENT, 46_000L))
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 46_000L))
    }

    @Test fun `three failed bounded probes pause then retries grow to two five and ten minutes`() {
        val p = GpsPowerPolicy()
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 0L))
        p.onProbeFailed(45_000L)
        assertEquals(90_000L, p.nextProbeAtMs)
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 90_000L))
        p.onProbeFailed(110_000L)
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 155_000L))
        p.onProbeFailed(175_000L)
        assertTrue(p.receptionDegraded)
        assertEquals(295_000L, p.nextProbeAtMs)
        assertFalse(p.periodicProbeDue(LocationMode.INTELLIGENT, 294_999L))
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 295_000L))
        p.onProbeFailed(315_000L)
        assertEquals(615_000L, p.nextProbeAtMs)
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 615_000L))
        p.onProbeFailed(635_000L)
        assertEquals(1_235_000L, p.nextProbeAtMs)
        p.onProbeFailed(1_255_000L)
        assertEquals(1_855_000L, p.nextProbeAtMs)
        assertEquals(5, p.consecutiveFailures)
    }

    @Test fun `repeated handovers and alarms cannot clear the degraded state or keep GPS on`() {
        val p = GpsPowerPolicy()
        p.onStreamUnavailable(180_000L)
        assertTrue(p.tryStartProbe(LocationMode.ADAPTIVE, 190_000L))
        p.onProbeFailed(210_000L)
        for (time in 211_000L..309_000L step 1_000L) {
            assertFalse(p.tryStartProbe(LocationMode.INTELLIGENT, time))
        }
        assertTrue(p.receptionDegraded)
        assertEquals(510_000L, p.nextProbeAtMs)
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 310_000L))
        assertEquals(4, p.consecutiveFailures)
        assertEquals(510_000L, p.nextProbeAtMs)
    }

    @Test fun `adaptive pauses even with screen on then resumes only after a usable fresh fix`() {
        val p = GpsPowerPolicy()
        assertTrue(p.streamWanted(LocationMode.ADAPTIVE, true))
        p.onStreamUnavailable(180_000L)
        assertFalse(p.streamWanted(LocationMode.ADAPTIVE, true))
        assertTrue(p.periodicProbeDue(LocationMode.ADAPTIVE, 300_000L))
        p.onFreshFix(301_000L)
        assertFalse(p.receptionDegraded)
        assertTrue(p.streamWanted(LocationMode.ADAPTIVE, true))
        assertFalse(p.streamWanted(LocationMode.ADAPTIVE, false))
        assertFalse(p.periodicProbeDue(LocationMode.ADAPTIVE, 400_000L))
        assertTrue(p.periodicProbeDue(LocationMode.INTELLIGENT, 346_000L))
    }

    @Test fun `continuous retains the permanent stream regardless of degraded reception`() {
        val p = GpsPowerPolicy()
        p.onStreamUnavailable(180_000L)
        assertTrue(p.streamWanted(LocationMode.CONTINUOUS, false))
        assertTrue(p.streamWanted(LocationMode.CONTINUOUS, true))
        assertFalse(p.periodicProbeDue(LocationMode.CONTINUOUS, 10_000_000L))
    }

    @Test fun `screen and alarm requests share the saving mode gate`() {
        val p = GpsPowerPolicy()
        assertTrue(p.tryStartProbe(LocationMode.ADAPTIVE, 0L))
        assertFalse(p.tryStartProbe(LocationMode.ADAPTIVE, 30_000L))
        assertFalse(p.tryStartProbe(LocationMode.ADAPTIVE, 59_999L))
        assertTrue(p.tryStartProbe(LocationMode.ADAPTIVE, 60_000L))
    }

    @Test fun `stream watchdog includes device sleep and uses the last real fix`() {
        val p = GpsPowerPolicy()
        assertFalse(p.streamHasStalled(0L, null, 179_999L))
        assertTrue(p.streamHasStalled(0L, null, 180_000L))
        assertFalse(p.streamHasStalled(0L, 170_000L, 180_000L))
        assertTrue(p.streamHasStalled(0L, 170_000L, 350_000L))
        assertFalse(p.streamHasStalled(500_000L, 170_000L, 510_000L))
    }

    @Test fun `age is measured monotonically and stale or future fixes cannot pass`() {
        assertTrue(GpsFixAge.isRecent(1_000_000L, 999_000L, 120_000L))
        assertFalse(GpsFixAge.isRecent(1_000_000L, 880_000L, 120_000L))
        assertFalse(GpsFixAge.isRecent(1_000_000L, 1_001_000L, 120_000L))
        assertFalse(GpsFixAge.isRecent(1_000_000L, 950_000L, 10_000L))
    }
}
