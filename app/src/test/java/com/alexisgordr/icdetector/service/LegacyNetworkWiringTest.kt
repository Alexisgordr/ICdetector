package com.alexisgordr.icdetector.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — Avisos 2G/3G: una sola ruta, siempre bajo la preferencia, sin tonos sueltos. */
class LegacyNetworkWiringTest {
    private val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
    private fun src(name: String) = root.walkTopDown().first { it.name == name }.readText()

    @Test fun `every 2G-3G path goes through the episode gate and the preference`() {
        val alerts = src("SecurityAlertController.kt")
        assertEquals(1, Regex("""legacyEpisodes\.observe\(""").findAll(alerts).count())
        assertTrue(alerts.contains("legacyProtection.onEpisodeStart(kind, legacyAlertMode())"))
        assertFalse("sin tono propio en el cambio 4G/5G → 2G/3G", alerts.contains("TONE_SUP_ERROR"))
        assertFalse(alerts.contains("legacyProtection.trigger"))
        assertFalse(alerts.contains("legacyProtectionEnabled"))
    }

    @Test fun `the tone only plays with notification and sound, and off shows nothing`() {
        val protection = src("LegacyNetworkProtection.kt")
        val body = protection.substringAfter("fun onEpisodeStart(").substringBefore("\n    }\n")
        assertTrue(body.indexOf("if (mode == LegacyNetworkAlertMode.OFF) return") < body.indexOf("playWarningTone()"))
        assertTrue(body.contains("if (mode == LegacyNetworkAlertMode.NOTIFY_SOUND) playWarningTone()"))
        assertTrue(body.contains("silent = mode != LegacyNetworkAlertMode.NOTIFY_SOUND"))
        assertTrue(protection.contains("no es un ataque confirmado"))
    }

    @Test fun `the service reads a real preference instead of a hard-coded flag`() {
        val service = src("MiniICService.kt")
        assertFalse(service.contains("is3gAirplaneModeEnabled"))
        assertTrue(service.contains("LegacyNetworkAlertMode.fromStored(\n            prefs.getString(com.alexisgordr.icdetector.core.LegacyNetworkAlertMode.PREF_KEY, null)"))
        assertTrue(service.contains("legacyAlertMode = { legacyNetworkAlertMode }"))
        val settings = src("SettingsScreen.kt")
        assertTrue(settings.contains("putString(LegacyNetworkAlertMode.PREF_KEY, legacyMode.storedValue)"))
        assertTrue(settings.contains("service?.legacyNetworkAlertMode = legacyMode"))
        val notice = src("ServiceNotificationController.kt").substringAfter("fun showLegacyNetworkNotice(").substringBefore("\n    }\n")
        assertTrue(notice.contains(".setSilent(silent)"))
        assertTrue(notice.contains("Settings.ACTION_AIRPLANE_MODE_SETTINGS"))
    }

    @Test fun `airplane mode is only offered, never written, and no privileged permission is promised`() {
        listOf("LegacyNetworkProtection.kt", "MiniICService.kt", "SecurityAlertController.kt", "ServiceNotificationController.kt").forEach {
            val code = src(it)
            assertFalse("$it escribe ajustes protegidos", code.contains("Settings.Global.putInt"))
            assertFalse(it, code.contains("ACTION_AIRPLANE_MODE_CHANGED"))
        }
        val app = root.parentFile
        assertFalse(Regex("""<uses-permission[^>]*WRITE_SECURE_SETTINGS""").containsMatchIn(File(app, "AndroidManifest.xml").readText()))
        val manual = listOf(File("MANUAL.md"), File("../MANUAL.md"), File("../../MANUAL.md")).first { it.exists() }.readText()
        assertFalse(manual.contains("WRITE_SECURE_SETTINGS"))
        assertTrue(manual.contains("never turns it on by itself"))
    }
}
