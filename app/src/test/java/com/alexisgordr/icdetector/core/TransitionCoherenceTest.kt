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

    // ── v2.5: los atajos ya no ganan a la geometría ──────────────────────────────────────────

    @Test fun `una vecina que contradice la geografia ya no se libra por serlo`() {
        // EL ATAQUE QUE ESTO CIERRA: un IMSI-catcher local aparece en la lista de vecinas del
        // módem antes de que el móvil se enganche. Hasta v2.4 eso bastaba para devolver PASSED
        // sin llegar a comparar las zonas aprendidas — es decir, el ataque que H16 existe para
        // cazar se saltaba H16 entera anunciándose como vecina.
        val result = TransitionCoherence.evaluate(input(neighbor = true))
        assertEquals(HeuristicStatus.FAILED, result.status)
        assertTrue(result.explanation.contains("vecina"))
    }

    @Test fun `una ruta aprendida tampoco tapa una imposibilidad fisica`() {
        val result = TransitionCoherence.evaluate(input(prior = 5, neighbor = true))
        assertEquals(HeuristicStatus.FAILED, result.status)
    }

    @Test fun `sin baseline maduro la vecina sigue pasando como antes`() {
        // La regresión que hay que evitar: exigir geometría donde no hay historial convertiría
        // cada celda nueva en sospechosa. Sin muestras suficientes no hay nada que contradecir.
        val result = TransitionCoherence.evaluate(input(to = emptyList(), neighbor = true))
        assertEquals(HeuristicStatus.PASSED, result.status)
        assertTrue("No puede enseñar la ruta al baseline sin historial", !result.eligibleForLearning)
    }

    @Test fun `una vecina coherente pasa y ademas es apta para aprender`() {
        val nearby = madrid.map { CellLocationSample(it.latitude + 0.002, it.longitude + 0.002) }
        val result = TransitionCoherence.evaluate(input(to = nearby, neighbor = true))
        assertEquals(HeuristicStatus.PASSED, result.status)
        assertTrue(result.eligibleForLearning)
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
