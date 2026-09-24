package com.alexisgordr.icdetector.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLocalizationTest {
    @Test
    fun `terminal keeps technical values but removes Spanish audit text`() {
        val source = "[AUDIT] Celda 123 — índice heurístico: 25% (11/16 reglas evaluadas; 5 sin datos)"
        val result = localizeTerminalLine(source)

        assertTrue(result.contains("123"))
        assertTrue(result.contains("heuristic score"))
        assertTrue(result.contains("rules evaluated"))
        assertTrue(result.contains("unavailable"))
        assertFalse(result.contains("Celda"))
    }

    @Test
    fun `terminal translates verification failure without hiding raw response`() {
        val source = "[API] OpenCellID: límite diario de consultas agotado. NO significa que la antena no exista; se reintentará. Respuesta: 429"
        val result = localizeTerminalLine(source)

        assertTrue(result.contains("daily query limit exhausted", ignoreCase = true))
        assertTrue(result.contains("Will retry", ignoreCase = true))
        assertTrue(result.contains("429"))
        assertFalse(result.contains("Respuesta"))
    }

    @Test
    fun `terminal translates multi-signal episode reason`() {
        val source = "Episodio multiseñal (RF_DOMINANCE+MOBILITY) | familias independientes"
        val result = localizeTerminalLine(source)

        assertTrue(result.contains("Multi-signal episode"))
        assertTrue(result.contains("independent families"))
        assertFalse(result.contains("Episodio"))
    }

    // v2.8.0 — Los mensajes nuevos del mantenimiento forense y de la salud de la escritura.
    @Test
    fun `terminal translates whole-case forensic pruning`() {
        val source = "Poda forense: 2 caso(s) cerrado(s) eliminados enteros (1400 muestras) " +
            "para respetar el tope de 20000. Restantes: 19600."
        val result = localizeTerminalLine(source)

        assertTrue(result.contains("Forensic pruning"))
        assertTrue(result.contains("closed case(s) deleted in full"))
        assertTrue(result.contains("1400"))
        assertTrue(result.contains("19600"))
        assertFalse(result.contains("Poda"))
        assertFalse(result.contains("muestras"))
    }

    @Test
    fun `terminal translates the forensic cap notice without trimming a live capture`() {
        val source = "⚠️ Tope forense superado (25000 muestras) con solo casos abiertos. " +
            "No se recortan: una captura en curso no se mutila."
        val result = localizeTerminalLine(source)

        assertTrue(result.contains("Forensic cap exceeded"))
        assertTrue(result.contains("only open cases"))
        assertTrue(result.contains("25000"))
        assertFalse(result.contains("captura"))
    }

    @Test
    fun `terminal translates degraded and restored forensic capture`() {
        val degraded = localizeTerminalLine("⚠ Captura forense degradada — las muestras no se están guardando.")
        assertTrue(degraded.contains("Forensic capture degraded"))
        assertFalse(degraded.contains("Captura"))

        val restored = localizeTerminalLine("Captura forense restablecida.")
        assertTrue(restored.contains("Forensic capture restored"))
        assertFalse(restored.contains("Captura"))
    }

    @Test
    fun `terminal translates established local identity change`() {
        val source = "Cambio en identidad celular consolidada (PCI+HANDOVER)"
        val result = localizeTerminalLine(source)

        assertTrue(result.contains("Established local cell identity changed"))
        assertFalse(result.contains("Cambio en identidad"))
    }

    @Test
    fun `terminal translates v2_10_4 radio and service lines`() {
        val service = localizeTerminalLine(
            "[SERVICIO] Estado de servicio: EN SERVICIO → SOLO EMERGENCIAS · red=21407 · SIM=21407 · datos=LTE registrado · roaming=no"
        )
        assertTrue(service.contains("[SERVICE] Service state: IN SERVICE → EMERGENCY ONLY"))
        assertTrue(service.contains("network=21407"))
        assertTrue(service.contains("data=LTE registered"))
        assertFalse(service.contains("SERVICIO"))

        val radio = localizeTerminalLine(
            "[RADIO] Servidora LTE 1301/48 · estado de conexión=PRIMARY_SERVING · entradas registradas=2 · sin portadoras secundarias"
        )
        assertTrue(radio.contains("Serving LTE 1301/48"))
        assertTrue(radio.contains("connection state=PRIMARY_SERVING"))
        assertTrue(radio.contains("no secondary carriers"))
    }
}
