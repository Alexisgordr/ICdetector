package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellLocationSample
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.TransitionCoherenceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionCoherenceTest {
    private val madrid = listOf(
        CellLocationSample(40.4168, -3.7038), CellLocationSample(40.4170, -3.7040),
        CellLocationSample(40.4166, -3.7036), CellLocationSample(40.4169, -3.7037)
    )
    private val barcelona = listOf(
        CellLocationSample(41.3874, 2.1686), CellLocationSample(41.3876, 2.1688),
        CellLocationSample(41.3872, 2.1684), CellLocationSample(41.3875, 2.1687)
    )

    private fun input(
        moved: Double? = 20.0,
        from: List<CellLocationSample> = madrid,
        to: List<CellLocationSample> = barcelona,
        prior: Int = 0,
        neighbor: Boolean = false,
        accuracy: Float = 10f
    ) = TransitionCoherence.Input(
        fromIdentity = "214-01-1-A-LTE", toIdentity = "214-01-1-B-LTE",
        elapsedSeconds = 5, deviceDistanceMeters = moved,
        previousAccuracyMeters = accuracy, currentAccuracyMeters = accuracy,
        fromSamples = from, toSamples = to, priorTrustedTransitions = prior,
        destinationWasNeighbor = neighbor
    )

    @Test fun `falla si el telefono apenas se mueve y las zonas aprendidas estan muy lejos`() {
        val result = TransitionCoherence.evaluate(input())
        assertEquals(HeuristicStatus.FAILED, result.status)
        assertTrue((result.centerDistanceMeters ?: 0) > 100_000)
    }

    @Test fun `se abstiene mientras el baseline geografico no sea suficiente`() {
        val result = TransitionCoherence.evaluate(input(to = barcelona.take(2)))
        assertEquals(HeuristicStatus.NOT_EVALUATED, result.status)
        assertTrue(result.explanation.contains("aprendizaje"))
    }

    @Test fun `acepta una celda que ya era vecina aunque el baseline sea escaso`() {
        val result = TransitionCoherence.evaluate(input(to = emptyList(), neighbor = true))
        assertEquals(HeuristicStatus.PASSED, result.status)
    }

    @Test fun `acepta una transicion aprendida previamente`() {
        val result = TransitionCoherence.evaluate(input(to = emptyList(), prior = 2))
        assertEquals(HeuristicStatus.PASSED, result.status)
    }

    @Test fun `se abstiene con GPS impreciso`() {
        val result = TransitionCoherence.evaluate(input(accuracy = 120f))
        assertEquals(HeuristicStatus.NOT_EVALUATED, result.status)
    }

    @Test fun `acepta zonas historicas que se solapan`() {
        val nearby = madrid.map { CellLocationSample(it.latitude + 0.002, it.longitude + 0.002) }
        val result = TransitionCoherence.evaluate(input(to = nearby))
        assertEquals(HeuristicStatus.PASSED, result.status)
    }

    @Test fun `H16 entra en el informe y no puede activar una alerta por si sola`() {
        val cell = CellData(
            isRegistered = true, networkType = "4G LTE", cellId = "B", mnc = "01",
            tac = "1", dbm = -90, mcc = "214"
        )
        val analyzed = ThreatAnalyzer.analyzeThreats(
            active = cell, neighbors = emptyList(), isHardwareCipheringActive = true,
            cellChangeHistory = emptyList(), currentLocation = null,
            transitionCoherence = TransitionCoherenceResult(
                status = HeuristicStatus.FAILED, explanation = "test",
                fromIdentity = "A", toIdentity = "B"
            )
        )
        assertEquals(HeuristicStatus.FAILED, analyzed.heuristicReport.transitionCoherence)
        assertEquals(85, analyzed.securityScore)
        assertTrue(!analyzed.isSuspicious)
    }
}
