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

    // ── H2 y H7 ─────────────────────────────────────────────────────────────────────────────

    @Test fun `H2 does not compare LTE RSRP with GSM RSSI or UMTS RSCP`() {
        // Caso medido: servidora LTE a -70 dBm (RSRP) y vecina GSM a -106 dBm (RSSI).
        listOf(RadioTech.GSM, RadioTech.UMTS).forEach { other ->
            val result = analyze(serving(dbm = -70), listOf(neighbour(radio = other, dbm = -106)))
            assertEquals("$other", HeuristicStatus.NOT_EVALUATED, result.heuristicReport.powerJump)
            assertEquals("$other", 100, result.securityScore)
        }
    }

    @Test fun `H2 still compares within the same technology`() {
        val gsm = neighbour(radio = RadioTech.GSM, dbm = -60)    // más fuerte, pero otra escala
        assertEquals(HeuristicStatus.FAILED, analyze(serving(dbm = -70), listOf(gsm, neighbour(dbm = -106))).heuristicReport.powerJump)
        assertEquals(HeuristicStatus.PASSED, analyze(serving(dbm = -70), listOf(neighbour(radio = RadioTech.GSM, dbm = -120), neighbour(dbm = -90))).heuristicReport.powerJump)
    }

    @Test fun `H7 does not take dead-looking neighbours of another scale as ghosts`() {
        // Servidora LTE fuerte; solo vecinas GSM a -112 dBm (RSSI débil, pero otra escala): N/A, no fallo.
        val result = analyze(serving(dbm = -60), listOf(neighbour(radio = RadioTech.GSM, dbm = -112), neighbour(radio = RadioTech.UMTS, dbm = -115)))
        assertEquals(HeuristicStatus.NOT_EVALUATED, result.heuristicReport.ghostNeighbors)
        assertTrue(result.securityScore >= 100 - 15)   // a lo sumo H1 no aplica: hay vecinas
        assertFalse(result.suspiciousReason.orEmpty().contains("Vecinos fantasma"))
        // Con vecinas LTE, se evalúa como antes.
        assertEquals(HeuristicStatus.FAILED, analyze(serving(dbm = -60), listOf(neighbour(dbm = -112))).heuristicReport.ghostNeighbors)
        assertEquals(HeuristicStatus.PASSED, analyze(serving(dbm = -60), listOf(neighbour(dbm = -112), neighbour(dbm = -100))).heuristicReport.ghostNeighbors)
    }

    @Test fun `H1 still counts neighbours of any technology`() {
        val result = analyze(serving(dbm = -70), listOf(neighbour(radio = RadioTech.GSM, dbm = -90)))
        assertEquals(HeuristicStatus.PASSED, result.heuristicReport.isolatedCell)
    }
}
