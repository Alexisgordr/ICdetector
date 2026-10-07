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

    // v2.10.7 — La alarma describe una anomalía de red, no un móvil vulnerado.
    @Test fun `confirmed alarm label does not claim the device is compromised`() {
        listOf(en, es).forEach { strings ->
            assertFalse(strings.containsKey("system_compromised"))
            val label = strings.getValue("network_anomaly_confirmed")
            assertFalse(label, Regex("COMPROMIS|COMPROMET", RegexOption.IGNORE_CASE).containsMatchIn(label))
        }
        assertEquals("NETWORK ANOMALY CONFIRMED", en.getValue("network_anomaly_confirmed"))
    }

    // v2.10.7 — El pitido debe poder silenciarse y el terminal no debe afirmar que ignora una
    // alerta que luego se confirma.
    @Test fun `alert tone respects silent mode and ping-pong log is neutral`() {
        val service = module("src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        assertFalse("tone must not use the alarm stream", service.contains("ToneGenerator(AudioManager.STREAM_ALARM"))
        assertTrue(service.contains("ToneGenerator(AudioManager.STREAM_NOTIFICATION"))
        assertFalse(service.contains("Ignorando alerta"))
    }

    // v2.10.8 — "%%" solo se convierte en "%" cuando el texto se formatea con argumentos. En un
    // texto sin argumentos se veía literalmente "98%%".
    @Test fun `plain strings do not show a doubled percent sign`() {
        val placeholder = Regex("%\\d+\\$")
        listOf(en, es).forEach { strings ->
            strings.filterValues { !placeholder.containsMatchIn(it) }.forEach { (key, value) ->
                assertFalse("$key shows %% literally", value.contains("%%"))
            }
        }
        assertTrue(en.getValue("local_trust_ceiling").endsWith("98%"))
        assertTrue(es.getValue("local_trust_ceiling").endsWith("98%"))
    }

    // v2.10.8 — Las notificaciones salían en castellano con la interfaz en inglés.
    @Test fun `notification texts exist in both languages and are not hard-coded`() {
        listOf(
            "notif_monitoring_title", "notif_legacy_network_title", "notif_open_settings",
            "channel_monitoring_name", "channel_alerts_description", "notif_latency_title",
            "collection_interrupted_format", "write_failure_notification", "audit_starting",
            "alarm_notification_title", "alarm_notification_body_format"
        ).forEach { key ->
            assertTrue("$key (en)", en.getValue(key).isNotBlank())
            assertTrue("$key (es)", es.getValue(key).isNotBlank())
        }
        assertEquals("Network anomaly confirmed", en.getValue("alarm_notification_title"))
        assertEquals("Anomalía de red confirmada", es.getValue("alarm_notification_title"))
        val service = "src/main/java/com/alexisgordr/icdetector/service"
        val sources = listOf(
            "$service/ServiceNotificationController.kt", "$service/MiniICService.kt",
            "$service/CollectionHealthController.kt", "src/main/java/com/alexisgordr/icdetector/ui/MainScreen.kt"
        ).associateWith { module(it).readText() }
        listOf(
            "\"miniIC Channel\"", "Monitoreo · GPS continuo", "red 2G/3G detectada", "\"ABRIR AJUSTES\"",
            "\"Avisos accionables", "\"⚠ Anomalía de Red\"", "\"⚠ ESCRITURA FALLIDA —", "\"Iniciando..."
        ).forEach { text ->
            sources.forEach { (file, source) -> assertFalse("$file hard-codes $text", source.contains(text)) }
        }
        val service2 = sources.getValue("$service/MiniICService.kt")
        assertTrue("service must use the app language", service2.contains("LocaleController.localizedContext"))
        // Cambiar el idioma con el servicio en marcha también cambia sus notificaciones.
        assertTrue(service2.contains("registerOnSharedPreferenceChangeListener(languageListener)"))
        assertTrue(service2.contains("unregisterOnSharedPreferenceChangeListener(languageListener)"))
        assertTrue(service2.contains("override fun getResources()"))
    }

    // v2.10.8 — Una alarma confirmada publica un aviso visible, una vez por episodio.
    @Test fun `confirmed alarm posts a notification once per episode`() {
        val alerts = module("src/main/java/com/alexisgordr/icdetector/service/SecurityAlertController.kt").readText()
        val episode = alerts.substringAfter("if (confirmed && persistedAlarmCellId != cell.cellId) {").substringBefore("}")
        assertTrue(episode.contains("persistConfirmedAlarm(cell)"))
        assertTrue(episode.contains("notifyConfirmedAlarm(cell)"))
        val notifications = module("src/main/java/com/alexisgordr/icdetector/service/ServiceNotificationController.kt").readText()
        assertTrue(notifications.contains("fun showConfirmedAlarm"))
        assertTrue(notifications.contains("ALERT_CHANNEL_ID = \"miniic_security_alerts\""))
        assertTrue(notifications.contains("CHANNEL_ID = \"miniic_channel\""))
    }

    // v2.10.8 — Potencia, huella y PCI muestran un estado de espera mientras la celda no es de
    // confianza, en vez de "EMPTY · 0 muestras".
    @Test fun `trust-gated baselines explain the waiting period`() {
        // Texto corto: tiene que caber en la misma línea donde antes salía "EMPTY · 0 muestras".
        assertEquals("NEEDS 2 DAYS", en.getValue("baseline_waiting"))
        assertEquals("NECESITA 2 DÍAS", es.getValue("baseline_waiting"))
        listOf(en, es).forEach { assertTrue(it.getValue("baseline_waiting").length <= 18) }
        assertFalse(en.containsKey("baseline_maturity_hint") || es.containsKey("baseline_maturity_hint"))
        val main = module("src/main/java/com/alexisgordr/icdetector/ui/MainScreen.kt").readText()
        assertEquals(3, Regex("trustGated = true").findAll(main).count())
    }

    // v2.10.8 — El cliente de WiGLE no se usaba: se retira para que el código coincida con la
    // documentación (solo OpenCellID y la prueba de latencia opcional salen a la red).
    @Test fun `dead wigle client is gone`() {
        assertFalse(File("src/main/java/com/alexisgordr/icdetector/network/WigleClient.kt").exists())
        assertFalse(File("app/src/main/java/com/alexisgordr/icdetector/network/WigleClient.kt").exists())
        val root = module("src/main/java")
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            assertFalse("${file.name} still calls api.wigle.net", file.readText().contains("api.wigle.net"))
        }
    }

    // v2.10.8 — Textos que ya no usaba ninguna pantalla.
    @Test fun `unused strings were removed`() {
        listOf("geometry_legend", "not_evaluated_explanation", "terminal_technical_event").forEach { key ->
            assertFalse(key, en.containsKey(key) || es.containsKey(key))
        }
    }
}
