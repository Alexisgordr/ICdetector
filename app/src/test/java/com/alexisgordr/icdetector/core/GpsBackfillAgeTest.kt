package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.1.0 (#36) — La edad del fix con que onGpsAvailable rellena coordenadas sale del reloj
 * monotónico. Antes se restaba `loc.time` a la hora civil: con la hora del teléfono adelantada un
 * fix recién llegado parecía viejo, y con la hora atrasada uno viejo daba una edad negativa y pasaba.
 */
class GpsBackfillAgeTest {
    private val nowElapsed = 50_000_000L          // elapsedRealtime del teléfono, en ms
    private fun nanos(elapsedMs: Long) = elapsedMs * 1_000_000L

    /** La regla anterior, para comparar: edad = hora civil - loc.time. */
    private fun oldRule(nowCivil: Long, fixTime: Long) = nowCivil - fixTime < 15_000L

    @Test fun `with the wall clock ahead a fresh fix is still used`() {
        val fixTime = 1_790_000_000_000L
        val nowCivil = fixTime + 3_600_000L        // la hora se adelantó una hora después del fix
        assertFalse("la regla anterior lo descartaba", oldRule(nowCivil, fixTime))
        val age = GpsFixAge.ageMs(nowElapsed, nanos(nowElapsed - 3_000L))
        assertEquals(3_000L, age)
        assertTrue(GpsFixAge.usableForBackfill(age))
    }

    @Test fun `with the wall clock behind an old fix is no longer used`() {
        val fixTime = 1_790_000_000_000L
        val nowCivil = fixTime - 3_600_000L        // edad civil negativa
        assertTrue("la regla anterior lo aceptaba", oldRule(nowCivil, fixTime))
        assertFalse(GpsFixAge.usableForBackfill(GpsFixAge.ageMs(nowElapsed, nanos(nowElapsed - 10 * 60_000L))))
    }

    @Test fun `unknown or future monotonic stamps are not usable`() {
        assertNull(GpsFixAge.ageMs(nowElapsed, 0L))
        assertNull(GpsFixAge.ageMs(nowElapsed, nanos(nowElapsed + 1)))
        assertFalse(GpsFixAge.usableForBackfill(null))
        assertTrue(GpsFixAge.usableForBackfill(14_999L))
        assertFalse(GpsFixAge.usableForBackfill(15_000L))
    }

    @Test fun `onGpsAvailable measures with elapsedRealtimeNanos and keeps loc_time for the rows`() {
        val service = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val gps = service.substringAfter("private fun onGpsAvailable()").substringBefore("private fun checkAlerts(")
        assertTrue(gps.contains("GpsFixAge.ageMs(SystemClock.elapsedRealtime(), loc.elapsedRealtimeNanos)"))
        assertTrue(gps.contains("GpsFixAge.usableForBackfill(coordAgeMs)"))
        assertFalse("sin edad civil", gps.contains("System.currentTimeMillis() - loc.time"))
        assertTrue("las filas se casan por hora civil", gps.contains("fixTimeMs = loc.time"))
    }
}
