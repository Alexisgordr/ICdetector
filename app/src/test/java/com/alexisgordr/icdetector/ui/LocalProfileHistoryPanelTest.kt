package com.alexisgordr.icdetector.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 3.0 — Historial → Perfil local: consulta de evaluaciones guardadas. Solo lectura, sin alertas, sin
 * el perfil actual como referencia histórica, y con la opción experimental controlando lo visible.
 */
class LocalProfileHistoryPanelTest {
    private val app = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun ui(name: String) = File(app, "java/com/alexisgordr/icdetector/ui/$name").readText()

    private fun strings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(app, "res/$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i -> nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent }
    }

    @Test fun `the panel only reads stored evaluations, never the current profile`() {
        val panel = ui("LocalProfileHistoryPanel.kt")
        assertTrue(panel.contains("LocalProfileStore.evaluationsPage(dbHelper.readableDatabase, f, after)"))
        assertTrue(panel.contains("EvidenceParser.parse(entry.evidence)"))
        listOf("LocalProfileStore.profiles(", "LocalProfileStore.allProfiles(", "LocalProfileScorer", "LocalProfileLearner",
            "upsert(", "insertEvaluation(", "recordModeEvent(", "writableDatabase").forEach {
            assertFalse("el panel usa $it", panel.contains(it))
        }
    }

    @Test fun `no alerts, notifications or sounds, and no engine changes`() {
        val panel = ui("LocalProfileHistoryPanel.kt")
        listOf("Notification", "ToneGenerator", "MediaPlayer", "Vibrator", "securityAlerts", "SecurityAlertController",
            "ThreatAnalyzer", "isSuspicious", "securityScore").forEach {
            assertFalse("el panel usa $it", panel.contains(it))
        }
    }

    @Test fun `the tab follows the experimental option and Settings opens it`() {
        val history = ui("HistoryScreen.kt")
        assertTrue(history.contains("var showProfile by remember { mutableStateOf(initialTab == \"profile\") }"))
        assertTrue(history.contains("showProfile || historyContext.getSharedPreferences(\"miniic_prefs\""))
        assertTrue(history.contains("if (profileTabVisible) {"))
        assertTrue(history.contains("showProfile = tab == \"profile\""))
        assertTrue(history.contains("LocalProfileHistoryPanel(dbHelper, Modifier.fillMaxWidth().weight(1f))"))
        val main = ui("MainScreen.kt")
        assertTrue(main.contains("onOpenProfileEvaluations = { showSettings = false; historyTab = \"profile\"; showHistory = true }"))
        assertTrue(main.contains("HistoryPanel(dbHelper = dbHelper, service = service, initialTab = historyTab)"))
        assertTrue(main.contains("showSettings = false; historyTab = null"))
        assertTrue(ui("LocalProfileSection.kt").contains("onClick = onViewEvaluations"))
        assertTrue(ui("SettingsScreen.kt").contains("LocalProfileSection(onViewEvaluations = onOpenProfileEvaluations)"))
    }

    @Test fun `texts speak of signal deviation and never of attacks, in both languages`() {
        val en = strings("values")
        val es = strings("values-es")
        val keys = en.keys.filter { it.startsWith("profile_") || it == "tab_local_profile" || it == "history_title_local_profile" || it == "local_profile_view_evaluations" }
        assertTrue(keys.size >= 35)
        keys.forEach { key ->
            assertTrue("$key sin traducción", es.containsKey(key))
            listOf(en.getValue(key), es.getValue(key)).forEach { text ->
                listOf("attack", "ataque", "IMSI", "catcher", "threat", "amenaza").forEach {
                    assertFalse("$key menciona $it", text.contains(it, ignoreCase = true))
                }
            }
        }
        assertEquals("Perfil local — desviación de señal (experimental)", es.getValue("history_title_local_profile"))
        assertTrue(es.getValue("profile_history_h13_note").contains("no es una confirmación independiente"))
        assertTrue(en.getValue("profile_history_h13_note").contains("not independent confirmation"))
        assertTrue(es.getValue("profile_history_metric_without_reference").contains("referencia no guardada"))
    }

    @Test fun `changing the filter cancels pending loads and stale responses are dropped`() {
        val panel = ui("LocalProfileHistoryPanel.kt")
        val effect = panel.substringAfter("LaunchedEffect(filter, generation) {").substringBefore("fun loadMore()")
        assertTrue(effect.contains("loadMoreJob?.cancel()"))
        assertTrue(effect.contains("entries = emptyList()"))
        assertTrue(effect.contains("if (!ProfileEvaluationQuery.accepts(current(), token)) return@LaunchedEffect"))
        val more = panel.substringAfter("fun loadMore()").substringBefore("Column(modifier")
        assertTrue(more.contains("if (!ProfileEvaluationQuery.accepts(current(), token)) return@launch"))
        assertTrue(more.contains("ProfileEvaluationQuery.append(entries,"))
    }

    @Test fun `read errors are shown with retry, keep the list and never swallow cancellation`() {
        val panel = ui("LocalProfileHistoryPanel.kt")
        assertFalse(panel.contains("getOrDefault(emptyList())"))
        assertFalse("runCatching también captura la cancelación", panel.contains("runCatching"))
        assertEquals(2, Regex("""catch \(e: CancellationException\) \{\s*throw e""").findAll(panel).count())
        assertTrue(panel.contains("error == LoadError.FIRST_PAGE ->"))
        assertTrue(panel.contains("onClick = { generation++ }"))
        assertTrue(panel.contains("}.onFailure { error = LoadError.MORE }"))
        assertTrue(panel.contains("R.string.profile_history_retry"))
    }
}
