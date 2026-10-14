package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — El diagnóstico explica un resultado con el MISMO estado Wi-Fi con que se analizó. Si el
 * Wi-Fi cambia entre el análisis y la publicación, la explicación no cambia de motivo.
 */
class WifiDiagnosticConsistencyTest {
    private val cell = CellData(isRegistered = true, networkType = "4G LTE", cellId = "100", mnc = "07",
        tac = "31601", dbm = -70, mcc = "214")

    private fun inputs(wifi: Boolean) = DiagnosticEngine.Inputs(
        neighborCount = 0, wifiActive = wifi, locationAvailable = false, historyWithLocation = 0,
        latencyAvailable = false, previousBandAvailable = false,
        signalBaseline = null, rfFingerprint = null, rfStability = null, reputation = null
    )

    @Test fun `a result analysed on wifi is explained as a wifi abstention`() {
        val wifiAtAnalysis = true
        val analysed = ThreatAnalyzer.analyzeThreats(active = cell, neighbors = emptyList(),
            cellChangeHistory = emptyList(), currentLocation = null,
            isWifiActive = wifiAtAnalysis, isolatedCellConfirmed = true)
        assertEquals(HeuristicStatus.NOT_EVALUATED, analysed.heuristicReport.powerJump)

        val captured = DiagnosticEngine.explain(analysed, inputs(wifiAtAnalysis))
        val h2 = captured.first { it.id == 2 }.explanation
        assertTrue(h2.contains("Wi-Fi"))

        // Lo que pasaba al volver a consultar el Wi-Fi tras desconectarse: otro motivo para el mismo resultado.
        val requeried = DiagnosticEngine.explain(analysed, inputs(wifi = false)).first { it.id == 2 }.explanation
        assertNotEquals(h2, requeried)
        assertFalse(requeried.contains("Wi-Fi"))
    }

    @Test fun `the service explains with the captured value and the rule exclusions are unchanged`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val service = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val cycle = service.substringAfter("cellProcessingMutex.withLock {").substringBefore("private fun onAnalysisFailed(")
        assertEquals("una sola lectura de Wi-Fi por observación", 1, Regex("""isWifiConnected\(\)""").findAll(cycle).count())
        val inputs = cycle.substringAfter("DiagnosticEngine.Inputs(").substringBefore("trustGateMet")
        assertTrue(inputs.contains("wifiActive = wifiActive,"))
        assertTrue(cycle.contains("isWifiActive = wifiActive,"))
        assertTrue(cycle.contains("candidate = !wifiActive && neighbors.isEmpty() && active.dbm >= -80"))
    }

    @Test fun `concurrency adjustments are justified`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val service = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        assertTrue(service.contains("@Volatile private var isServiceRunning = false"))
        assertTrue("loggedLocationMode sin @Volatile: se confina a un hilo",
            service.contains("    private var loggedLocationMode"))
        val loop = service.substringAfter("scope.launch(Dispatchers.Default) {").substringBefore("delay(delayTime.milliseconds)")
        assertTrue(loop.contains("withContext(Dispatchers.Main) { applyLocationMode() }"))
        assertFalse(Regex("""\n\s+applyLocationMode\(\)\n""").containsMatchIn(loop))
    }
}
