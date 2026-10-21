package com.alexisgordr.icdetector.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 3.0 — Las etiquetas positivas dicen qué criterio cumplen y no sugieren legitimidad garantizada,
 * seguridad ni ausencia de ataques. Un mismo criterio lleva el mismo nombre en toda la app.
 */
class LabelClarityTest {
    private val app = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun strings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(app, "res/$dir/strings.xml"))
        val n = doc.getElementsByTagName("string")
        return (0 until n.length).associate { i -> n.item(i).attributes.getNamedItem("name").nodeValue to n.item(i).textContent }
    }
    private val es = strings("values-es")
    private val en = strings("values")

    @Test fun `learned handovers have one name and an explained criterion`() {
        val keys = listOf("filter_learned", "topology_metric_trusted", "topology_route_counts_format", "geometry_trusted_in_out",
            "route_trust_label", "route_detail_counts", "trust_desc_learning")
        keys.forEach { k ->
            assertTrue("$k ES", es.getValue(k).contains("aprendid", ignoreCase = true))
            assertTrue("$k EN", en.getValue(k).contains("learned", ignoreCase = true))
            assertFalse("$k ES", Regex("fiable|confianza", RegexOption.IGNORE_CASE).containsMatchIn(es.getValue(k)))
            assertFalse("$k EN", Regex("trust", RegexOption.IGNORE_CASE).containsMatchIn(en.getValue(k)))
        }
        assertEquals(es.getValue("filter_learned"), es.getValue("topology_metric_trusted"))
        assertEquals(en.getValue("filter_learned"), en.getValue("topology_metric_trusted"))
        assertTrue(es.getValue("topology_learned_note").contains("H16") && es.getValue("topology_learned_note").contains("No prueba que las celdas sean legítimas"))
        assertTrue(en.getValue("topology_learned_note").contains("H16") && en.getValue("topology_learned_note").contains("does not prove that the cells are legitimate"))
        val history = File(app, "java/com/alexisgordr/icdetector/ui/HistoryScreen.kt").readText()
        assertTrue(history.contains("stringResource(R.string.topology_learned_note)"))
    }

    @Test fun `positive states keep saying what they do not prove`() {
        assertTrue(es.getValue("trust_desc_established").contains("no prueba de autenticidad"))
        assertTrue(en.getValue("trust_desc_established").contains("not proof of authenticity"))
        assertTrue(es.getValue("trust_desc_new").contains("no se afirma legitimidad"))
        assertTrue(es.getValue("stable_site_simple_ready_note").contains("no una protección activa"))
        // Ninguna etiqueta afirma seguridad o legitimidad.
        listOf(es to listOf("es segura", "legítima", "sin ataques", "garantiza"), en to listOf("is safe", "is legitimate", "no attack", "guarantee"))
            .forEach { (m, words) -> m.forEach { (k, v) -> words.forEach { w ->
                if (v.contains(w, ignoreCase = true)) assertTrue("$k: «$w» sin negación", Regex("no |not |sin |does not|n't", RegexOption.IGNORE_CASE).containsMatchIn(v))
            } } }
    }
}
