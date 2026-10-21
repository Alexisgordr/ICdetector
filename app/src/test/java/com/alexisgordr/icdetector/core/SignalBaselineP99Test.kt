package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.SignalBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.1.0 (#40) — El P99 de la línea base de H13 es `Int?`: null = no calculado. Antes era 0, y un
 * P99 real de 0 dBm se tomaba por «sin calcular» y el refuerzo de H13 no se aplicaba.
 */
class SignalBaselineP99Test {
    private val cell = CellData(isRegistered = true, networkType = "4G LTE", cellId = "1000", mnc = "07", tac = "100",
        dbm = -55, mcc = "214", radioTech = RadioTech.LTE)
    private fun h13(baseline: SignalBaseline) = ThreatAnalyzer.analyzeThreats(cell, emptyList(), emptyList(), null,
        signalBaseline = baseline).heuristicReport.signalBaseline
    private fun base(p99: Int?) = SignalBaseline(sampleCount = 25, meanDbm = -85.0, stdDevDbm = 3.0, minDbm = -90, maxDbm = -80, p99Dbm = p99)

    @Test fun `not calculated is null, not zero`() {
        assertNull(SignalBaseline(10, -85.0, 3.0, -90, -80).p99Dbm)
    }

    @Test fun `without P99 the rule behaves as before`() {
        assertEquals(HeuristicStatus.FAILED, h13(base(null)))
    }

    @Test fun `a real P99 of 0 dBm is applied, not mistaken for missing`() {
        // -55 dBm no supera un P99 de 0 dBm: la celda ya se ha visto así de fuerte aquí.
        assertEquals(HeuristicStatus.PASSED, h13(base(0)))
        assertEquals(HeuristicStatus.PASSED, h13(base(-50)))
        assertEquals(HeuristicStatus.FAILED, h13(base(-60)))
    }

    @Test fun `the analyzer no longer uses 0 as the missing value`() {
        val analyzer = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/core/ThreatAnalyzer.kt").readText()
        assertTrue(analyzer.contains("p99 != null"))
        assertTrue(!analyzer.contains("p99Dbm != 0"))
    }
}
