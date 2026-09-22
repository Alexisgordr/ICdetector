package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Test

/** Physical heuristics must ignore the user-facing network label. */
class RadioTechDecisionRegressionTest {
    private fun lte(label: String, arfcn: Int = 300_000, band: Int? = null, dbm: Int = -70) = CellData(
        isRegistered = true,
        networkType = label,
        cellId = "100",
        mnc = "07",
        tac = "31601",
        dbm = dbm,
        mcc = "214",
        radioTech = RadioTech.LTE,
        arfcn = arfcn,
        band = band
    )

    private fun analyze(cell: CellData, previousBand: Int? = null, previousDbm: Int? = null) =
        ThreatAnalyzer.analyzeThreats(
            active = cell,
            neighbors = emptyList(),
            isHardwareCipheringActive = true,
            cellChangeHistory = emptyList(),
            currentLocation = null,
            previousBand = previousBand,
            previousDbm = previousDbm
        )

    @Test fun `H8 is identical for LTE and NSA display labels on the same LTE cell`() {
        val lteLabel = analyze(lte("4G LTE")).heuristicReport.arfcnSanity
        val nsaLabel = analyze(lte("5G NR (NSA)")).heuristicReport.arfcnSanity
        assertEquals(lteLabel, nsaLabel)
    }

    @Test fun `H11 threshold is identical for LTE and NSA display labels`() {
        val threshold = ThreatAnalyzer.getDynamicLocationThreshold(emptyList(), RadioTech.LTE)
        assertEquals(25_000.0, threshold, 0.0)
        // Both labels above carry RadioTech.LTE, so H11 consumes this exact value.
        assertEquals(lte("4G LTE").radioTech, lte("5G NR (NSA)").radioTech)
    }

    @Test fun `H14 is identical for LTE and NSA display labels on the same LTE cell`() {
        val a = analyze(lte("4G LTE", arfcn = 6300, band = 20), previousBand = 7, previousDbm = -80)
        val b = analyze(lte("5G NR (NSA)", arfcn = 6300, band = 20), previousBand = 7, previousDbm = -80)
        assertEquals(a.heuristicReport.bandDowngrade, b.heuristicReport.bandDowngrade)
        assertEquals(a.securityScore, b.securityScore)
        assertEquals(a.suspiciousReason, b.suspiciousReason)
    }
}
