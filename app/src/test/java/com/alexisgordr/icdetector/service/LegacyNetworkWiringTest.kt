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
        assertTrue(alerts.contains("legacyProtection.onEpisodeStart(kind, legacyAlertMode(), previousDbm)"))
        assertFalse("sin tono propio en el cambio 4G/5G → 2G/3G", alerts.contains("TONE_SUP_ERROR"))
        assertFalse(alerts.contains("legacyProtection.trigger"))
        assertFalse(alerts.contains("legacyProtectionEnabled"))
    }

    @Test fun `off shows nothing, notify is silent, and notify-sound adds no manual tone`() {
        val protection = src("LegacyNetworkProtection.kt")
        val body = protection.substringAfter("fun onEpisodeStart(").substringBefore("\n    }\n")
        assertTrue(body.contains("if (mode == LegacyNetworkAlertMode.OFF) return"))
        assertTrue(body.indexOf("if (mode == LegacyNetworkAlertMode.OFF) return") < body.indexOf("showLegacyNetworkNotice"))
        assertTrue(body.contains("silent = mode != LegacyNetworkAlertMode.NOTIFY_SOUND"))
        listOf("playWarningTone", "startTone", "ToneGenerator").forEach { assertFalse("tono manual: $it", protection.contains(it)) }
        assertTrue(protection.contains("no es un ataque confirmado"))
    }

    @Test fun `2G-3G and confirmed alarms use independent channels`() {
        val n = src("ServiceNotificationController.kt")
        assertTrue(n.contains("const val LEGACY_CHANNEL_ID = \"miniic_legacy_network\""))
        assertTrue(n.contains("const val ALERT_CHANNEL_ID = \"miniic_security_alerts\""))
        assertTrue(Regex("""LEGACY_CHANNEL_ID,\s*context.getString\(R.string.channel_legacy_network_name\),\s*NotificationManager.IMPORTANCE_DEFAULT""").containsMatchIn(n))
        val notice = n.substringAfter("fun showLegacyNetworkNotice(").substringBefore("\n    }\n")
        assertTrue(notice.contains("NotificationCompat.Builder(context, LEGACY_CHANNEL_ID)"))
        assertFalse(notice.contains("ALERT_CHANNEL_ID"))
        assertFalse("un aviso nuevo debe poder sonar aunque el anterior siga visible", notice.contains("setOnlyAlertOnce"))
        val alarm = n.substringAfter("fun showConfirmedAlarm(").substringBefore("\n    }\n")
        assertTrue(alarm.contains("NotificationCompat.Builder(context, ALERT_CHANNEL_ID)"))
        // #35 sigue mirando el canal de alarmas confirmadas, no el de 2G/3G.
        val audibility = n.substringAfter("fun alarmAudibility(").substringBefore("\n        }\n")
        assertTrue(audibility.contains("getNotificationChannel(ALERT_CHANNEL_ID)"))
        assertFalse(audibility.contains("LEGACY_CHANNEL_ID"))
        assertFalse("no se borra ningún canal", n.contains("deleteNotificationChannel"))
    }

    @Test fun `a confirmed alarm sounds through its notification only, not a tone per evaluation`() {
        val alerts = src("SecurityAlertController.kt")
        val suspicious = alerts.substringAfter("if (cell.isSuspicious) {").substringBefore("} else {")
        assertFalse(suspicious.contains("startTone"))
        assertTrue(suspicious.contains("if (confirmed && alarmEpisodes.onConfirmedAlarm(episodeKey))"))
        assertTrue("por defecto, la identidad de la celda", alerts.contains("episodeKey: String = cell.identityKey"))
        assertTrue(suspicious.contains("notifyConfirmedAlarm(cell)"))
        // El aviso independiente de potencia fuerte se conserva (el de latencia se retiró en 3.1.0).
        assertTrue(alerts.contains("TONE_CDMA_ALERT_CALL_GUARD"))
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

    @Test fun `losing the active cell interrupts the 2G-3G closing count`() {
        val service = src("MiniICService.kt")
        val lost = service.substringAfter("private fun onSignalLost() {").substringBefore("\n    }")
        assertTrue(lost.contains("securityAlerts.onServiceInterrupted()"))
        assertTrue(service.contains("if (activeRaw == null && ::securityAlerts.isInitialized) securityAlerts.onServiceInterrupted()"))
        val alerts = src("SecurityAlertController.kt").substringAfter("fun onServiceInterrupted() {").substringBefore("\n    }")
        assertTrue(alerts.contains("legacyEpisodes.interrupt()"))
        assertTrue("sin continuidad no hay «cambio desde 4G/5G»", alerts.contains("previousNetworkType = null"))
    }

    @Test fun `previous signal is passed as context only, validated, and never scored`() {
        val alerts = src("SecurityAlertController.kt")
        assertTrue(alerts.contains("legacyProtection.onEpisodeStart(kind, legacyAlertMode(), previousDbm)"))
        val protection = src("LegacyNetworkProtection.kt")
        assertTrue(protection.contains("LegacyNetwork.previousSignalLog(previousDbm)"))
        assertTrue(protection.contains("previousDbm = LegacyNetwork.previousSignalDbm(previousDbm)"))
        val notice = src("ServiceNotificationController.kt").substringAfter("fun showLegacyNetworkNotice(").substringBefore("\n    }\n")
        assertTrue(notice.contains("R.string.notif_legacy_previous_unknown"))
        listOf("ThreatAnalyzer.kt", "BayesianScorer.kt", "TemporalConfidence.kt").forEach {
            assertFalse(it, src(it).contains("previousSignalDbm"))
        }
    }

    @Test fun `alert durations use the injected monotonic clock`() {
        val alerts = src("SecurityAlertController.kt")
        assertFalse("el tono de señal fuerte usaba el reloj civil", alerts.contains("System.currentTimeMillis"))
        assertTrue(alerts.contains("nowMs: () -> Long = SystemClock::elapsedRealtime"))
        val strong = alerts.substringAfter("strongSignalThreshold()) {").substringBefore("\n        }\n")
        assertTrue(strong.contains("val now = nowMs()"))
    }
}
