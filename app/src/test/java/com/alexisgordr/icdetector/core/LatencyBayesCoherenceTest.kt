package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.0 — La LR de latencia del bayesiano se aplica solo con H12 FAILED. El indicador bruto de
 * latencia anómala no eleva la confianza cuando H12 pasa o no se evalúa.
 */
class LatencyBayesCoherenceTest {
    private fun cell(dbm: Int, rsrq: Int?, sinr: Int?) = CellData(
        isRegistered = true, networkType = "4G LTE", radioTech = RadioTech.LTE, cellId = "1000",
        mnc = "01", tac = "100", dbm = dbm, mcc = "214", arfcn = 1300, rsrq = rsrq, sinr = sinr
    )
    private val neighbour = CellData(isRegistered = false, networkType = "4G LTE", cellId = "2000",
        mnc = "01", tac = "100", dbm = -95, mcc = "214")

    private fun run(dbm: Int = -60, rsrq: Int? = -18, sinr: Int? = null, anomalous: Boolean, available: Boolean = true, wifi: Boolean = false) =
        ThreatAnalyzer.analyzeThreats(
            active = cell(dbm, rsrq, sinr), neighbors = listOf(neighbour), cellChangeHistory = emptyList(), currentLocation = null, isWifiActive = wifi,
            isNetworkLatencyAnomalous = anomalous, isNetworkLatencyAvailable = available, nowMs = 1_700_000_000_000L
        )

    @Test fun `H12 FAILED applies the latency factor exactly once`() {
        val failed = run(anomalous = true)
        val calm = run(anomalous = false)
        assertEquals(HeuristicStatus.FAILED, failed.heuristicReport.latencyCorrelation)
        assertEquals(HeuristicStatus.PASSED, calm.heuristicReport.latencyCorrelation)
        assertTrue(failed.anomalyConfidence > calm.anomalyConfidence)
        // Mismo resultado que el bayesiano con el factor aplicado una sola vez sobre lo demás igual.
        val others = calm.anomalyConfidence
        val expected = BayesianScorer.calculate(emptyList(), "PENDING", true, neighborCount = 1)
        assertEquals("solo H12 cambia entre ambas", 0, calm.heuristicReport.failedCount)
        assertEquals(1, failed.heuristicReport.failedCount)
        assertEquals(expected, failed.anomalyConfidence, 0.0001f)
        assertEquals(BayesianScorer.calculate(emptyList(), "PENDING", false, neighborCount = 1), others, 0.0001f)
    }

    @Test fun `H12 PASSED with raw anomalous latency and normal RF adds nothing`() {
        val r = run(rsrq = -8, sinr = 15, anomalous = true)
        assertEquals(HeuristicStatus.PASSED, r.heuristicReport.latencyCorrelation)
        assertEquals(run(rsrq = -8, sinr = 15, anomalous = false).anomalyConfidence, r.anomalyConfidence, 0f)
    }

    @Test fun `weak signal keeps H12 passed and adds nothing`() {
        val r = run(dbm = -85, anomalous = true)
        assertEquals(HeuristicStatus.PASSED, r.heuristicReport.latencyCorrelation)
        assertEquals(run(dbm = -85, anomalous = false).anomalyConfidence, r.anomalyConfidence, 0f)
    }

    @Test fun `no latency measurement is not evaluated and adds nothing`() {
        val r = run(anomalous = true, available = false)
        assertEquals(HeuristicStatus.NOT_EVALUATED, r.heuristicReport.latencyCorrelation)
        assertEquals(run(anomalous = false, available = false).anomalyConfidence, r.anomalyConfidence, 0f)
    }

    @Test fun `no RF quality data is not evaluated and adds nothing`() {
        val r = run(rsrq = null, sinr = null, anomalous = true)
        assertEquals(HeuristicStatus.NOT_EVALUATED, r.heuristicReport.latencyCorrelation)
        assertEquals(run(rsrq = null, sinr = null, anomalous = false).anomalyConfidence, r.anomalyConfidence, 0f)
    }

    @Test fun `Wi-Fi abstains and adds nothing`() {
        val r = run(anomalous = true, wifi = true)
        assertEquals(HeuristicStatus.NOT_EVALUATED, r.heuristicReport.latencyCorrelation)
        assertEquals(run(anomalous = false, wifi = true).anomalyConfidence, r.anomalyConfidence, 0f)
    }

    @Test fun `BayesianScorer has a single caller, which passes the H12 result`() {
        val src = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        val callers = src.walkTopDown().filter { it.extension == "kt" }
            .filter { it.readText().contains("BayesianScorer.calculate(") }.map { it.name }.toList()
        assertEquals(listOf("ThreatAnalyzer.kt"), callers)
        val analyzer = src.walkTopDown().first { it.name == "ThreatAnalyzer.kt" }.readText()
        assertTrue(analyzer.contains("latencyCorrelationFailed = !hLatencyCorrelation,"))
        assertTrue("la latencia no entra también por la lista", !analyzer.contains("add(\"latency\")"))
    }
}
