package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.1.0 (#43) — H6 es conservador mientras no se sabe si el módem mide el TA: con la unidad sin
 * veredicto, N/A `ta_sin_verificar`; con REAL, evalúa; con STUB, N/A `ta_stub`. Mismo camino que el
 * servicio: observe() y después annotate().
 */
class H6TimingAdvanceVerdictTest {
    private fun cell(enb: Long, ta: Int, dbm: Int = -55) = CellData(isRegistered = true, networkType = "4G LTE",
        cellId = ((enb shl 8) + 1).toString(), mnc = "07", tac = "100", dbm = dbm, mcc = "214", radioTech = RadioTech.LTE,
        timingAdvance = ta, timingAdvanceUnit = TimingAdvanceUnit.LTE_INDEX)

    /** Lo que hace el servicio con cada lectura. */
    private fun TimingAdvanceSanity.deliver(c: CellData): HeuristicReport {
        observe(c.timingAdvanceUnit, TimingAdvanceSanity.evidenceKey(c), c.timingAdvance)
        return ThreatAnalyzer.analyzeThreats(annotate(c), emptyList(), emptyList(), null).heuristicReport
    }

    @Test fun `a constant zero stream before the third site is ta_sin_verificar, then ta_stub`() {
        val s = TimingAdvanceSanity()
        listOf(1001L, 1002L).forEach { enb ->
            val r = s.deliver(cell(enb, 0))
            assertEquals(HeuristicStatus.NOT_EVALUATED, r.taDistance)
            assertTrue("H6(ta_sin_verificar)" in r.notEvaluatedIds().split(";"))
            assertEquals(HeuristicReport.H6_TA_UNVERIFIED_TEXT, r.abstentionText("H6"))
        }
        // Antes de 3.1.0 esas dos lecturas fallaban H6 (TA 0 y −55 dBm).
        assertEquals(HeuristicStatus.FAILED, ThreatAnalyzer.analyzeThreats(cell(1001L, 0), emptyList(), emptyList(), null).heuristicReport.taDistance)
        val third = s.deliver(cell(1003L, 0))
        assertEquals(HeuristicStatus.NOT_EVALUATED, third.taDistance)
        assertTrue("H6(ta_stub)" in third.notEvaluatedIds().split(";"))
        assertFalse(third.taDistanceUnverified)
    }

    @Test fun `once the modem is REAL H6 is evaluated again`() {
        val s = TimingAdvanceSanity()
        s.deliver(cell(2001L, 3)); s.deliver(cell(2002L, 5))
        assertEquals(TimingAdvanceSanity.Verdict.REAL, s.verdict(TimingAdvanceUnit.LTE_INDEX))
        val r = s.deliver(cell(2003L, 0))
        assertEquals("TA 0 y −55 dBm con un módem que mide", HeuristicStatus.FAILED, r.taDistance)
        assertEquals(HeuristicStatus.PASSED, s.deliver(cell(2004L, 10)).taDistance)
    }

    @Test fun `annotate marks only readings with a TA of a unit without verdict`() {
        val s = TimingAdvanceSanity()
        assertTrue(s.annotate(cell(1L, 0)).timingAdvanceUnverified)
        assertFalse("sin TA no hay nada que verificar", s.annotate(cell(1L, 0).copy(timingAdvance = null)).timingAdvanceUnverified)
        assertFalse("una unidad ya concluida (STUB_ZERO) no está pendiente",
            s.annotate(cell(1L, 0).copy(timingAdvanceUnit = TimingAdvanceUnit.STUB_ZERO)).timingAdvanceUnverified)
        // Sin la marca (filas antiguas, CSV, escenarios) H6 evalúa como antes.
        assertEquals(HeuristicStatus.FAILED, ThreatAnalyzer.analyzeThreats(cell(1L, 0), emptyList(), emptyList(), null).heuristicReport.taDistance)
    }

    @Test fun `the diagnostic explains both reasons`() {
        val inputs = DiagnosticEngine.Inputs(neighborCount = 0, wifiActive = false, locationAvailable = true, historyWithLocation = 5,
            previousBandAvailable = false, signalBaseline = null, rfFingerprint = null, rfStability = null, reputation = null)
        fun explain(c: CellData) = DiagnosticEngine.explain(ThreatAnalyzer.analyzeThreats(c, emptyList(), emptyList(), null), inputs)
            .first { it.id == 6 }.explanation
        assertEquals(DiagnosticEngine.H6_TA_UNVERIFIED_EXPLANATION, explain(cell(1L, 0).copy(timingAdvanceUnverified = true)))
        assertEquals(DiagnosticEngine.H6_TA_STUB_EXPLANATION, explain(cell(1L, 0).copy(timingAdvanceUnit = TimingAdvanceUnit.STUB_ZERO)))
    }
}
