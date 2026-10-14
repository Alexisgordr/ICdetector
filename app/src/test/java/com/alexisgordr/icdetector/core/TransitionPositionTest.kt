package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellLocationSample
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.TransitionCoherenceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * 3.0 — H16 con la posición del teléfono. Escenarios de la revisión: dos macroceldas rurales
 * aprendidas en casa (A) y en el trabajo (B), con centros a 15 km. Solo «quieto en la zona de A y
 * aparece B» es incoherente; a mitad de camino el salto puede ser normal y queda en N/A.
 */
class TransitionPositionTest {
    private val lat0 = 42.0
    private val lon0 = -1.6
    private val mPerDegLat = 111_195.0

    private fun at(northM: Double, eastM: Double = 0.0) = CellLocationSample(
        lat0 + northM / mPerDegLat,
        lon0 + eastM / (mPerDegLat * cos(Math.toRadians(lat0)))
    )

    /** Ocho muestras alrededor de un centro, a [radiusM] de él. */
    private fun zone(centerNorthM: Double, radiusM: Double = 110.0) =
        listOf(0.0 to 1.0, 1.0 to 0.0, 0.0 to -1.0, -1.0 to 0.0, 0.7 to 0.7, -0.7 to 0.7, 0.7 to -0.7, -0.7 to -0.7)
            .map { (n, e) -> at(centerNorthM + n * radiusM, e * radiusM) }

    private fun h16(
        here: CellLocationSample?,
        moved: Double,
        centersM: Double = 15_000.0,
        radiusM: Double = 110.0,
        neighbor: Boolean = false,
        prior: Int = 0,
        accuracy: Float = 10f
    ): TransitionCoherenceResult = TransitionCoherence.evaluate(
        TransitionCoherence.Input(
            fromIdentity = "214-07-1-A-LTE", toIdentity = "214-07-1-B-LTE",
            elapsedSeconds = 5,
            previous = TransitionCoherence.Endpoint(100_000L, 98_000L * 1_000_000L),
            current = TransitionCoherence.Endpoint(105_000L, 104_000L * 1_000_000L),
            deviceDistanceMeters = moved,
            currentPosition = here,
            previousAccuracyMeters = accuracy, currentAccuracyMeters = accuracy,
            fromSamples = zone(0.0, radiusM), toSamples = zone(centersM, radiusM),
            priorTrustedTransitions = prior, destinationWasNeighbor = neighbor
        )
    )

    @Test fun `D - still at home in A's zone and B learned 15 km away appears - fails`() {
        val r = h16(here = at(0.0), moved = 5.0)
        assertEquals(HeuristicStatus.FAILED, r.status)
    }

    @Test fun `D at the edge of A's zone still fails`() {
        assertEquals(HeuristicStatus.FAILED, h16(here = at(1_000.0), moved = 5.0).status)
    }

    @Test fun `A - halfway along the road at 50 km per h - not evaluated`() {
        val r = h16(here = at(7_500.0), moved = 140.0)
        assertEquals(HeuristicStatus.NOT_EVALUATED, r.status)
        assertEquals("N/A: ${TransitionCoherence.OUTSIDE_BOTH}", r.explanation)
        assertEquals("un N/A nunca suma a rutas fiables", 0, TransitionCoherence.trustedIncrement(r))
    }

    @Test fun `B - halfway at 100 km per h with 30 s between fixes - not evaluated`() {
        val r = h16(here = at(7_500.0), moved = 900.0)
        assertEquals(HeuristicStatus.NOT_EVALUATED, r.status)
    }

    @Test fun `C - halfway and B was a visible neighbour - never fails`() {
        val r = h16(here = at(7_500.0), moved = 140.0, neighbor = true)
        assertNotEquals(HeuristicStatus.FAILED, r.status)
    }

    @Test fun `E - wide zones of 3 km and centres 15 km apart, halfway - not evaluated`() {
        val r = h16(here = at(7_500.0), moved = 140.0, radiusM = 3_000.0)
        assertEquals(HeuristicStatus.NOT_EVALUATED, r.status)
        assertEquals("N/A: ${TransitionCoherence.OUTSIDE_BOTH}", r.explanation)
    }

    @Test fun `F - centres 9 km apart still pass`() {
        assertEquals(HeuristicStatus.PASSED, h16(here = at(4_500.0), moved = 140.0, centersM = 9_000.0).status)
    }

    @Test fun `in the new cell's zone, far from the old one - not evaluated`() {
        val r = h16(here = at(15_000.0), moved = 5.0)
        assertEquals(HeuristicStatus.NOT_EVALUATED, r.status)
        assertEquals("N/A: ${TransitionCoherence.AT_DESTINATION}", r.explanation)
    }

    @Test fun `at the edge of the old zone with the new one only moderately far - not evaluated`() {
        // Margen de medida 1.000 + 75 + 75 + 1.500 m; lejos = 3 × margen. La zona nueva queda más
        // allá del margen pero no «lejos»: no es el caso que H16 persigue.
        val r = h16(here = at(2_600.0), moved = 1_000.0, centersM = 10_500.0, accuracy = 75f)
        assertEquals(HeuristicStatus.NOT_EVALUATED, r.status)
        assertEquals("N/A: ${TransitionCoherence.DESTINATION_NOT_FAR}", r.explanation)
    }

    @Test fun `without the current position a remote jump is not evaluated`() {
        val r = h16(here = null, moved = 5.0)
        assertEquals(HeuristicStatus.NOT_EVALUATED, r.status)
        assertEquals("N/A: ${TransitionCoherence.NO_POSITION}", r.explanation)
    }

    @Test fun `a learned route still cannot hide the attack at home`() {
        assertEquals(HeuristicStatus.FAILED, h16(here = at(0.0), moved = 5.0, prior = 5).status)
        assertEquals(HeuristicStatus.FAILED, h16(here = at(0.0), moved = 5.0, neighbor = true).status)
    }

    @Test fun `the new reasons are translated`() {
        listOf(
            TransitionCoherence.NO_POSITION, TransitionCoherence.OUTSIDE_BOTH,
            TransitionCoherence.AT_DESTINATION, TransitionCoherence.DESTINATION_NOT_FAR
        ).forEach { reason ->
            val en = com.alexisgordr.icdetector.ui.localizeTerminalLine("N/A: $reason")
            assertTrue(reason, en != "N/A: $reason" && !en.contains("posición") && !en.contains("zona"))
        }
    }
}
