package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — Cableado de los avisos silenciosos de Stable-Site: canal propio sin sonido, ninguna
 * conexión con alarmas ni puntuaciones, un fallo de la notificación no rompe el análisis, y la
 * restauración y el primer arranque fijan el corte. Comprobado sobre el código fuente.
 */
class StableSiteNoticesWiringTest {
    private val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }
    private fun src(path: String) = main.resolve("com/alexisgordr/icdetector/$path").readText()

    @Test fun `the service notifies after saving the decision and a failure never breaks the analysis`() {
        val service = src("service/MiniICService.kt")
        assertTrue(service.contains("dbHelper.applyStableSiteDecision(stableSiteCandidate, activeRaw, siteKeys)\n                        .also { maybeNotifyStableSite(it) }"))
        val notify = service.substringAfter("private fun maybeNotifyStableSite(").substringBefore("private fun onAnalysisFailed(")
        assertTrue(notify.contains("notices.shouldNotify(decision, enabled, since)"))
        assertTrue(notify.contains("prefs.getBoolean(notices.PREF_ENABLED, notices.DEFAULT_ENABLED)"))
        assertTrue(notify.contains("} catch (e: CancellationException) {\n            throw e\n        } catch (e: Exception) {"))
        assertTrue(notify.indexOf("appendLog(") < notify.indexOf("showStableSiteNotice("))
        listOf("securityAlerts", "checkAlerts", "ToneGenerator", "ALERT_CHANNEL_ID", "showConfirmedAlarm", "temporalConfidence",
            "threatEpisodeTracker", "localCellTrust", "securityScore", "bayes", "dbHelper").forEach {
            assertFalse("el aviso no toca $it", notify.contains(it, ignoreCase = true))
        }
        // Corte: en onCreate solo si falta, después de la puerta de la primera apertura.
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("override fun ")
        val cutoff = onCreate.indexOf("StableSiteNotices.PREF_NOTIFY_SINCE_MS")
        assertTrue(cutoff > onCreate.indexOf("FirstRun.allowsCollection(this)"))
        assertTrue(onCreate.contains("if (!prefs.contains(com.alexisgordr.icdetector.core.StableSiteNotices.PREF_NOTIFY_SINCE_MS))"))
    }

    @Test fun `scores, temporal confirmation, trust and alarms do not know about the notices`() {
        listOf("core/ThreatAnalyzer.kt", "core/BayesianScorer.kt", "core/LocalCellTrustEngine.kt", "core/TemporalConfidence.kt",
            "service/SecurityAlertController.kt").forEach { path ->
            val f = main.resolve("com/alexisgordr/icdetector/$path")
            if (f.exists()) {
                val text = f.readText()
                assertFalse(path, text.contains("StableSiteNotices") || text.contains("episodeRecord") || text.contains("STABLE_SITE_CHANNEL_ID"))
            }
        }
        assertTrue(src("core/StableSite.kt").contains("const val STABLE_SITE_ENFORCEMENT_ENABLED = false"))
    }

    @Test fun `own low-importance channel without sound or vibration, never the alarm channel`() {
        val n = src("service/ServiceNotificationController.kt")
        assertTrue(n.contains("const val STABLE_SITE_CHANNEL_ID = \"miniic_stable_site_notices\""))
        val ids = Regex("""const val \w*CHANNEL_ID = "([^"]+)"""").findAll(n).map { it.groupValues[1] }.toList()
        assertEquals("ids de canal distintos", ids.size, ids.toSet().size)
        val channel = n.substringAfter("STABLE_SITE_CHANNEL_ID,\n").substringBefore("        )\n    }")
        listOf("NotificationManager.IMPORTANCE_LOW", "setSound(null, null)", "enableVibration(false)").forEach { assertTrue(it, channel.contains(it)) }
        val show = n.substringAfter("fun showStableSiteNotice(").substringBefore("    /**")
        assertTrue(show.contains("NotificationCompat.Builder(context, STABLE_SITE_CHANNEL_ID)"))
        listOf(".setSilent(true)", "PRIORITY_LOW", "StableSiteNotices.EXTRA_EPISODE_ID", "PendingIntent.FLAG_IMMUTABLE", ".setAutoCancel(true)")
            .forEach { assertTrue(it, show.contains(it)) }
        listOf("ALERT_CHANNEL_ID", "ToneGenerator", "setSound(", "setVibrate(", "PRIORITY_HIGH").forEach { assertFalse(it, show.contains(it)) }
    }

    @Test fun `texts say what happened, that it is not a confirmed attack, and never claim one`() {
        val es = res.resolve("values-es/strings.xml").readText()
        val en = res.resolve("values/strings.xml").readText()
        fun value(xml: String, name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(xml)!!.groupValues[1]
        assertEquals("Cambio inusual en este lugar", value(es, "notif_stable_site_title"))
        assertEquals("Avisos silenciosos de Stable-Site", value(es, "stable_site_notices_title"))
        assertEquals("Disponibles cuando la referencia del lugar esté preparada. No activan alarmas.", value(es, "stable_site_notices_description"))
        assertEquals("Referencia en aprendizaje", value(es, "stable_site_notice_status_learning"))
        assertEquals("Referencia preparada: avisos silenciosos disponibles", value(es, "stable_site_notice_status_ready"))
        assertEquals("Avisos desactivados por el usuario", value(es, "stable_site_notice_status_disabled"))
        assertTrue(value(es, "notif_stable_site_body").contains("No confirma un ataque."))
        assertTrue(value(en, "notif_stable_site_body").contains("It does not confirm an attack."))
        val names = listOf("stable_site_notices_title", "stable_site_notices_description", "channel_stable_site_name",
            "channel_stable_site_description", "notif_stable_site_title", "notif_stable_site_body", "stable_site_notice_status_learning",
            "stable_site_notice_status_ready", "stable_site_notice_status_disabled", "stable_site_episode_focus_title",
            "stable_site_episode_focus_note", "stable_site_episode_focus_missing", "stable_site_episode_focus_open_sites")
        names.forEach { name ->
            listOf(es, en).forEach { xml ->
                val text = value(xml, name)
                listOf("IMSI catcher detectado", "IMSI catcher detected", "ataque confirmado", "confirmed attack", "celda ilegítima", "illegitimate cell")
                    .forEach { assertFalse("$name: $it", text.contains(it, ignoreCase = true)) }
            }
        }
        assertTrue(value(es, "stable_site_episode_focus_missing").contains("120 días"))
    }

    @Test fun `only the user turns the preference on or off`() {
        val writers = main.walkTopDown().filter { it.extension == "kt" }
            .filter { it.readText().contains("putBoolean(StableSiteNotices.PREF_ENABLED") || it.readText().contains("putBoolean(com.alexisgordr.icdetector.core.StableSiteNotices.PREF_ENABLED") }
            .map { it.name }.toList()
        assertEquals(listOf("SettingsScreen.kt"), writers)
        val settings = src("ui/SettingsScreen.kt")
        assertTrue(settings.contains("putBoolean(StableSiteNotices.PREF_ENABLED, stableSiteNotices)"))
        assertTrue(settings.contains("prefs.getBoolean(StableSiteNotices.PREF_ENABLED, StableSiteNotices.DEFAULT_ENABLED)"))
    }

    @Test fun `a restore moves the cutoff so restored episodes never notify`() {
        val restore = src("backup/RestoreStartup.kt")
        val apply = restore.substringAfter("private fun applySettings(").substringBefore("fun prefs(")
        assertTrue(apply.indexOf("StableSiteNotices.PREF_NOTIFY_SINCE_MS") in 0 until apply.indexOf("editor.commit()"))
    }

    @Test fun `the episode is recorded with an atomic conditional update`() {
        val helper = src("storage/CellDbHelper.kt")
        val update = helper.substringAfter("private fun updateStableSiteEpisode(").substringBefore("private fun findActiveStableSiteHold(")
        assertTrue(update.contains("\"episode_id=? AND candidate_identity=? AND shadow_recorded=0\""))
        assertFalse("el flag no vuelve a 0", update.contains("put(\"shadow_recorded\",0)"))
        assertTrue(update.contains("return newlyRecorded"))
    }

    @Test fun `tapping the notice opens the episode, also with the app already open`() {
        val activity = src("MainActivity.kt")
        assertTrue(activity.contains("if (savedInstanceState == null) com.alexisgordr.icdetector.ui.StableSiteNoticeTarget.consume(intent)"))
        assertTrue(activity.substringAfter("override fun onNewIntent(").contains("StableSiteNoticeTarget.consume(intent)"))
        val screen = src("ui/MainScreen.kt")
        assertTrue(screen.contains("StableSiteEpisodeFocus(dbHelper, noticeEpisode, Modifier.fillMaxSize()) { StableSiteNoticeTarget.clear() }"))
        val focus = src("ui/StableSiteScreen.kt").substringAfter("fun StableSiteEpisodeFocus(")
        assertTrue(focus.contains("StableSiteReadStore.episodeById("))
        assertTrue(focus.contains("R.string.stable_site_episode_focus_missing"))
    }

}
