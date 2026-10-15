package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellLocationSample
import com.alexisgordr.icdetector.models.HeuristicStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.0 (G1) — H16 solo mide el desplazamiento con un fix GPS propio y reciente en cada extremo. Una
 * posición repetida o antigua da «0 m» sin demostrar que el móvil estuviera quieto: N/A, nunca un
 * movimiento cero evaluable. Los escenarios usan la geometría real de TransitionCoherence con zonas
 * aprendidas muy alejadas (Madrid → Barcelona), que con 0–20 m recorridos daría FAILED.
 */
class TransitionGpsFreshnessTest {
    private val madrid = listOf(
        CellLocationSample(40.4168, -3.7038), CellLocationSample(40.4170, -3.7040),
        CellLocationSample(40.4166, -3.7036), CellLocationSample(40.4169, -3.7037)
    )
    private val barcelona = listOf(
        CellLocationSample(41.3874, 2.1686), CellLocationSample(41.3876, 2.1688),
        CellLocationSample(41.3872, 2.1684), CellLocationSample(41.3875, 2.1687)
    )
    private val nearby = madrid.map { CellLocationSample(it.latitude + 0.002, it.longitude + 0.002) }

    private fun ns(ms: Long) = ms * 1_000_000L
    private fun end(snapshotMs: Long, fixMs: Long?) = TransitionCoherence.Endpoint(snapshotMs, fixMs?.let(::ns))

    /** Instantáneas a t=100 s y t=105 s; por defecto, cada una con su fix de hace 1–2 s. */
    private fun input(
        previous: TransitionCoherence.Endpoint = end(100_000, 98_000),
        current: TransitionCoherence.Endpoint = end(105_000, 104_000),
        moved: Double? = 0.0,
        to: List<CellLocationSample> = barcelona,
        neighbor: Boolean = false,
        prior: Int = 0
    ) = TransitionCoherence.Input(
        fromIdentity = "214-01-1-A-LTE", toIdentity = "214-01-1-B-LTE",
        elapsedSeconds = (current.snapshotElapsedMs - previous.snapshotElapsedMs) / 1_000L,
        previous = previous, current = current, deviceDistanceMeters = moved,
        currentPosition = madrid.first(),   // quieto en la zona de origen
        previousAccuracyMeters = 10f, currentAccuracyMeters = 10f,
        fromSamples = madrid, toSamples = to, priorTrustedTransitions = prior, destinationWasNeighbor = neighbor
    )

    private fun assertNa(input: TransitionCoherence.Input, contains: String) {
        val r = TransitionCoherence.evaluate(input)
        assertEquals(HeuristicStatus.NOT_EVALUATED, r.status)
        assertTrue(r.explanation, r.explanation.startsWith("N/A: ") && r.explanation.contains(contains))
        assertNull("sin distancia medida que enseñar", r.deviceDistanceMeters)
        assertFalse(r.eligibleForLearning)
        assertEquals("un N/A no suma al aprendizaje fiable", 0, TransitionCoherence.trustedIncrement(r))
    }

    @Test fun `1 the same fix at both ends is N-A, never a zero movement`() {
        // Mismo Location (mismo elapsedRealtimeNanos) en las dos instantáneas, reciente para ambas.
        assertNa(input(previous = end(100_000, 99_000), current = end(105_000, 99_000)), "mismo fix GPS")
    }

    @Test fun `2 an old fix at either end is N-A`() {
        assertNa(input(previous = end(100_000, 85_000)), "la celda anterior tiene más de 10 s")
        assertNa(input(current = end(105_000, 90_000)), "la celda nueva tiene más de 10 s")
        // El límite: 10 s exactos ya no es reciente; 9,999 s sí.
        assertNa(input(current = end(105_000, 95_000)), "más de 10 s")
        assertNull(TransitionCoherence.gpsProblem(end(100_000, 94_000), end(105_000, 95_001)))
    }

    @Test fun `3 GPS lost while the cell changes gives no false FAILED from a cached position`() {
        // Antes del handover: fix vivo. Se pierde el GPS y, 30 s después, la nueva servidora solo
        // tiene la última posición aceptada (la misma, todavía dentro de los 120 s).
        val cached = input(previous = end(100_000, 99_500), current = end(130_000, 99_500))
        assertNa(cached, "más de 10 s")
        // Y sin ninguna posición en el extremo nuevo.
        assertNa(input(current = end(105_000, null)), "no hay fix GPS en la observación de la celda nueva")
        assertNa(input(previous = end(100_000, null)), "no hay fix GPS en la observación de la celda anterior")
    }

    @Test fun `4 two distinct recent fixes keep the expected geometric results`() {
        val failed = TransitionCoherence.evaluate(input(moved = 20.0))
        assertEquals(HeuristicStatus.FAILED, failed.status)
        assertEquals(20, failed.deviceDistanceMeters)
        val passed = TransitionCoherence.evaluate(input(moved = 20.0, to = nearby))
        assertEquals(HeuristicStatus.PASSED, passed.status)
        assertTrue(passed.eligibleForLearning)
        assertEquals(1, TransitionCoherence.trustedIncrement(passed))
        val learning = TransitionCoherence.evaluate(input(moved = 20.0, to = barcelona.take(2)))
        assertEquals(HeuristicStatus.NOT_EVALUATED, learning.status)
        assertTrue(learning.explanation.contains("aprendizaje"))
    }

    @Test fun `5 two distinct recent fixes at the same coordinates can be real rest and are evaluated`() {
        // Coordenadas idénticas (0 m) pero dos fixes distintos: medida real de reposo.
        assertNull(TransitionCoherence.gpsProblem(end(100_000, 99_000), end(105_000, 104_500)))
        assertEquals(HeuristicStatus.FAILED, TransitionCoherence.evaluate(input(moved = 0.0)).status)
        assertEquals(HeuristicStatus.PASSED, TransitionCoherence.evaluate(input(moved = 0.0, to = nearby)).status)
    }

    @Test fun `6 a future or invalid timestamp abstains`() {
        assertNa(input(current = end(105_000, 105_001)), "inválida o futura")
        assertNa(input(previous = end(100_000, 100_500)), "inválida o futura")
        assertNa(input(current = TransitionCoherence.Endpoint(105_000, 0L)), "inválida o futura")
        assertNa(input(previous = TransitionCoherence.Endpoint(100_000, -5L)), "inválida o futura")
        // Fix del extremo nuevo anterior al del extremo viejo: no es un desplazamiento medible.
        assertNa(input(previous = end(100_000, 99_000), current = end(105_000, 98_000)), "es anterior al de la celda anterior")
    }

    @Test fun `7 a visible neighbour or a learned route does not bypass the GPS check`() {
        // Sin baseline en destino, la vecina o la ruta aprendida darían PASSED... con GPS válido.
        assertEquals(HeuristicStatus.PASSED, TransitionCoherence.evaluate(input(to = emptyList(), neighbor = true)).status)
        assertEquals(HeuristicStatus.PASSED, TransitionCoherence.evaluate(input(to = emptyList(), prior = 3)).status)
        // ...pero con el mismo fix o uno antiguo se abstienen antes de llegar al atajo.
        assertNa(input(to = emptyList(), neighbor = true, previous = end(100_000, 99_000), current = end(105_000, 99_000)), "mismo fix")
        assertNa(input(to = emptyList(), prior = 3, current = end(105_000, 80_000)), "más de 10 s")
        assertNa(input(neighbor = true, prior = 5, current = end(105_000, null)), "no hay fix GPS")
    }

    @Test fun `8 N-A results never add to the trusted-route learning`() {
        val results = listOf(
            input(previous = end(100_000, 99_000), current = end(105_000, 99_000), to = nearby, prior = 3, neighbor = true),
            input(current = end(105_000, 50_000), to = nearby, prior = 3),
            input(current = end(105_000, null), to = nearby, neighbor = true),
            input(current = end(105_000, 106_000), to = nearby, prior = 3)
        ).map(TransitionCoherence::evaluate)
        results.forEach {
            assertEquals(HeuristicStatus.NOT_EVALUATED, it.status)
            assertEquals(0, TransitionCoherence.trustedIncrement(it))
        }
        // El mismo caso con GPS válido sí habría enseñado la ruta.
        assertEquals(1, TransitionCoherence.trustedIncrement(TransitionCoherence.evaluate(input(to = nearby, prior = 3))))
    }

    @Test fun `the same live-fix age as location collection, without lowering the 120 s limit`() {
        assertEquals(10_000L, GpsFixAge.LIVE_MAX_AGE_MS)
        val main = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        val location = main.resolve("com/alexisgordr/icdetector/service/LocationCollectionController.kt").readText()
        assertTrue(location.contains("const val LIVE_FIX_MAX_AGE_MS = GpsFixAge.LIVE_MAX_AGE_MS"))
        assertTrue(location.contains("const val MAX_FIX_AGE_MS = 120_000L"))
        val tracker = main.resolve("com/alexisgordr/icdetector/service/ServingTransitionTracker.kt").readText()
        assertTrue(tracker.contains("TransitionCoherence.Endpoint(elapsedMs, location?.elapsedRealtimeNanos)"))
        assertTrue(tracker.contains("previous = prior.endpoint,") && tracker.contains("current = current.endpoint,"))
        assertTrue(tracker.contains("RESULT_TTL_MS = 20_000L"))
        val helper = main.resolve("com/alexisgordr/icdetector/storage/CellDbHelper.kt").readText()
        assertTrue(helper.contains("val trustedIncrement = com.alexisgordr.icdetector.core.TransitionCoherence.trustedIncrement(result)"))
        assertNotNull(TransitionCoherence.gpsProblem(end(1, null), end(2, null)))
    }

    @Test fun `docs mark it as an H16 behaviour cut without recalculating old rows`() {
        fun doc(name: String) = listOf(java.io.File(name), java.io.File("../$name")).first { it.exists() }.readText()
        val changelog = doc("CHANGELOG.md")
        assertTrue(changelog.contains("**H16 needs a recent GPS fix at each end (behaviour cut for H16).**"))
        assertTrue(changelog.contains("Rows\n  from earlier builds are not recalculated"))
        assertTrue(doc("MANUAL.md").contains("The travelled distance is only measured from **two GPS fixes of their own**"))
    }
}
