package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (#20) — Dos ciclos sospechosos, un hueco sin cobertura y otro ciclo de la misma celda no
 * completan una confirmación como si fueran seguidos.
 */
class ContinuityGapTest {
    private var now = 1_000_000L
    private val suspicious = CellData(
        isRegistered = true, networkType = "4G LTE", cellId = "100", mnc = "07", tac = "1", dbm = -70,
        mcc = "214", radioTech = RadioTech.LTE, isSuspicious = true, suspiciousReason = "Desviación TAC",
        heuristicReport = HeuristicReport(tacDeviation = HeuristicStatus.FAILED, pingPong = HeuristicStatus.FAILED)
    )

    private fun confidence() = TemporalConfidence(confirmationCycles = 3, elapsedRealtimeMs = { now })

    @Test fun `three consecutive suspicious cycles still confirm`() {
        val t = confidence()
        var token = 10_000L
        repeat(2) { t.apply(suspicious, token); token += 3_000; now += 3_000 }
        assertTrue(t.apply(suspicious, token).isSuspicious)
    }

    @Test fun `a signal loss restarts the streak`() {
        val t = confidence()
        var token = 10_000L
        repeat(2) { t.apply(suspicious, token); token += 3_000; now += 3_000 }
        t.interrupt()                                   // lista vacía / abstención / modo avión
        val after = t.apply(suspicious, token)
        assertFalse(after.isSuspicious)
        assertEquals(1, after.temporalProgress.phase)
    }

    @Test fun `a ten-minute gap restarts the streak even without an explicit loss`() {
        val t = confidence()
        var token = 10_000L
        repeat(2) { t.apply(suspicious, token); token += 3_000; now += 3_000 }
        now += 10 * 60_000L; token += 10 * 60_000L
        val after = t.apply(suspicious, token)
        assertFalse(after.isSuspicious)
        assertEquals(1, after.temporalProgress.phase)
    }

    @Test fun `a short normal gap does not restart the streak`() {
        val t = confidence()
        var token = 10_000L
        repeat(2) { t.apply(suspicious, token); token += 10_000; now += 10_000 }   // pantalla apagada
        assertTrue(t.apply(suspicious, token).isSuspicious)
    }

    @Test fun `episode evidence from before a loss is not correlated with evidence after it`() {
        val tracker = ThreatEpisodeTracker(nowMs = { now })
        val ping = suspicious.copy(isSuspicious = false,
            heuristicReport = HeuristicReport(pingPong = HeuristicStatus.FAILED))
        val tac = suspicious.copy(isSuspicious = false,
            heuristicReport = HeuristicReport(tacDeviation = HeuristicStatus.FAILED))
        tracker.apply(ping, 1L)
        now += 5_000
        tracker.interrupt()
        assertFalse("sin la evidencia de antes del hueco no hay dos familias", tracker.apply(tac, 2L).promoted)
    }

    @Test fun `the service breaks continuity on signal loss and leaves a terminal line`() {
        val service = listOf(
            File("src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt")
        ).first { it.exists() }.readText()
        assertTrue(service.contains("val continuityBroken = signalLosses != acknowledgedSignalLosses"))
        assertTrue(service.contains("temporalConfidence.interrupt()"))
        assertTrue(service.contains("threatEpisodeTracker.interrupt()"))
        assertTrue(service.contains("prevBand = null"))
        assertTrue(service.contains("collectionGeneration.lose(signalLost = false)"))
    }
}
