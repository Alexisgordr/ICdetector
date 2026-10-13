package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.ApiCoordinateCheck.Reason
import com.alexisgordr.icdetector.core.ApiCoordinateCheck.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (V3) — Coordenada de OpenCellID: la edad de la posición de referencia es monotónica, la falta
 * de referencia no rechaza pero se distingue, y las barreras previas se mantienen.
 */
class ApiCoordinateCheckTest {
    private val hour = 3_600_000L
    private val now = 100 * hour                      // elapsedRealtime actual
    private val pamplona = 42.8125 to -1.6458
    private fun ref(elapsed: Long?, at: Pair<Double, Double> = pamplona) = CoordinateReference(at.first, at.second, elapsed)
    private fun check(lat: Double, lon: Double, shared: Int? = 0, live: CoordinateReference? = null, recent: CoordinateReference? = null) =
        ApiCoordinateCheck.check(lat, lon, shared, live, recent, now)

    private val near = 42.80 to -1.60                  // ~4 km
    private val madrid = 40.4168 to -3.7038            // ~310 km
    private val paris = 48.8566 to 2.3522              // ~730 km

    @Test fun `the fallback age is monotonic, so civil clock changes do not matter`() {
        // La referencia no lleva hora civil: adelantar o atrasar el reloj no la cambia.
        assertEquals(hour, ApiCoordinateCheck.monotonicAgeMs(now - hour, now))
        assertTrue(ApiCoordinateCheck.isUsableFallback(ref(now - 6 * hour), now))
        assertFalse(ApiCoordinateCheck.isUsableFallback(ref(now - 6 * hour - 1), now))
        assertEquals(Result.REJECTED, check(paris.first, paris.second, recent = ref(now - hour)).result)
        assertEquals(Result.FOUND_DISTANCE_CHECKED, check(madrid.first, madrid.second, recent = ref(now - hour)).result)
    }

    @Test fun `an old reference is not used and the coordinate is not auto-rejected`() {
        val o = check(paris.first, paris.second, recent = ref(now - 7 * hour))
        assertEquals(Result.FOUND_WITHOUT_DISTANCE_CHECK, o.result)
        assertNull(o.distanceM)
    }

    @Test fun `a future, zero or missing monotonic time cannot prove its age`() {
        listOf(now + 1, 0L, -5L, null).forEach {
            assertNull("$it", ApiCoordinateCheck.monotonicAgeMs(it, now))
            assertEquals("$it", Result.FOUND_WITHOUT_DISTANCE_CHECK, check(paris.first, paris.second, recent = ref(it)).result)
        }
    }

    @Test fun `without any reference the record is found but not contrasted`() {
        assertEquals(Result.FOUND_WITHOUT_DISTANCE_CHECK, check(paris.first, paris.second).result)
        assertEquals(Result.FOUND_WITHOUT_DISTANCE_CHECK, check(near.first, near.second).result)
    }

    @Test fun `a live fix keeps the 50 km limit and wins over the fallback`() {
        assertEquals(Result.FOUND_DISTANCE_CHECKED, check(near.first, near.second, live = ref(null)).result)
        val far = check(madrid.first, madrid.second, live = ref(null), recent = ref(now - hour))
        assertEquals(Result.REJECTED, far.result)
        assertEquals(Reason.TOO_FAR_FROM_LIVE, far.reason)
        assertTrue(far.distanceM!! > 50_000)
    }

    @Test fun `existing barriers are kept with or without a reference`() {
        listOf(null, ref(now - hour)).forEach { r ->
            assertEquals(Reason.ZERO, check(0.0, 0.0, recent = r).reason)
            assertEquals(Reason.OUT_OF_RANGE, check(91.0, 0.0, recent = r).reason)
            assertEquals(Reason.OUT_OF_RANGE, check(0.0, -181.0, recent = r).reason)
            assertEquals(Reason.OUT_OF_RANGE, check(Double.NaN, 1.0, recent = r).reason)
            assertEquals(Reason.SHARED_ACROSS_AREAS, check(near.first, near.second, shared = 3, recent = r).reason)
            assertEquals(Result.REJECTED, check(near.first, near.second, shared = 3, recent = r).result)
        }
        assertEquals(Result.FOUND_DISTANCE_CHECKED, check(near.first, near.second, shared = 2, live = ref(null)).result)
        // Fallo de la base (null): la distancia sigue aplicando.
        assertEquals(Result.REJECTED, check(paris.first, paris.second, shared = null, recent = ref(now - hour)).result)
    }

    @Test fun `the service measures monotonic age and logs the missing contrast`() {
        fun src(p: String) = listOf("app/src/main/java/com/alexisgordr/icdetector/$p", "src/main/java/com/alexisgordr/icdetector/$p").map(::File).first { it.exists() }.readText()
        val validator = src("service/ApiCoordinateValidator.kt")
        assertFalse(validator.contains("currentTimeMillis"))
        assertFalse(validator.contains(".time"))
        assertTrue(validator.contains("elapsedRealtimeNanos"))
        assertTrue(validator.contains("SystemClock::elapsedRealtime"))
        val controller = src("service/ExternalVerificationController.kt")
        assertTrue(controller.contains("log(ApiCoordinateCheck.LOG_NO_DISTANCE_CHECK)"))
        // La referencia guardada solo recupera su tiempo monotónico en el mismo arranque.
        val location = src("service/LocationCollectionController.kt").substringAfter("private fun restoreReference()")
        assertTrue(location.contains("parts[4] == bootCount()"))
        assertTrue(location.contains("it <= SystemClock.elapsedRealtime()"))
    }
}
