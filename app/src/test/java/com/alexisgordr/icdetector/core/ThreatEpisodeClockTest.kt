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
 * 3.0 — ThreatEpisodeTracker con el reloj inyectado: la evidencia caduca a los 90 s y la promoción a
 * los 30 s, también cuando el tiempo avanza de golpe por un sueño profundo (elapsedRealtime lo cuenta).
 */
class ThreatEpisodeClockTest {
    private var now = 1_000_000L
    private val tracker = ThreatEpisodeTracker(nowMs = { now })   // ventanas por defecto: 90 s y 30 s

    private fun cell(report: HeuristicReport = HeuristicReport()) = CellData(
        isRegistered = true, networkType = "4G LTE", cellId = "100", mnc = "07", tac = "31601", dbm = -75,
        mcc = "214", radioTech = RadioTech.LTE, securityScore = 85, heuristicReport = report
    )
    private val rf = HeuristicReport(powerJump = HeuristicStatus.FAILED)
    private val identity = HeuristicReport(tacDeviation = HeuristicStatus.FAILED)

    private fun promote(): Long {
        tracker.apply(cell(rf), 1L)
        now += 5_000
        assertTrue(tracker.apply(cell(identity), 2L).promoted)
        return 3L
    }

    @Test fun `the promotion is held up to 30 s without failures and then expires`() {
        var token = promote()
        now += 29_000
        assertTrue("dentro de 30 s se mantiene sin fallos", tracker.apply(cell(), token++).promoted)
        now += 2_000
        assertFalse("pasados 30 s caduca", tracker.apply(cell(), token).promoted)
    }

    @Test fun `evidence expires after 90 s and cannot combine with a later failure`() {
        tracker.apply(cell(rf), 1L)
        now += 89_000
        assertTrue("dentro de 90 s se combina", tracker.apply(cell(identity), 2L).promoted)

        val fresh = ThreatEpisodeTracker(nowMs = { now })
        fresh.apply(cell(rf), 10L)
        now += 91_000
        val later = fresh.apply(cell(identity), 11L)
        assertFalse("pasados 90 s, no", later.promoted)
        assertEquals(setOf(ThreatEpisodeTracker.Family.IDENTITY), later.families)
    }

    @Test fun `a deep-sleep jump expires both the promotion and the evidence`() {
        val token = promote()
        now += 10 * 60_000L               // 10 min dormido: elapsedRealtime avanza de golpe
        val after = tracker.apply(cell(), token)
        assertFalse(after.promoted)
        assertFalse(after.cell.isSuspicious)
        assertFalse("sin evidencia viva tras el sueño", after.watching)
        // Un fallo nuevo de una sola familia no se combina con la evidencia de antes del sueño.
        assertFalse(tracker.apply(cell(rf), token + 1).promoted)
    }

    @Test fun `the service injects elapsedRealtime and keeps the default windows`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val service = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val decl = service.substringAfter("private val threatEpisodeTracker = ").substringBefore("\n    )")
        assertTrue(decl.contains("nowMs = SystemClock::elapsedRealtime"))
        assertFalse("sin ventanas nuevas", decl.contains("windowMs") || decl.contains("promotionHoldMs"))
        val tracker = main.resolve("com/alexisgordr/icdetector/core/ThreatEpisodeTracker.kt").readText()
        assertTrue(tracker.contains("private val windowMs: Long = 90_000L"))
        assertTrue(tracker.contains("private val promotionHoldMs: Long = 30_000L"))
    }
}
