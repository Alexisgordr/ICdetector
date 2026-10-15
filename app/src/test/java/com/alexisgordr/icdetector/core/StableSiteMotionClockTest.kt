package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — El clasificador de movimiento usa solo tiempo monotónico (elapsedRealtime): orden, frescura
 * y duración no dependen del reloj civil, el sueño profundo cuenta y nada cruza un reinicio.
 */
class StableSiteMotionClockTest {
    private val boot = 5_000_000L   // elapsedRealtime arbitrario desde el arranque
    private fun fix(t: Long, lat: Double = 42.8, accuracy: Float = 8f) = MotionFix(lat, -1.6, boot + t, accuracy, 0f)

    private fun staticAfterTwoMinutes(c: MotionClassifier) {
        listOf(0L, 30_000L, 60_000L, 90_000L, 120_000L).forEach { c.observe(fix(it), boot + it) }
    }

    @Test fun `a civil clock change does not matter, only monotonic time is used`() {
        // Los fixes llevan tiempo monotónico; la hora civil puede saltar horas sin efecto alguno.
        val c = MotionClassifier()
        staticAfterTwoMinutes(c)
        assertEquals(MotionState.STATIC_CONFIRMED, c.current(boot + 125_000).state)
    }

    @Test fun `a stale fix is ignored and does not create static evidence`() {
        val c = MotionClassifier()
        c.observe(fix(0), boot)
        // Llega 2 min tarde (callback antiguo o caché): no es evidencia de este momento.
        val late = c.observe(fix(30_000), boot + 30_000 + 120_000)
        assertEquals(MotionReason.FIRST_FIX, late.reason)
        assertEquals(0L, late.durationSeconds)
    }

    @Test fun `out of order fixes do not rewind the window`() {
        val c = MotionClassifier()
        c.observe(fix(0), boot); c.observe(fix(60_000), boot + 60_000)
        val before = c.current(boot + 60_001)
        c.observe(fix(20_000), boot + 60_002)
        assertEquals(before, c.current(boot + 60_002))
    }

    @Test fun `deep sleep expires the window because elapsedRealtime keeps counting`() {
        val c = MotionClassifier()
        staticAfterTwoMinutes(c)
        val afterSleep = c.current(boot + 120_000 + 30 * 60_000L)
        assertEquals(MotionState.UNKNOWN, afterSleep.state)
        assertEquals(MotionReason.NO_RECENT_FIX, afterSleep.reason)
        // El primer fix tras despertar empieza de cero: no suma el tiempo dormido.
        val first = c.observe(fix(120_000 + 30 * 60_000L), boot + 120_000 + 30 * 60_000L)
        assertEquals(MotionReason.FIRST_FIX, first.reason)
    }

    @Test fun `a fix from an earlier boot is never reused`() {
        // Tras reiniciar el aparato, elapsedRealtime vuelve a empezar: un Location en caché de antes
        // lleva un tiempo "futuro" para este arranque.
        val c = MotionClassifier()
        val now = 10_000L
        val fromPreviousBoot = MotionFix(42.8, -1.6, 9_000_000L, 8f, 0f)
        assertEquals(MotionReason.NO_RECENT_FIX, c.observe(fromPreviousBoot, now).reason)
        assertEquals(MotionState.UNKNOWN, c.current(now).state)
    }

    @Test fun `a new process starts from unknown`() {
        val restarted = MotionClassifier()
        assertEquals(MotionState.UNKNOWN, restarted.current(boot).state)
        assertEquals(MotionReason.NO_RECENT_FIX, restarted.current(boot).reason)
    }

    @Test fun `the service feeds monotonic time, civil time only names the day`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val service = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val observe = service.substringAfter("private fun observeMotionFix(location: Location) {").substringBefore("\n    }\n")
        assertTrue(observe.contains("location.elapsedRealtimeNanos / 1_000_000L"))
        assertTrue(observe.contains("SystemClock.elapsedRealtime()"))
        assertTrue(!observe.contains("location.time,"))
        assertTrue(service.contains("stableSiteMotion.current(SystemClock.elapsedRealtime())"))
        val db = main.resolve("com/alexisgordr/icdetector/storage/CellDbHelper.kt").readText()
        assertTrue("el día se sigue nombrando con la hora civil",
            db.substringAfter("fun recordStableSiteContext(").contains("wallMs: Long = System.currentTimeMillis()"))
    }
}
