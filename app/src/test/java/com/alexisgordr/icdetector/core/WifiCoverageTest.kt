package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — Wi-Fi: qué reglas se abstienen (documentado, sin cambiar reglas) y que su N/A dice la causa.
 */
class WifiCoverageTest {
    private val active = CellData(isRegistered = true, networkType = "4G LTE", radioTech = RadioTech.LTE, cellId = "1000",
        mnc = "01", tac = "100", dbm = -60, mcc = "214", arfcn = 1300, rsrq = -18, sinr = -2)
    private val neighbour = CellData(isRegistered = false, networkType = "4G LTE", radioTech = RadioTech.LTE, cellId = "2000", mnc = "01", tac = "100", dbm = -115, mcc = "214")

    private fun report(wifi: Boolean) = ThreatAnalyzer.analyzeThreats(
        active = active, neighbors = listOf(neighbour), cellChangeHistory = emptyList(),
        currentLocation = null, isWifiActive = wifi
    ).heuristicReport

    @Test fun `exactly H1, H2, H7 and H13 abstain on Wi-Fi`() {
        val on = report(wifi = true)
        listOf(on.isolatedCell, on.powerJump, on.ghostNeighbors, on.signalBaseline)
            .forEach { assertEquals(HeuristicStatus.NOT_EVALUATED, it) }
        // Las mismas reglas sí se evalúan sin Wi-Fi con estos datos (salvo H1 con vecina y H13 sin historial).
        val off = report(wifi = false)
        assertTrue(off.powerJump != HeuristicStatus.NOT_EVALUATED)
        assertTrue(off.ghostNeighbors != HeuristicStatus.NOT_EVALUATED)
        // 3.1.0 — H12 retirada: N/A con y sin Wi-Fi, y su motivo no es el Wi-Fi.
        assertEquals(HeuristicStatus.NOT_EVALUATED, on.latencyCorrelation)
        assertEquals(HeuristicStatus.NOT_EVALUATED, off.latencyCorrelation)
    }

    @Test fun `their N-A reason names Wi-Fi`() {
        val cell = ThreatAnalyzer.analyzeThreats(active = active, neighbors = listOf(neighbour), cellChangeHistory = emptyList(), currentLocation = null, isWifiActive = true)
        val inputs = DiagnosticEngine.Inputs(neighborCount = 1, wifiActive = true, locationAvailable = false, historyWithLocation = 0,
            previousBandAvailable = false, signalBaseline = null,
            rfFingerprint = null, rfStability = null, reputation = null)
        val byId = DiagnosticEngine.explain(cell, inputs).associateBy { it.id }
        listOf(1, 2, 7, 13).forEach { id ->
            assertEquals("H$id", HeuristicStatus.NOT_EVALUATED, byId.getValue(id).status)
            assertTrue("H$id: ${byId.getValue(id).explanation}", byId.getValue(id).explanation.contains("Wi-Fi"))
        }
        assertEquals(DiagnosticEngine.H12_RETIRED_EXPLANATION, byId.getValue(12).explanation)
    }

    @Test fun `the manual documents the Wi-Fi exclusions and the retired H12`() {
        val manual = listOf(File("MANUAL.md"), File("../MANUAL.md")).first { it.exists() }.readText()
        assertTrue(manual.contains("### Wi-Fi and rule coverage"))
        assertTrue(manual.contains("**H1** (isolated cell),\n**H2** (power jump), **H7** (ghost neighbours) and **H13**"))
        assertTrue(manual.contains("H12 was retired in 3.1.0"))
        assertTrue(manual.contains("**Precaution, not validated.**"))
    }
}
