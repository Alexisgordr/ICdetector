package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HeuristicStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (corte de campaña) — H11 solo compara con un fix de 60 s o menos. Los tests JVM de Gradle no pueden crear
 * un android.location.Location, así que la decisión se prueba en MobileCellIdFixAge y el cableado
 * del analizador, en su código.
 */
class MobileCellIdFixAgeTest {
    @Test fun `up to 60 s of monotonic age is usable, older or unknown is not`() {
        assertTrue(MobileCellIdFixAge.usable(0L))
        assertTrue(MobileCellIdFixAge.usable(60_000L))
        assertFalse(MobileCellIdFixAge.usable(60_001L))
        assertFalse("120 s, el máximo que acepta la ubicación en continuo", MobileCellIdFixAge.usable(120_000L))
        assertFalse("edad desconocida", MobileCellIdFixAge.usable(null))
        assertFalse("edad negativa: reloj incoherente", MobileCellIdFixAge.usable(-1L))
    }

    @Test fun `the 60 s limit keeps the error well below the densest H11 threshold`() {
        val densest = ThreatAnalyzer.getDynamicLocationThreshold(List(12) { ScenarioCells.neighbour() }, com.alexisgordr.icdetector.models.RadioTech.LTE)
        assertEquals(2000.0, densest, 0.0)
        assertTrue("ciudad, 15 m/s", 15.0 * MobileCellIdFixAge.MAX_AGE_MS / 1_000 < densest / 2)
        assertTrue("antes: 30 m/s durante 120 s superaba el umbral urbano", 30.0 * 120 > densest)
    }

    @Test fun `the abstention reason is stored as H11(fix_viejo)`() {
        val report = HeuristicReport(mobileCellIdFixTooOld = true)
        assertEquals(HeuristicStatus.NOT_EVALUATED, report.mobileCellId)
        assertTrue(report.notEvaluatedIds().split(";").contains("H11(fix_viejo)"))
        assertTrue(report.snapshot().split(";").contains("H11=NOT_EVALUATED(fix_viejo)"))
        assertEquals(HeuristicReport.H11_FIX_ABSTENTION_TEXT, report.abstentionText("H11"))
        assertFalse(HeuristicReport().notEvaluatedIds().contains("fix_viejo"))
    }

    @Test fun `the analyzer drops an old fix before H11`() {
        val analyzer = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/core/ThreatAnalyzer.kt").readText()
        assertTrue(analyzer.contains("val h11FixTooOld = currentLocation != null && !MobileCellIdFixAge.usable(locationFixAgeMs)"))
        assertTrue(analyzer.contains("active, h11Location, preloadedHistory, neighbors"))
        assertTrue(analyzer.contains("mobileCellIdFixTooOld = h11FixTooOld"))
    }
}

/** Celda vecina mínima para los umbrales de densidad. */
private object ScenarioCells {
    fun neighbour() = com.alexisgordr.icdetector.models.CellData(
        isRegistered = false, networkType = "4G LTE", cellId = "2000", mnc = "07", tac = "1", dbm = -95
    )
}
