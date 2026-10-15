package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — H6 evalúa solo proximidad (TA + señal). La rama geométrica con `active.lat/lon` como antena
 * no tenía datos en producción y se retiró; nada la sustituye.
 */
class H6ProximityTest {
    private fun cell(dbm: Int, ta: Int?, lat: Double? = null, lon: Double? = null) = CellData(
        isRegistered = true, networkType = "4G LTE", radioTech = RadioTech.LTE, cellId = "1000", mnc = "01",
        tac = "100", dbm = dbm, mcc = "214", arfcn = 1300, timingAdvance = ta,
        timingAdvanceUnit = TimingAdvanceUnit.LTE_INDEX, lat = lat, lon = lon
    )
    private val neighbour = CellData(isRegistered = false, networkType = "4G LTE", cellId = "2000",
        mnc = "01", tac = "100", dbm = -95, mcc = "214")
    private fun h6(c: CellData) = ThreatAnalyzer.analyzeThreats(
        active = c, neighbors = listOf(neighbour), cellChangeHistory = emptyList(), currentLocation = null
    ).heuristicReport.taDistance

    @Test fun `proximity branch is unchanged`() {
        assertEquals(HeuristicStatus.FAILED, h6(cell(dbm = -55, ta = 1)))      // 78 m, señal fuerte
        assertEquals(HeuristicStatus.PASSED, h6(cell(dbm = -65, ta = 1)))      // señal no tan fuerte
        assertEquals(HeuristicStatus.PASSED, h6(cell(dbm = -55, ta = 2)))      // 156 m
        assertEquals(HeuristicStatus.NOT_EVALUATED, h6(cell(dbm = -55, ta = null)))
    }

    @Test fun `cell coordinates no longer change H6`() {
        // Antes, con lat/lon en CellData se tomaba otra rama y la proximidad no se evaluaba.
        assertEquals(h6(cell(dbm = -55, ta = 1)), h6(cell(dbm = -55, ta = 1, lat = 40.4, lon = -3.7)))
        assertEquals(h6(cell(dbm = -55, ta = 3)), h6(cell(dbm = -55, ta = 3, lat = 40.4, lon = -3.7)))
    }

    @Test fun `no geometric branch, no tower coordinates in the analyzer, and no parser sets them`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val analyzer = main.walkTopDown().first { it.name == "ThreatAnalyzer.kt" }.readText()
        val code = analyzer.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
        assertFalse(code.contains("active.lat") || code.contains("active.lon"))
        assertFalse(analyzer.contains("\"Suplantación TA\""))
        assertTrue(analyzer.contains("if (taMeters != null && taMeters <= 100 && active.dbm >= -60) {"))
        val parser = main.walkTopDown().first { it.name == "CellParser.kt" }.readText()
        assertFalse(Regex("""\blat\s*=""").containsMatchIn(parser))
    }
}
