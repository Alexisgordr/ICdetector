package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
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

    /** La cadena del servicio: tracker → TemporalConfidence, con la marca de retención neutra. */
    private fun chain(t: ThreatEpisodeTracker, confirmation: TemporalConfidence) =
        { c: CellData, token: Long ->
            val e = t.apply(c, token, true)
            confirmation.apply(e.cell, token, true, heldWithoutEvidence = e.heldWithoutEvidence)
        }

    @Test fun `two sub-threshold failures and clean held cycles never confirm`() {
        val t = tracker()
        val step = chain(t, TemporalConfidence(elapsedRealtimeMs = { now }))
        val rf = cell(HeuristicReport(powerJump = HeuristicStatus.FAILED))
        val identity = cell(HeuristicReport(tacDeviation = HeuristicStatus.FAILED))
        val normal = cell(HeuristicReport()).copy(securityScore = 100, suspiciousReason = null)

        assertFalse(step(rf, 0L).isSuspicious)
        now += 5_000
        val promoted = step(identity, 5_000L)
        assertEquals(1, promoted.temporalProgress.phase)
        now += 5_000
        val held1 = step(normal, 10_000L)
        now += 5_000
        val held2 = step(normal, 15_000L)
        assertEquals("la retención sin evidencia no avanza la racha", 1, held2.temporalProgress.phase)
        assertFalse(held1.isSuspicious)
        assertFalse("antes: alarma a los 15 s con dos ciclos limpios", held2.isSuspicious)
        // Pasada la retención, la racha cae.
        now += 30_000
        assertEquals(0, step(normal, 45_000L).temporalProgress.phase)
    }

    @Test fun `held clean cycles bridge an episode without breaking the streak`() {
        val t = tracker()
        val step = chain(t, TemporalConfidence(elapsedRealtimeMs = { now }))
        val rf = cell(HeuristicReport(powerJump = HeuristicStatus.FAILED))
        val identity = cell(HeuristicReport(tacDeviation = HeuristicStatus.FAILED))
        val normal = cell(HeuristicReport()).copy(securityScore = 100, suspiciousReason = null)

        step(rf, 10_000L)
        now += 10_000
        assertEquals(1, step(identity, 20_000L).temporalProgress.phase)
        now += 10_000
        assertEquals("el hueco limpio no rompe", 1, step(normal, 30_000L).temporalProgress.phase)
        now += 10_000
        assertEquals(2, step(rf, 40_000L).temporalProgress.phase)
        now += 10_000
        assertEquals(2, step(normal, 50_000L).temporalProgress.phase)
        now += 10_000
        val third = step(identity, 60_000L)
        assertEquals(3, third.temporalProgress.phase)
        assertTrue("tres observaciones promovidas con evidencia confirman", third.isSuspicious)
    }

    @Test fun `a held promotion is flagged only when the observation adds no evidence`() {
        val t = tracker()
        t.apply(cell(HeuristicReport(powerJump = HeuristicStatus.FAILED)), 1L, true)
        now += 5_000
        val opening = t.apply(cell(HeuristicReport(tacDeviation = HeuristicStatus.FAILED)), 2L, true)
        assertTrue(opening.promoted)
        assertFalse(opening.heldWithoutEvidence)
        now += 5_000
        val clean = cell(HeuristicReport()).copy(securityScore = 100, suspiciousReason = null)
        val held = t.apply(clean, 3L, true)
        assertTrue(held.promoted)
        assertTrue(held.heldWithoutEvidence)
        now += 5_000
        val repeated = t.apply(cell(HeuristicReport(tacDeviation = HeuristicStatus.FAILED)), 3L, true)
        assertTrue("un token repetido no es evidencia nueva", repeated.heldWithoutEvidence)
        now += 5_000
        val cached = t.apply(cell(HeuristicReport(tacDeviation = HeuristicStatus.FAILED)), 4L, false)
        assertTrue("una entrega cacheada no es evidencia nueva", cached.heldWithoutEvidence)
    }

    @Test fun `a cell suspicious on its own is never marked as held`() {
        val t = tracker()
        t.apply(cell(HeuristicReport(powerJump = HeuristicStatus.FAILED)), 1L, true)
        now += 5_000
        t.apply(cell(HeuristicReport(tacDeviation = HeuristicStatus.FAILED)), 2L, true)
        now += 5_000
        val own = cell(HeuristicReport()).copy(isSuspicious = true, securityScore = 40)
        val e = t.apply(own, 3L, true)
        assertFalse(e.promoted)
        assertFalse(e.heldWithoutEvidence)
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
