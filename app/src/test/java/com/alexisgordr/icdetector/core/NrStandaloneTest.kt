package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0 (#8) — Servidora 5G SA (NR): qué reglas se evalúan y cómo. */
class NrStandaloneTest {
    private val n78 = 632_448     // 3486,72 MHz (n77/n78): alta
    private val n28 = 151_600     // 758 MHz (n14/n28/n67): baja

    private fun nr(arfcn: Int?, dbm: Int = -75, ta: Int? = null) = CellData(
        isRegistered = true, networkType = "5G SA", cellId = "1234567890", mnc = "07", tac = "1",
        dbm = dbm, mcc = "214", radioTech = RadioTech.NR, arfcn = arfcn,
        timingAdvance = ta, timingAdvanceUnit = if (ta != null) TimingAdvanceUnit.NR_RAW else TimingAdvanceUnit.UNKNOWN
    )

    private fun analyze(cell: CellData, previousNrArfcn: Int? = null, previousDbm: Int? = null, previousBand: Int? = null,
                        recent: List<Int> = emptyList()) =
        ThreatAnalyzer.analyzeThreats(
            active = cell, neighbors = emptyList(), isHardwareCipheringActive = true,
            cellChangeHistory = emptyList(), currentLocation = null,
            previousBand = previousBand, previousDbm = previousDbm, previousNrArfcn = previousNrArfcn,
            recentRegisteredDbm = recent
        ).heuristicReport

    @Test fun `nr-arfcn gives the exact downlink frequency`() {
        assertEquals(3486.72, NrFrequency.dlMhz(n78)!!, 0.001)
        assertEquals(758.0, NrFrequency.dlMhz(n28)!!, 0.001)
        assertEquals(24_250.08, NrFrequency.dlMhz(2_016_667)!!, 0.001)
        assertNull(NrFrequency.dlMhz(0))
        assertNull(NrFrequency.dlMhz(3_279_166))
        assertNull(NrFrequency.dlMhz(Int.MAX_VALUE))
        assertTrue(NrFrequency.isHigh(n78)); assertTrue(NrFrequency.isLow(n28))
    }

    @Test fun `h8 passes on a normal nr-arfcn`() {
        assertEquals(HeuristicStatus.PASSED, analyze(nr(n78)).arfcnSanity)
    }

    @Test fun `h14 is n-a without a previous nr carrier`() {
        assertEquals(HeuristicStatus.NOT_EVALUATED, analyze(nr(n28)).bandDowngrade)
    }

    @Test fun `h14 flags a forced high to low downgrade on 5g sa`() {
        val report = analyze(nr(n28, dbm = -75), previousNrArfcn = n78, previousDbm = -80)
        assertEquals(HeuristicStatus.FAILED, report.bandDowngrade)
    }

    @Test fun `h14 does not flag a coverage fallback or a degrading signal on 5g sa`() {
        assertEquals(HeuristicStatus.PASSED,
            analyze(nr(n28, dbm = -105), previousNrArfcn = n78, previousDbm = -80).bandDowngrade)
        assertEquals(HeuristicStatus.PASSED,
            analyze(nr(n28, dbm = -75), previousNrArfcn = n78, previousDbm = -80, recent = listOf(-60, -72, -84, -96)).bandDowngrade)
        assertEquals(HeuristicStatus.PASSED,
            analyze(nr(n78, dbm = -75), previousNrArfcn = n28, previousDbm = -80).bandDowngrade)
    }

    @Test fun `a change between lte and nr is not evaluated`() {
        // Venía de LTE B7 (alta) y la servidora ahora es NR: no hay NR-ARFCN anterior.
        assertEquals(HeuristicStatus.NOT_EVALUATED, analyze(nr(n28), previousBand = 7, previousDbm = -80).bandDowngrade)
    }

    @Test fun `h6 stays n-a with nr timing advance`() {
        // El TA de NR (NR_RAW) no tiene una conversión a metros defendible: H6 se abstiene.
        assertEquals(HeuristicStatus.NOT_EVALUATED, analyze(nr(n78, ta = 3)).taDistance)
        assertNull(TimingAdvanceUnit.NR_RAW.toMeters(3))
        assertFalse(NrFrequency.isLow(0))
    }
}
