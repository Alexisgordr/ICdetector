package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.PingPongRule.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** v2.10.9 — H10 sin Android: tiempos de los cambios de celda y velocidad (nullable). */
class PingPongRuleTest {
    private val now = 1_700_000_000_000L
    private val burst = listOf(now - 3_000L, now - 2_000L, now - 1_000L)

    @Test fun `three changes in 10 s while stationary fail`() {
        assertEquals(Outcome.FAILED, PingPongRule.evaluate(burst, now, speedMps = 0f))
        assertEquals(Outcome.FAILED, PingPongRule.evaluate(burst, now, speedMps = 8f))
    }

    @Test fun `fewer than three changes pass`() {
        assertEquals(Outcome.PASSED, PingPongRule.evaluate(burst.take(2), now, speedMps = 0f))
        assertEquals(Outcome.PASSED, PingPongRule.evaluate(emptyList(), now, speedMps = null))
    }

    @Test fun `moving fast passes`() {
        assertEquals(Outcome.PASSED_MOVING, PingPongRule.evaluate(burst, now, speedMps = 8.1f))
        assertEquals(Outcome.PASSED_MOVING, PingPongRule.evaluate(burst, now, speedMps = 25f))
    }

    @Test fun `unknown speed is not evaluated, never assumed stationary`() {
        assertEquals(Outcome.NOT_EVALUATED, PingPongRule.evaluate(burst, now, speedMps = null))
    }

    @Test fun `an old burst is forgotten once 10 s have passed`() {
        assertEquals(Outcome.FAILED, PingPongRule.evaluate(burst, now + 6_000L, speedMps = 0f))
        assertEquals(Outcome.FAILED, PingPongRule.evaluate(burst, now + 7_000L, speedMps = 0f))
        assertEquals(Outcome.PASSED, PingPongRule.evaluate(burst, now + 9_000L, speedMps = 0f))
        assertEquals(Outcome.PASSED, PingPongRule.evaluate(burst, now + 3_600_000L, speedMps = 0f))
    }

    @Test fun `only changes inside the window count`() {
        val mixed = listOf(now - 60_000L, now - 30_000L, now - 2_000L, now - 1_000L)
        assertEquals(Outcome.PASSED, PingPongRule.evaluate(mixed, now, speedMps = 0f))
    }

    @Test fun `future timestamps do not count`() {
        val skewed = listOf(now + 1_000L, now + 2_000L, now + 3_000L)
        assertEquals(Outcome.PASSED, PingPongRule.evaluate(skewed, now, speedMps = 0f))
    }
}
