package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.LegacyNetworkEpisodeGate.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0 — Avisos 2G/3G: preferencia y un aviso por episodio continuo. */
class LegacyNetworkAlertsTest {
    @Test fun `mode defaults to notification only, also for missing or unknown values`() {
        assertEquals(LegacyNetworkAlertMode.NOTIFY, LegacyNetworkAlertMode.DEFAULT)
        assertEquals(LegacyNetworkAlertMode.NOTIFY, LegacyNetworkAlertMode.fromStored(null))
        assertEquals(LegacyNetworkAlertMode.NOTIFY, LegacyNetworkAlertMode.fromStored("LOUD"))
        LegacyNetworkAlertMode.entries.forEach { assertEquals(it, LegacyNetworkAlertMode.fromStored(it.storedValue)) }
    }

    @Test fun `one alert per continuous episode instead of every minute`() {
        val g = LegacyNetworkEpisodeGate()
        assertEquals(Trigger.CHANGE_FROM_MODERN, g.observe("3G", "4G LTE", 0))
        // 10 minutos en 3G: ningún aviso más.
        for (t in 5_000L..600_000L step 5_000L) assertNull(g.observe("3G HSPA", "3G HSPA", t))
        assertTrue(g.open)
    }

    @Test fun `a short return to 4G does not close the episode`() {
        val g = LegacyNetworkEpisodeGate()
        g.observe("2G", null, 0)
        assertNull(g.observe("4G LTE", "2G", 10_000))
        assertNull(g.observe("4G LTE", "4G LTE", 50_000))        // 40 s en 4G
        assertNull(g.observe("2G", "4G LTE", 60_000))            // vuelve a 2G: mismo episodio
        assertTrue(g.open)
    }

    @Test fun `sixty seconds in a row on 4G-5G close it and the next 2G-3G starts another`() {
        val g = LegacyNetworkEpisodeGate()
        assertEquals(Trigger.USE, g.observe("3G", null, 0))
        g.observe("5G NR", "3G", 10_000)
        assertTrue(g.open)
        g.observe("5G NR", "5G NR", 69_999)
        assertTrue("59,999 s no bastan", g.open)
        g.observe("5G NR", "5G NR", 70_000)
        assertFalse(g.open)
        assertEquals(Trigger.CHANGE_FROM_MODERN, g.observe("3G", "5G NR", 80_000))
    }

    @Test fun `no service and legacy readings restart the closing count`() {
        val g = LegacyNetworkEpisodeGate()
        g.observe("3G", null, 0)
        g.observe("N/A", "3G", 30_000)
        g.observe("4G LTE", "N/A", 40_000)
        g.observe("3G", "4G LTE", 90_000)                         // reinicia la cuenta
        g.observe("4G LTE", "3G", 100_000)
        g.observe("4G LTE", "4G LTE", 150_000)
        assertTrue(g.open)
        g.observe("4G LTE", "4G LTE", 160_000)
        assertFalse(g.open)
        g.reset()
        assertEquals(Trigger.USE, g.observe("2G", "N/A", 170_000))
    }

    @Test fun `modern networks never open an episode`() {
        val g = LegacyNetworkEpisodeGate()
        listOf("4G LTE", "5G NR", "N/A", "").forEach { assertNull(g.observe(it, "4G", 0)) }
        assertFalse(g.open)
    }

    @Test fun `3G, 4G, no service, 4G does not close on time counted from the first 4G`() {
        val g = LegacyNetworkEpisodeGate()
        assertEquals(Trigger.USE, g.observe("3G", null, 0))
        g.observe("4G LTE", "3G", 10_000)
        g.observe("N/A", "4G LTE", 40_000)                       // pérdida de servicio
        g.observe("4G LTE", "N/A", 75_000)                       // 65 s desde la primera 4G, pero no seguidos
        assertTrue("la cuenta de cierre se reinició", g.open)
        assertNull("sigue el mismo episodio: sin aviso nuevo", g.observe("3G", "4G LTE", 80_000))
        // Ahora sí: 60 s seguidos en 4G desde 90 s.
        g.observe("4G LTE", "3G", 90_000)
        g.observe("4G LTE", "4G LTE", 149_999)
        assertTrue(g.open)
        g.observe("4G LTE", "4G LTE", 150_000)
        assertFalse(g.open)
    }

    @Test fun `an interruption from the service restarts only the closing count`() {
        val g = LegacyNetworkEpisodeGate()
        g.observe("2G", null, 0)
        g.observe("5G NR", "2G", 10_000)
        g.interrupt()                                           // sin celda activa
        assertTrue("el episodio sigue abierto", g.open)
        g.observe("5G NR", null, 60_000)                         // empieza la cuenta de nuevo
        g.observe("5G NR", "5G NR", 119_999)
        assertTrue(g.open)
        g.observe("5G NR", "5G NR", 120_000)
        assertFalse(g.open)
        // Sin episodio abierto, interrumpir no abre nada.
        g.interrupt()
        assertFalse(g.open)
    }

    @Test fun `previous signal is shown only when it is a valid reading`() {
        assertEquals(-72, LegacyNetwork.previousSignalDbm(-72))
        assertEquals(-140, LegacyNetwork.previousSignalDbm(-140))
        assertEquals(-30, LegacyNetwork.previousSignalDbm(-30))
        listOf(null, -999, Int.MAX_VALUE, Int.MIN_VALUE, -141, -29, 0, 85).forEach {
            assertNull("$it", LegacyNetwork.previousSignalDbm(it))
            assertEquals("señal previa desconocida", LegacyNetwork.previousSignalLog(it))
        }
        assertEquals("última señal anterior observada: -72 dBm", LegacyNetwork.previousSignalLog(-72))
        // El mismo criterio que el perfil local y la línea base de H13.
        assertEquals(com.alexisgordr.icdetector.core.profile.ProfileMetric.SIGNAL_DBM.validRange, LegacyNetwork.VALID_DBM)
        val db = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
            .walkTopDown().first { it.name == "CellDbHelper.kt" }.readText()
        assertTrue(db.contains("dbm in -140..-30"))
    }
}
