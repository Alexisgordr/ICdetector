package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.0 (fase 2) — Una regla solo resta puntos cuando se ha podido evaluar, y solo compara datos
 * comparables. Casos medidos en la revisión con `ThreatAnalyzer.analyzeThreats`.
 */
class HeuristicsComparabilityTest {
    private fun serving(radio: RadioTech = RadioTech.LTE, dbm: Int = -95, mnc: String = "07", tac: String = "31601") = CellData(
        isRegistered = true, networkType = "4G LTE", cellId = "1000", mnc = mnc, tac = tac, dbm = dbm, mcc = "214", radioTech = radio
    )

    private fun neighbour(radio: RadioTech = RadioTech.LTE, dbm: Int = -95, mnc: String = "07", tac: String = "31601") = CellData(
        isRegistered = false, networkType = radio.name, cellId = "2000", mnc = mnc, tac = tac, dbm = dbm, mcc = "214", radioTech = radio
    )

    private fun analyze(active: CellData, neighbours: List<CellData>) = ThreatAnalyzer.analyzeThreats(
        active = active, neighbors = neighbours, isHardwareCipheringActive = true,
        cellChangeHistory = emptyList(), currentLocation = null, isNetworkLatencyAvailable = false
    )

    // ── H4 ──────────────────────────────────────────────────────────────────────────────────

    @Test fun `H4 not evaluated never subtracts points, stores a reason or feeds the Bayesian`() {
        // Caso medido: servidora con MNC «N/A» y cinco vecinas con MNC conocidos distintos.
        val result = analyze(serving(mnc = "N/A"), (1..5).map { neighbour(mnc = "0$it") })
        assertEquals(HeuristicStatus.NOT_EVALUATED, result.heuristicReport.mncCount)
        assertEquals(100, result.securityScore)
        assertFalse(result.suspiciousReason.orEmpty().contains("Multitud de MNCs"))
        // El bayesiano no recibe H4: misma confianza que con las vecinas de la misma red (sin
        // nada que señalar). Antes subía (3,9 en la revisión).
        val control = analyze(serving(mnc = "N/A"), (1..5).map { neighbour(mnc = "07") })
        assertEquals(control.anomalyConfidence, result.anomalyConfidence)
    }

    @Test fun `H4 still fails when it can be evaluated`() {
        val result = analyze(serving(mnc = "01"), (2..5).map { neighbour(mnc = "0$it") })
        assertEquals(HeuristicStatus.FAILED, result.heuristicReport.mncCount)
        assertEquals(85, result.securityScore)
    }

    // ── H5 ──────────────────────────────────────────────────────────────────────────────────

    @Test fun `H5 never compares an LTE TAC with a GSM or UMTS LAC or an NR TAC`() {
        // Caso medido: servidora LTE con TAC 31601 y una sola vecina GSM con LAC 1234.
        listOf(RadioTech.GSM, RadioTech.UMTS, RadioTech.NR).forEach { other ->
            val result = analyze(serving(tac = "31601"), listOf(neighbour(radio = other, tac = "1234")))
            assertEquals("$other", HeuristicStatus.NOT_EVALUATED, result.heuristicReport.tacDeviation)
            assertEquals("$other", 100, result.securityScore)
        }
    }

    @Test fun `H5 uses only same-technology neighbours`() {
        val lteMatch = neighbour(tac = "31601"); val gsm = neighbour(radio = RadioTech.GSM, tac = "1234")
        assertEquals(HeuristicStatus.PASSED, analyze(serving(tac = "31601"), listOf(gsm, lteMatch)).heuristicReport.tacDeviation)
        assertEquals(HeuristicStatus.FAILED, analyze(serving(tac = "31601"), listOf(gsm, neighbour(tac = "31602"))).heuristicReport.tacDeviation)
        // Servidora GSM: compara su LAC con LAC de vecinas GSM.
        assertEquals(HeuristicStatus.PASSED,
            analyze(serving(radio = RadioTech.GSM, tac = "1234"), listOf(gsm, neighbour(tac = "31601"))).heuristicReport.tacDeviation)
    }
}
