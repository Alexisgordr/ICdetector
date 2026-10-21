package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.1.0 (#47) — En GSM el índice de TA va en pasos de 554 m: el 0 significa «a menos de 554 m», no
 * 0 m. H6 no puede afirmar los 100 m de su rama de proximidad y queda N/A con motivo `ta_gsm`.
 * LTE no cambia.
 */
class GsmTimingAdvanceH6Test {
    private fun cell(radio: RadioTech, unit: TimingAdvanceUnit, ta: Int?, dbm: Int = -55) = CellData(
        isRegistered = true, networkType = if (radio == RadioTech.GSM) "2G" else "4G LTE", cellId = "4242", mnc = "07",
        tac = "100", dbm = dbm, mcc = "214", radioTech = radio, timingAdvance = ta, timingAdvanceUnit = unit
    )
    private fun analyze(c: CellData) = ThreatAnalyzer.analyzeThreats(c, emptyList(), emptyList(), null).heuristicReport

    @Test fun `a strong GSM cell at index 0 is N-A with its reason, not a proximity failure`() {
        val report = analyze(cell(RadioTech.GSM, TimingAdvanceUnit.GSM_INDEX, 0))
        assertEquals(HeuristicStatus.NOT_EVALUATED, report.taDistance)
        assertTrue(report.taDistanceGsmCoarse)
        assertTrue("H6(ta_gsm)" in report.notEvaluatedIds().split(";"))
        assertEquals(HeuristicReport.H6_GSM_TA_TEXT, report.abstentionText("H6"))
        val analyzed = ThreatAnalyzer.analyzeThreats(cell(RadioTech.GSM, TimingAdvanceUnit.GSM_INDEX, 0), emptyList(), emptyList(), null)
        assertFalse(analyzed.suspiciousReason.orEmpty().contains("Proximidad anómala (TA)"))
    }

    @Test fun `any GSM index skips the proximity check`() {
        listOf(1, 5, 219).forEach { ta ->
            assertEquals("TA=$ta", HeuristicStatus.NOT_EVALUATED, analyze(cell(RadioTech.GSM, TimingAdvanceUnit.GSM_INDEX, ta)).taDistance)
        }
        // Sin TA no hay motivo GSM: la abstención de siempre.
        val noTa = analyze(cell(RadioTech.GSM, TimingAdvanceUnit.GSM_INDEX, null))
        assertFalse(noTa.taDistanceGsmCoarse)
        assertNull(noTa.abstentionCode("H6"))
    }

    @Test fun `LTE keeps its proximity check`() {
        assertEquals(HeuristicStatus.FAILED, analyze(cell(RadioTech.LTE, TimingAdvanceUnit.LTE_INDEX, 0)).taDistance)
        assertEquals(HeuristicStatus.PASSED, analyze(cell(RadioTech.LTE, TimingAdvanceUnit.LTE_INDEX, 10)).taDistance)
        assertFalse(analyze(cell(RadioTech.LTE, TimingAdvanceUnit.LTE_INDEX, 0)).taDistanceGsmCoarse)
    }

    @Test fun `the diagnostic explains the reason`() {
        val c = cell(RadioTech.GSM, TimingAdvanceUnit.GSM_INDEX, 0)
        val analyzed = ThreatAnalyzer.analyzeThreats(c, emptyList(), emptyList(), null)
        val inputs = DiagnosticEngine.Inputs(neighborCount = 0, wifiActive = false, locationAvailable = true, historyWithLocation = 5,
            previousBandAvailable = false, signalBaseline = null, rfFingerprint = null, rfStability = null, reputation = null)
        assertEquals(DiagnosticEngine.H6_GSM_TA_EXPLANATION, DiagnosticEngine.explain(analyzed, inputs).first { it.id == 6 }.explanation)
    }
}
