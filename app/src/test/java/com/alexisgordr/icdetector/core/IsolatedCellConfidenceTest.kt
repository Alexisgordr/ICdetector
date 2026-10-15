package com.alexisgordr.icdetector.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IsolatedCellConfidenceTest {
    @Test fun `requires three consecutive fresh observations`() {
        val confidence = IsolatedCellConfidence(3)

        assertFalse(confidence.observe("cell-a", true, 10_000L))
        assertFalse(confidence.observe("cell-a", true, 20_000L))
        assertTrue(confidence.observe("cell-a", true, 30_000L))
    }

    @Test fun `duplicate token cannot advance the streak`() {
        val confidence = IsolatedCellConfidence(3)

        assertFalse(confidence.observe("cell-a", true, 10_000L))
        assertFalse(confidence.observe("cell-a", true, 10_000L))
        assertFalse(confidence.observe("cell-a", true, 20_000L))
        assertTrue(confidence.observe("cell-a", true, 30_000L))
    }

    @Test fun `neighbour snapshot clears the streak immediately`() {
        val confidence = IsolatedCellConfidence(3)
        confidence.observe("cell-a", true, 10_000L)
        confidence.observe("cell-a", true, 20_000L)

        assertFalse(confidence.observe("cell-a", false, 30_000L))
        assertFalse(confidence.observe("cell-a", true, 40_000L))
    }

    @Test fun `handover cannot inherit isolation evidence`() {
        val confidence = IsolatedCellConfidence(3)
        confidence.observe("cell-a", true, 10_000L)
        confidence.observe("cell-a", true, 20_000L)

        assertFalse(confidence.observe("cell-b", true, 30_000L))
        assertFalse(confidence.observe("cell-b", true, 40_000L))
        assertTrue(confidence.observe("cell-b", true, 50_000L))
    }

    // ── 3.0 — Token desconocido frente a token repetido ─────────────────────────────────────

    @Test fun `a zero or negative token is unknown and counts as fresh`() {
        listOf(0L, -5L).forEach { token ->
            val c = IsolatedCellConfidence()
            assertFalse(c.observe("cell", true, token))
            assertFalse(c.observe("cell", true, token))
            assertTrue("$token: antes la racha se quedaba en 1/3 para siempre", c.observe("cell", true, token))
            assertFalse(c.stuckOnRepeatedToken)
        }
    }

    @Test fun `a constant non-zero token still does not count, but it is flagged`() {
        val c = IsolatedCellConfidence()
        c.observe("cell", true, 1_234L)
        repeat(IsolatedCellConfidence.STUCK_REPEATS - 1) { assertFalse(c.observe("cell", true, 1_234L)) }
        assertFalse(c.stuckOnRepeatedToken)
        c.observe("cell", true, 1_234L)
        assertTrue(c.stuckOnRepeatedToken)
        // Una entrega nueva, una con vecinas o un cambio de celda lo despejan.
        c.observe("cell", true, 1_235L); assertFalse(c.stuckOnRepeatedToken)
        repeat(IsolatedCellConfidence.STUCK_REPEATS + 1) { c.observe("other", true, 9L) }
        assertTrue(c.stuckOnRepeatedToken)
        c.observe("other", false, 9L); assertFalse(c.stuckOnRepeatedToken)
    }

    @Test fun `the service no longer turns an unknown token into zero and says when it is stuck`() {
        val src = listOf("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt", "src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt")
            .map(::File).first { it.exists() }.readText()
        assertFalse(src.contains("activeObservationToken ?: 0L"))
        assertTrue(src.contains("val observationToken = activeObservationToken?.takeIf { it > 0L }"))
        assertTrue(src.contains("if (isolatedCellConfidence.stuckOnRepeatedToken) {"))
        assertTrue(src.contains("appendLog(\"[RADIO]\", ISOLATED_TOKEN_STUCK_LOG)"))
    }
}
