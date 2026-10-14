package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.ServingIdentityChange.Identity
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0 (corte de campaña) — Historial de cambios de servidora que lee H10. */
class CellChangeHistoryTest {
    private val a = Identity("214", "07", "31601", "100", RadioTech.LTE)
    private val b = a.copy(cellId = "200")

    @Test fun `the first reading and every real change are stored with their time`() {
        val history = CellChangeHistory(windowMs = 10_000)
        assertTrue(history.observe(a, 1_000))
        assertFalse("misma identidad: no es un cambio", history.observe(a, 2_000))
        assertTrue(history.observe(b, 3_000))
        assertEquals(listOf(a.key to 1_000L, b.key to 3_000L), history.snapshot())
    }

    @Test fun `an unreadable Cell ID is not a change`() {
        val history = CellChangeHistory(windowMs = 10_000)
        history.observe(a, 1_000)
        assertFalse(history.observe(a.copy(cellId = "N/A"), 2_000))
        assertFalse("vuelve a la misma celda: tampoco", history.observe(a, 3_000))
        assertEquals(1, history.size)
    }

    @Test fun `changes older than the window are dropped when a new one arrives`() {
        val history = CellChangeHistory(windowMs = 10_000)
        history.observe(a, 0)
        history.observe(b, 5_000)
        history.observe(a, 10_000)
        assertEquals("a los 10 s justos sigue dentro", 3, history.size)
        history.observe(b, 15_001)
        assertEquals(listOf(a.key to 10_000L, b.key to 15_001L), history.snapshot())
    }

    @Test fun `a snapshot does not change with later readings`() {
        val history = CellChangeHistory(windowMs = 10_000)
        history.observe(a, 1_000)
        val snapshot = history.snapshot()
        history.observe(b, 2_000)
        assertEquals(1, snapshot.size)
        assertEquals(2, history.size)
    }

    @Test fun `clear forgets the changes and the previous identity`() {
        val history = CellChangeHistory(windowMs = 10_000)
        history.observe(a, 1_000)
        history.observe(b, 2_000)
        history.clear()
        assertEquals(0, history.size)
        assertTrue("sin identidad anterior, la primera lectura vuelve a contar", history.observe(b, 3_000))
    }
}
