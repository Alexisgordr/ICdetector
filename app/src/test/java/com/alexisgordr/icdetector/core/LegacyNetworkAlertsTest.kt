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

    @Test fun `no service neither closes nor extends, and a legacy reading restarts the closing count`() {
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
}
