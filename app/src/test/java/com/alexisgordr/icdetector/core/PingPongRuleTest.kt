package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.PingPongRule.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    // ── 3.0 — La velocidad caduca antes que la posición ─────────────────────────────────────

    @Test fun `an old speed does not decide H10`() {
        val now = 100_000L
        val burst = listOf(now - 9_000, now - 5_000, now - 1_000)
        // Parado hace 90 s (semáforo) y tres handovers ahora (tren arrancando): antes FAILED.
        assertEquals(PingPongRule.Outcome.NOT_EVALUATED, PingPongRule.evaluate(burst, now, PingPongRule.liveSpeed(0f, 90_000)))
        // Con un fix en vivo, la velocidad sí decide.
        assertEquals(PingPongRule.Outcome.FAILED, PingPongRule.evaluate(burst, now, PingPongRule.liveSpeed(0f, 2_000)))
        assertEquals(PingPongRule.Outcome.PASSED_MOVING, PingPongRule.evaluate(burst, now, PingPongRule.liveSpeed(20f, 2_000)))
    }

    @Test fun `live speed needs a known, non-negative age under the live limit`() {
        assertEquals(3f, PingPongRule.liveSpeed(3f, 0)!!, 0f)
        assertEquals(3f, PingPongRule.liveSpeed(3f, GpsFixAge.LIVE_MAX_AGE_MS - 1)!!, 0f)
        listOf(null, -1L, GpsFixAge.LIVE_MAX_AGE_MS, 120_000L).forEach { age ->
            assertEquals("$age", null, PingPongRule.liveSpeed(3f, age))
        }
        assertEquals(null, PingPongRule.liveSpeed(null, 0))
    }

    @Test fun `the analyzer and the service pass the fix age`() {
        fun src(p: String) = listOf("app/src/main/java/com/alexisgordr/icdetector/$p", "src/main/java/com/alexisgordr/icdetector/$p")
            .map(::File).first { it.exists() }.readText()
        assertTrue(src("core/ThreatAnalyzer.kt").contains("PingPongRule.liveSpeed(currentLocation?.takeIf { it.hasSpeed() }?.speed, locationFixAgeMs)"))
        val service = src("service/MiniICService.kt")
        assertTrue(service.contains("locationFixAgeMs = currentLocation?.elapsedRealtimeNanos?.takeIf { it > 0 }"))
    }
}
