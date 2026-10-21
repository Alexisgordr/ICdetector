package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HeuristicStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.1.0 (#46) — H11 solo usa un fix con precisión conocida de 100 m o menos; si no, se abstiene con
 * `H11(fix_impreciso)`. Como en MobileCellIdFixAgeTest, la JVM no puede crear un Location: la
 * decisión se prueba aquí y el cableado del analizador y del servicio, en su código.
 */
class MobileCellIdFixAccuracyTest {
    @Test fun `up to 100 m of known accuracy is usable, worse or unknown is not`() {
        assertTrue(MobileCellIdFixAge.accurate(0f))
        assertTrue(MobileCellIdFixAge.accurate(100f))
        assertFalse(MobileCellIdFixAge.accurate(100.1f))
        assertFalse("sin precisión", MobileCellIdFixAge.accurate(null))
        assertFalse(MobileCellIdFixAge.accurate(Float.NaN))
        assertFalse(MobileCellIdFixAge.accurate(Float.POSITIVE_INFINITY))
        assertFalse(MobileCellIdFixAge.accurate(-1f))
    }

    @Test fun `the abstention is stored as H11(fix_impreciso) and an old fix keeps its own code`() {
        val imprecise = HeuristicReport(mobileCellIdFixImprecise = true)
        assertEquals(HeuristicStatus.NOT_EVALUATED, imprecise.mobileCellId)
        assertTrue(imprecise.notEvaluatedIds().split(";").contains("H11(fix_impreciso)"))
        assertEquals(HeuristicReport.H11_FIX_IMPRECISE_TEXT, imprecise.abstentionText("H11"))
        val both = HeuristicReport(mobileCellIdFixTooOld = true, mobileCellIdFixImprecise = true)
        assertTrue(both.notEvaluatedIds().split(";").contains("H11(fix_viejo)"))
    }

    @Test fun `the diagnostic explains it`() {
        val cell = com.alexisgordr.icdetector.models.CellData(isRegistered = true, networkType = "4G LTE", cellId = "1",
            mnc = "07", tac = "1", dbm = -90, mcc = "214", heuristicReport = HeuristicReport(mobileCellIdFixImprecise = true))
        val inputs = DiagnosticEngine.Inputs(neighborCount = 0, wifiActive = false, locationAvailable = true, historyWithLocation = 5,
            previousBandAvailable = false, signalBaseline = null, rfFingerprint = null, rfStability = null, reputation = null)
        assertEquals(DiagnosticEngine.H11_FIX_IMPRECISE_EXPLANATION, DiagnosticEngine.explain(cell, inputs).first { it.id == 11 }.explanation)
    }

    @Test fun `the analyzer drops an imprecise fix before H11 and the service passes the row's accuracy`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }.resolve("com/alexisgordr/icdetector")
        val analyzer = root.resolve("core/ThreatAnalyzer.kt").readText()
        assertTrue(analyzer.contains("val h11FixImprecise = currentLocation != null && !h11FixTooOld && !MobileCellIdFixAge.accurate(locationAccuracyM)"))
        assertTrue(analyzer.contains("val h11Location = currentLocation.takeUnless { h11FixTooOld || h11FixImprecise }"))
        assertTrue(analyzer.contains("mobileCellIdFixImprecise = h11FixImprecise"))
        assertTrue("la misma precisión que GpsAccuracyM", root.resolve("service/MiniICService.kt").readText()
            .contains("locationAccuracyM = observationContext.fix?.accuracyM,"))
    }
}
