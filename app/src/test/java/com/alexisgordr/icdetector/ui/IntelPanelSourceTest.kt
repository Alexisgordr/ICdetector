package com.alexisgordr.icdetector.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (UI1) — IntelPanel ya no lee el historial entero: pide las cifras agregadas en IO, tiene
 * estados de carga, error y vacío, y no convierte la cancelación en error. Las cifras se comparan
 * con el cálculo anterior en IntelStatsQueryTest (SQLite real).
 */
class IntelPanelSourceTest {
    private fun src(p: String) = listOf("app/src/main/$p", "src/main/$p").map(::File).first { it.exists() }.readText()

    @Test fun `the panel uses aggregates in IO with loading, error and empty states`() {
        val ui = src("java/com/alexisgordr/icdetector/ui/HistoryScreen.kt")
        val panel = ui.substringAfter("fun IntelPanel(").substringBefore("fun IntelCell(")
        assertFalse(panel.contains("getRecords("))
        assertFalse(panel.contains("HistoryRecord"))
        assertFalse(panel.contains("groupBy") || panel.contains("forEach { record"))
        assertTrue(panel.contains("withContext(Dispatchers.IO)"))
        assertTrue(panel.contains("dbHelper.getIntelStats(checkpoint = { ensureActive() })"))
        assertTrue(panel.contains("catch (e: kotlinx.coroutines.CancellationException) {\n            throw e"))
        listOf("IntelState.Loading ->", "IntelState.Empty ->", "IntelState.Failed ->").forEach { assertTrue(it, panel.contains(it)) }
        assertTrue("el umbral sale de la constante", panel.contains("R.string.intel_suspicious, com.alexisgordr.icdetector.core.ScoreThreshold.SUSPICIOUS_BELOW"))
    }

    @Test fun `clarified labels exist in both languages with the same markers`() {
        val en = src("res/values/strings.xml"); val es = src("res/values-es/strings.xml")
        fun v(x: String, k: String) = Regex("<string name=\"$k\">(.*?)</string>").find(x)!!.groupValues[1]
        listOf("intel_connections", "intel_suspicious", "intel_with_gps", "intel_anomalies", "intel_loading", "intel_load_error").forEach { k ->
            val markers = { s: String -> Regex("%\\d\\$[sd]").findAll(s).map { it.value }.toList() }
            assertTrue(k, markers(v(en, k)) == markers(v(es, k)))
        }
        assertTrue(v(es, "intel_suspicious").contains("%1\$d"))
        assertTrue(v(es, "intel_anomalies") == "CON MOTIVO" && v(en, "intel_anomalies") == "WITH REASON")
        assertTrue(v(es, "intel_connections") == "OBSERVACIONES")
    }
}
