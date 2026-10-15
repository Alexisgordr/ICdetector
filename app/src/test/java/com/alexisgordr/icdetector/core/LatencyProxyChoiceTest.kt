package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.LatencyProxyChoice.Action
import com.alexisgordr.icdetector.core.LatencyProxyChoice.Form
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (V2) — Encender la latencia con Tor seleccionado pide una elección explícita; cancelar no
 * cambia nada; sin Tor no se pregunta; nada se aplica hasta guardar. Y los textos de privacidad
 * dicen qué se envía, qué no oculta Tor y que no hay salida directa de reserva, en EN y ES.
 */
class LatencyProxyChoiceTest {
    private val withTor = Form(latencyEnabled = false, proxyEnabled = true)
    private val withoutTor = Form(latencyEnabled = false, proxyEnabled = false)

    @Test fun `with Tor selected enabling latency asks first`() {
        assertEquals(Action.ASK, LatencyProxyChoice.onLatencyToggle(withTor, requested = true, latencyBlocked = false))
        assertEquals(Form(latencyEnabled = true, proxyEnabled = false), LatencyProxyChoice.apply(withTor, true))
    }

    @Test fun `cancel keeps the previous configuration`() {
        assertEquals(withTor, LatencyProxyChoice.cancel(withTor))
    }

    @Test fun `without Tor there is no dialog`() {
        assertEquals(Action.APPLY, LatencyProxyChoice.onLatencyToggle(withoutTor, true, false))
        assertEquals(Form(true, false), LatencyProxyChoice.apply(withoutTor, true))
    }

    @Test fun `turning latency off is always allowed and never touches the proxy`() {
        val on = Form(latencyEnabled = true, proxyEnabled = false)
        assertEquals(Action.APPLY, LatencyProxyChoice.onLatencyToggle(on, false, latencyBlocked = true))
        assertEquals(Form(false, false), LatencyProxyChoice.apply(on, false))
    }

    @Test fun `Wi-Fi or VPN still block turning it on, before any question`() {
        assertEquals(Action.IGNORE, LatencyProxyChoice.onLatencyToggle(withTor, true, latencyBlocked = true))
    }

    private fun file(p: String) = listOf("app/$p", p).map(::File).first { it.exists() }.readText()

    @Test fun `the screen asks, cancels without changes and still applies only on save`() {
        val ui = file("src/main/java/com/alexisgordr/icdetector/ui/SettingsScreen.kt")
        val toggle = ui.substringAfter("checked = latencyDetectionEnabled,").substringBefore("colors = SwitchDefaults")
        assertTrue(toggle.contains("LatencyProxyChoice.Action.ASK -> askLatencyOverTor = true"))
        assertFalse("ya no apaga el proxy en silencio", toggle.contains("proxyEnabled = false"))
        val dialog = ui.substringAfter("if (askLatencyOverTor) {").substringBefore("HorizontalDivider")
        val cancel = dialog.substringAfter("dismissButton")
        assertFalse(cancel.contains("proxyEnabled ="))
        assertFalse(cancel.contains("latencyDetectionEnabled ="))
        assertFalse(dialog.substringAfter("onDismissRequest").substringBefore("title").contains("proxyEnabled"))
        // La ruta solo cambia al guardar, como antes.
        assertEquals(1, Regex("updateVerificationSettings\\(").findAll(ui).count())
        assertFalse(dialog.contains("updateVerificationSettings") || dialog.contains("putBoolean"))
    }

    @Test fun `privacy texts exist in English and Spanish and say what matters`() {
        val en = file("src/main/res/values/strings.xml")
        val es = file("src/main/res/values-es/strings.xml")
        fun value(xml: String, key: String) = Regex("<string name=\"$key\">(.*?)</string>").find(xml)!!.groupValues[1]
        listOf("opencellid_privacy", "latency_tor_dialog_title", "latency_tor_dialog_body", "latency_tor_dialog_confirm", "latency_tor_dialog_cancel")
            .forEach { assertTrue(it, value(en, it).isNotBlank() && value(es, it).isNotBlank()) }
        assertTrue(value(es, "latency_tor_dialog_body").startsWith(
            "Activar latencia desactivará el proxy de OpenCellID; las consultas podrán salir directamente por tu conexión."))
        listOf("MCC", "MNC", "Cell ID", "API key").forEach {
            assertTrue(it, value(en, "opencellid_privacy").contains(it)); assertTrue(it, value(es, "opencellid_privacy").contains(it))
        }
        assertTrue(value(en, "opencellid_privacy").contains("link your queries"))
        assertTrue(value(es, "opencellid_privacy").contains("enlazar tus consultas"))
        assertTrue(value(en, "opencellid_privacy").contains("never fall back to a direct connection"))
        assertTrue(value(es, "opencellid_privacy").contains("nunca salen por conexión directa"))
    }
}
