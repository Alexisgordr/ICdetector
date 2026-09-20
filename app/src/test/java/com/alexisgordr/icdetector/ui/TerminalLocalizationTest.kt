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

    @Test
    fun `terminal translates established local identity change`() {
        val source = "Cambio en identidad celular consolidada (PCI+HANDOVER)"
        val result = localizeTerminalLine(source)

        assertTrue(result.contains("Established local cell identity changed"))
        assertFalse(result.contains("Cambio en identidad"))
    }
}
