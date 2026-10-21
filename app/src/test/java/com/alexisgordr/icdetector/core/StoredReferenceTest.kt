package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (V3) — La última posición guardada: en el mismo arranque decide el reloj monotónico; en otro
 * arranque o con el formato antiguo se conserva sin tiempo monotónico. (3.1.0: extraído de la prueba
 * de la validación de coordenadas, que se retiró con la verificación externa.)
 */
class StoredReferenceTest {
    private val hour = 3_600_000L
    private val now = 100 * hour                      // elapsedRealtime actual
    private val civilNow = 1_760_000_000_000L
    private fun stored(civilAge: Long, elapsedAge: Long?, boot: String? = "5") =
        StoredReference.serialize(42.8, -1.6, civilNow - civilAge, elapsedAge?.let { now - it } ?: 0L, boot)

    @Test fun `same boot - the monotonic clock decides, whatever the civil clock says`() {
        // Hora corregida: el reloj civil dice 3 días (o −2 h), el monotónico 1 h. Se conserva.
        listOf(3 * 24 * hour, -2 * hour).forEach { civilAge ->
            val r = StoredReference.restore(stored(civilAge, hour), civilNow, now, "5")!!
            assertEquals(now - hour, r.elapsedMs)
            assertEquals(hour, StoredReference.monotonicAgeMs(r.elapsedMs, now))
        }
        // El civil dice 1 h, pero el monotónico 7 h: se descarta.
        assertNull(StoredReference.restore(stored(hour, 7 * hour), civilNow, now, "5"))
        // Marca del futuro en el mismo arranque: imposible, se descarta.
        assertNull(StoredReference.restore(StoredReference.serialize(42.8, -1.6, civilNow, now + 1, "5"), civilNow, now, "5"))
    }

    @Test fun `another boot or the old format is kept conservatively without monotonic time`() {
        listOf(stored(hour, hour, boot = "4"), "42.8,-1.6,${civilNow - hour}", stored(hour, hour, boot = null)).forEach { text ->
            val r = StoredReference.restore(text, civilNow, now, "5")!!
            assertNull(text, r.elapsedMs)
            assertNull(StoredReference.monotonicAgeMs(r.elapsedMs, now))
        }
        // Y con el reloj civil, más de 6 h o en el futuro: fuera.
        assertNull(StoredReference.restore("42.8,-1.6,${civilNow - 7 * hour}", civilNow, now, "5"))
        assertNull(StoredReference.restore("42.8,-1.6,${civilNow + hour}", civilNow, now, "5"))
        assertNull("sin número de arranque actual no se reutiliza", StoredReference.restore(stored(hour, hour), civilNow, now, null)?.elapsedMs)
    }

    @Test fun `malformed stored references are ignored`() {
        listOf(null, "", "1,2", "a,b,c", "1,2,3,4", "1,2,x,4,5", "1,2,3,4,5,6").forEach {
            assertNull("$it", StoredReference.restore(it, civilNow, now, "5"))
        }
    }

    @Test fun `monotonic age needs a positive past timestamp`() {
        assertNull(StoredReference.monotonicAgeMs(null, now))
        assertNull(StoredReference.monotonicAgeMs(0L, now))
        assertNull(StoredReference.monotonicAgeMs(now + 1, now))
        assertEquals(0L, StoredReference.monotonicAgeMs(now, now))
    }

    @Test fun `the service restores the reference with the monotonic clock`() {
        val src = listOf("app/src/main/java/com/alexisgordr/icdetector/service/LocationCollectionController.kt",
            "src/main/java/com/alexisgordr/icdetector/service/LocationCollectionController.kt").map(::File).first { it.exists() }.readText()
        val location = src.substringAfter("private fun restoreReference()").substringBefore("companion object")
        assertTrue(location.contains("StoredReference.restore("))
        assertFalse("la decisión no la toma el reloj civil del servicio", location.contains("currentTimeMillis() -"))
    }
}
