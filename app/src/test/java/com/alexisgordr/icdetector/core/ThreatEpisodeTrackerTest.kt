package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreatEpisodeTrackerTest {
    private var now = 0L
    private fun tracker() = ThreatEpisodeTracker(windowMs = 90_000L, promotionHoldMs = 30_000L) { now }

    private fun cell(report: HeuristicReport, cid: String = "100") = CellData(
        isRegistered = true, networkType = "4G LTE", cellId = cid, mnc = "07",
        tac = "31601", dbm = -75, mcc = "214", radioTech = RadioTech.LTE,
        securityScore = 85, suspiciousReason = "[sub-umbral] evidencia", heuristicReport = report
    )

    @Test fun `one correlated family never promotes an episode`() {
        val t = tracker()
        val rf = HeuristicReport(
            isolatedCell = HeuristicStatus.FAILED,
            powerJump = HeuristicStatus.FAILED,
            ghostNeighbors = HeuristicStatus.FAILED
        )
        repeat(4) { i ->
            now += 5_000
            assertFalse(t.apply(cell(rf), i.toLong(), true).promoted)
        }
    }

    @Test fun `two independent families on fresh observations promote`() {
        val t = tracker()
        val rf = HeuristicReport(powerJump = HeuristicStatus.FAILED)
        val identity = HeuristicReport(tacDeviation = HeuristicStatus.FAILED)
        assertFalse(t.apply(cell(rf), 1L, true).promoted)
        now += 10_000
        val result = t.apply(cell(identity), 2L, true)
        assertTrue(result.promoted)
        assertTrue(result.cell.isSuspicious)
        assertTrue(result.cell.securityScore < 70)
    }

    @Test fun `duplicate modem delivery cannot manufacture an episode`() {
        val t = tracker()
        val both = HeuristicReport(
            powerJump = HeuristicStatus.FAILED,
            tacDeviation = HeuristicStatus.FAILED
        )
        assertFalse(t.apply(cell(both), 7L, true).promoted)
        now += 5_000
        assertFalse(t.apply(cell(both), 7L, true).promoted)
    }

    @Test fun `expired evidence cannot combine with a later anomaly`() {
        val t = tracker()
        assertFalse(t.apply(cell(HeuristicReport(powerJump = HeuristicStatus.FAILED)), 1L, true).promoted)
        now += 91_000
        assertFalse(t.apply(cell(HeuristicReport(tacDeviation = HeuristicStatus.FAILED)), 2L, true).promoted)
    }

    @Test fun `evidence from different identities is not fused into an accusation`() {
        val t = tracker()
        assertFalse(t.apply(cell(HeuristicReport(powerJump = HeuristicStatus.FAILED), "100"), 1L, true).promoted)
        now += 5_000
        assertFalse(t.apply(cell(HeuristicReport(tacDeviation = HeuristicStatus.FAILED), "200"), 2L, true).promoted)
    }

    @Test fun `episode promotion still requires temporal confirmation`() {
        val t = tracker()
        val confirmation = TemporalConfidence(
            confirmationCycles = 3,
            minObservationSpacingMs = 0L,
            elapsedRealtimeMs = { now }
        )
        val rf = cell(HeuristicReport(powerJump = HeuristicStatus.FAILED))
        val identity = cell(HeuristicReport(tacDeviation = HeuristicStatus.FAILED))

        assertFalse(confirmation.apply(t.apply(rf, 1L, true).cell, 1L).isSuspicious)
        now += 10_000
        assertFalse(confirmation.apply(t.apply(identity, 2L, true).cell, 2L).isSuspicious)
        now += 10_000
        assertFalse(confirmation.apply(t.apply(identity, 3L, true).cell, 3L).isSuspicious)
        now += 10_000
        assertTrue(confirmation.apply(t.apply(identity, 4L, true).cell, 4L).isSuspicious)
    }

    @Test fun `promotion bridges normal screen-off cycles long enough to confirm`() {
        val t = tracker()
        val confirmation = TemporalConfidence(elapsedRealtimeMs = { now })
        val rf = cell(HeuristicReport(powerJump = HeuristicStatus.FAILED))
        val identity = cell(HeuristicReport(tacDeviation = HeuristicStatus.FAILED))
        val normal = cell(HeuristicReport()).copy(securityScore = 100, suspiciousReason = null)

        assertFalse(confirmation.apply(t.apply(rf, 10_000L, true).cell, 10_000L).isSuspicious)
        now += 10_000
        assertFalse(confirmation.apply(t.apply(identity, 20_000L, true).cell, 20_000L).isSuspicious)
        now += 10_000
        assertFalse(confirmation.apply(t.apply(normal, 30_000L, true).cell, 30_000L).isSuspicious)
        now += 10_000
        assertTrue(confirmation.apply(t.apply(normal, 40_000L, true).cell, 40_000L).isSuspicious)
    }

    @Test fun `promotion bridge expires instead of lasting for the correlation window`() {
        val t = tracker()
        t.apply(cell(HeuristicReport(powerJump = HeuristicStatus.FAILED)), 10_000L, true)
        now += 10_000
        assertTrue(t.apply(cell(HeuristicReport(tacDeviation = HeuristicStatus.FAILED)), 20_000L, true).promoted)
        now += 30_001
        val normal = cell(HeuristicReport()).copy(securityScore = 100, suspiciousReason = null)
        assertFalse(t.apply(normal, 50_001L, true).promoted)
    }

    @Test fun `handover cancels a held promotion immediately`() {
        val t = tracker()
        t.apply(cell(HeuristicReport(powerJump = HeuristicStatus.FAILED), "100"), 10_000L, true)
        now += 10_000
        assertTrue(t.apply(cell(HeuristicReport(tacDeviation = HeuristicStatus.FAILED), "100"), 20_000L, true).promoted)
        now += 3_000
        val other = cell(HeuristicReport(), "200").copy(securityScore = 100, suspiciousReason = null)
        assertFalse(t.apply(other, 23_000L, true).promoted)
    }
}
