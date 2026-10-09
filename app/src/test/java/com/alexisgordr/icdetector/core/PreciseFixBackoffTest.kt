package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 (#7, O5) — Los intentos normales de fix preciso se espacian si fallan seguidos. */
class PreciseFixBackoffTest {

    @Test fun `the interval doubles with each consecutive failure up to ten minutes`() {
        val backoff = PreciseFixBackoff()
        val seen = mutableListOf(backoff.minIntervalMs())
        repeat(7) { backoff.onTimeout(); seen += backoff.minIntervalMs() }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L, 600_000L), seen)
    }

    @Test fun `an accepted fix restores the normal interval`() {
        val backoff = PreciseFixBackoff()
        repeat(3) { backoff.onTimeout() }
        backoff.onSuccess()
        assertEquals(30_000L, backoff.minIntervalMs())
        assertEquals(0, backoff.consecutiveFailures)
    }

    @Test fun `forced requests never wait and the controller records failures and successes`() {
        val controller = listOf(
            File("src/main/java/com/alexisgordr/icdetector/service/LocationCollectionController.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/service/LocationCollectionController.kt")
        ).first { it.exists() }.readText()
        assertTrue(controller.contains("if (!force && now - lastForcedFixTime < preciseFixBackoff.minIntervalMs()) return"))
        assertTrue(controller.contains("preciseFixBackoff.onTimeout()"))
        assertTrue(controller.contains("preciseFixBackoff.onSuccess()"))
    }
}
