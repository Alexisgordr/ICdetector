package com.alexisgordr.icdetector.ui

import com.alexisgordr.icdetector.core.profile.AbstainReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 3.0 — Las vistas distinguen dato ausente, regla no evaluada, referencia inmadura, incompatible o
 * caducada, y muestran la precisión guardada sin inventarla.
 */
class ProvenanceViewsTest {
    private val app = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun ui(name: String) = File(app, "java/com/alexisgordr/icdetector/ui/$name").readText()
    private fun strings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(app, "res/$dir/strings.xml"))
        val n = doc.getElementsByTagName("string")
        return (0 until n.length).associate { i -> n.item(i).attributes.getNamedItem("name").nodeValue to n.item(i).textContent }
    }

    @Test fun `every abstain reason has its own words, and the code stays in the detail`() {
        val panel = ui("LocalProfileHistoryPanel.kt")
        val mapping = panel.substringAfter("internal fun abstainReasonLabel(").substringBefore("\n}\n")
        AbstainReason.entries.forEach { assertTrue(it.name, mapping.contains("AbstainReason.${it.name} ->")) }
        val keys = Regex("""R\.string\.(profile_abstain_\w+)""").findAll(mapping).map { it.groupValues[1] }.toList()
        assertEquals("un texto distinto por motivo", AbstainReason.entries.size, keys.toSet().size)
        val es = strings("values-es"); val en = strings("values")
        keys.forEach { k -> assertTrue(k, es.containsKey(k) && en.containsKey(k)) }
        assertEquals("referencia aún inmadura", es.getValue("profile_abstain_immature"))
        assertEquals("referencia caducada (sin datos recientes)", es.getValue("profile_abstain_stale"))
        assertTrue(es.getValue("profile_abstain_incompatible_device").startsWith("referencia de otro"))
        assertFalse("el resumen no muestra el código", panel.contains("abstainLabel(it)"))
        assertTrue(panel.contains("stringResource(R.string.profile_history_reason_code, it.name)"))
        assertTrue(ui("LocalProfileSection.kt").contains("stringResource(abstainReasonLabel(it))"))
    }

    @Test fun `antenna detail shows stored accuracy, skipped rules and origin, or says they were not stored`() {
        val history = ui("HistoryScreen.kt")
        assertTrue(history.contains("RowProvenance.accuracyMeters(record.gpsAccuracyM)"))
        assertTrue(history.contains("stringResource(R.string.antenna_gps_accuracy_unknown)"))
        assertTrue(history.contains("RowProvenance.notEvaluated(latest.notEvaluatedHeuristics)"))
        assertTrue(history.contains("latest.locationMode ?: notStored, latest.appVersion ?: notStored"))
        val es = strings("values-es"); val en = strings("values")
        assertTrue(es.getValue("antenna_not_evaluated_unknown").contains("sin dato"))
        assertTrue(en.getValue("antenna_not_evaluated_unknown").contains("not stored"))
        assertFalse("sin dato no es «todas evaluadas»", es.getValue("antenna_not_evaluated_unknown").contains("ninguna"))
        listOf("antenna_gps_accuracy", "antenna_not_evaluated_rules", "antenna_row_origin", "profile_history_reason_code").forEach { k ->
            val args = Regex("""%\d\$[sd]""")
            assertEquals(k, args.findAll(en.getValue(k)).map { it.value }.toList(), args.findAll(es.getValue(k)).map { it.value }.toList())
        }
    }
}
