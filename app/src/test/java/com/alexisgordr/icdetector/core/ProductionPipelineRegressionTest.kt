package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.LocalCellTrustEvidence
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression over the same pure decision chain orchestrated by MiniICService. */
class ProductionPipelineRegressionTest {
    private fun serving() = CellData(
        isRegistered = true, networkType = "5G NR (NSA)", cellId = "100", mnc = "07",
        tac = "31601", dbm = -60, mcc = "214", radioTech = RadioTech.LTE,
        arfcn = 1500, pci = 10
    )

    private fun neighbor() = CellData(
        isRegistered = false, networkType = "4G LTE", cellId = "200", mnc = "07",
        tac = "31601", dbm = -115, mcc = "214", radioTech = RadioTech.LTE,
        arfcn = 1600, pci = 11
    )

    @Test fun `production decision chain preserves temporal confirmation`() {
        val episode = ThreatEpisodeTracker(nowMs = { 1_000L })
        val temporal = TemporalConfidence(elapsedRealtimeMs = { 10_000L })
        val phases = (1L..3L).map { token ->
            val analyzed = ThreatAnalyzer.analyzeThreats(
                active = serving(), neighbors = listOf(neighbor()),
                isHardwareCipheringActive = true, cellChangeHistory = emptyList(),
                currentLocation = null
            )
            val trusted = LocalCellTrustEngine.apply(analyzed, LocalCellTrustEvidence())
            val correlated = episode.apply(trusted, token * 3_000L, true).cell
            temporal.apply(correlated, token * 3_000L, true)
        }

        assertEquals(listOf(1, 2, 3), phases.map { it.temporalProgress.phase })
        assertFalse(phases[0].isSuspicious)
        assertFalse(phases[1].isSuspicious)
        assertTrue(phases[2].isSuspicious)
    }
}
