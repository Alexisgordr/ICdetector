package com.alexisgordr.icdetector.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * v2.10.6 — Localización: la confirmación de borrado y los textos de History/Geometry
 * deben seguir el idioma de la interfaz. Lee los recursos y el código fuente directamente, así
 * que corre en la JVM sin Android.
 */
class StringResourcesTest {
    private fun module(path: String): File =
        listOf(File(path), File("app/$path")).first { it.exists() }

    private fun strings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(module("src/main/res/$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private val en = strings("values")
    private val es = strings("values-es")

    @Test fun `english and spanish declare the same strings`() {
        assertEquals(en.keys, es.keys)
    }

    @Test fun `delete confirmation word follows the interface language`() {
        assertEquals("DELETE", en.getValue("delete_confirm_word"))
        assertEquals("BORRAR", es.getValue("delete_confirm_word"))
        listOf("history_delete_prompt", "delete_type_prompt").forEach { key ->
            assertTrue("$key (en) must show the word", en.getValue(key).contains("%1\$s"))
            assertTrue("$key (es) must show the word", es.getValue(key).contains("%1\$s"))
            assertFalse("$key (en) must not ask for BORRAR", en.getValue(key).contains("BORRAR"))
        }
    }

    @Test fun `format placeholders match between languages`() {
        val placeholder = Regex("%\\d+\\$[sd]")
        en.forEach { (key, value) ->
            assertEquals(key, placeholder.findAll(value).map { it.value }.sorted().toList(),
                placeholder.findAll(es.getValue(key)).map { it.value }.sorted().toList())
        }
    }

    @Test fun `history and geometry screens do not hard-code the confirmation word`() {
        listOf("HistoryScreen.kt", "GeometryScreen.kt").forEach { name ->
            val source = module("src/main/java/com/alexisgordr/icdetector/ui/$name").readText()
            assertFalse("$name compares against a hard-coded word", source.contains("\"BORRAR\""))
        }
    }

    @Test fun `export privacy warnings exist in both languages`() {
        listOf("topology_export_privacy_warning", "geometry_export_privacy_warning",
            "forensic_privacy_warning").forEach { key ->
            assertTrue("$key (en)", en.getValue(key).length > 80)
            assertTrue("$key (es)", es.getValue(key).length > 80)
        }
    }

    @Test fun `spanish mobility and geometry labels do not mix english`() {
        val mixed = Regex("\\b(MOVING|edges|Good|Trusted)\\b")
        es.filterKeys { it.startsWith("mobility_") || it.startsWith("geometry_") || it.startsWith("intel_") }
            .forEach { (key, value) -> assertFalse("$key (es): $value", mixed.containsMatchIn(value)) }
    }

    @Test fun `route familiarity has a label in both languages`() {
        listOf("unknown", "observed", "known").forEach { state ->
            val key = "mobility_familiarity_$state"
            assertFalse("$key (en)", en.getValue(key).contains("_"))
            assertFalse("$key (es)", es.getValue(key).contains("_"))
        }
    }

    @Test fun `screens do not show raw enum names or hard-coded labels`() {
        val geometry = module("src/main/java/com/alexisgordr/icdetector/ui/GeometryScreen.kt").readText()
        listOf("?: \"UNKNOWN_ON_ROUTE\"", "familiarity?.name ?:", "GeometryLine(\"LocalCellTrust\"",
            "GeometryLine(\"Mobility\"", "GeometryLine(\"Good edges\"").forEach { literal ->
            assertFalse("GeometryScreen still contains $literal", geometry.contains(literal))
        }
        val history = module("src/main/java/com/alexisgordr/icdetector/ui/HistoryScreen.kt").readText()
        listOf("IntelCell(Modifier.weight(1f), \"VERIFIED\"", "IntelCell(Modifier.weight(1f), \"NOT FOUND\"")
            .forEach { literal -> assertFalse("HistoryScreen still contains $literal", history.contains(literal)) }
    }

    @Test fun `history screen shows state labels, not enum names`() {
        val history = module("src/main/java/com/alexisgordr/icdetector/ui/HistoryScreen.kt").readText()
        listOf("\${record.verified.name}", "\${latest.verified.name}", "\${route.lastStatus.name}",
            "Text(fc.state.name").forEach { literal ->
            assertFalse("HistoryScreen still displays $literal", history.contains(literal))
        }
        listOf("capturing", "post_capture", "ready", "interrupted").forEach { state ->
            val key = "forensic_state_$state"
            assertTrue("$key (en)", en.getValue(key).isNotBlank())
            assertTrue("$key (es)", es.getValue(key).isNotBlank())
        }
    }

    @Test fun `every requested permission is explained in both languages`() {
        listOf("permission_location", "permission_phone", "permission_notifications").forEach { prefix ->
            listOf("${prefix}_title", "${prefix}_body").forEach { key ->
                assertTrue("$key (en)", en.getValue(key).isNotBlank())
                assertTrue("$key (es)", es.getValue(key).isNotBlank())
            }
            assertTrue("${prefix}_body (en) must explain the use", en.getValue("${prefix}_body").length > 80)
        }
        val main = module("src/main/java/com/alexisgordr/icdetector/ui/MainScreen.kt").readText()
        assertTrue("MainScreen must show the rationale before the system dialog", main.contains("PermissionRationale("))
    }
}
