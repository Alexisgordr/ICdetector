package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un módem que no implementa el Timing Advance suele devolver 0 en lugar de declarar
 * "no disponible". Estos tests fijan la regla que distingue ese cero vacío de un cero real.
 */
class TimingAdvanceSanityTest {

    private fun key(cid: Int) = "214-07-31601-$cid"

    @Test fun `una sola celda a cero no basta para sospechar`() {
        val s = TimingAdvanceSanity()
        repeat(50) { s.observe(key(1), 0) }
        assertFalse(
            "puedes estar de verdad pegado a UNA antena; 50 lecturas de la misma celda no prueban nada",
            s.isStub
        )
    }

    @Test fun `tres celdas distintas a cero delatan un campo sin rellenar`() {
        val s = TimingAdvanceSanity()
        s.observe(key(1), 0)
        s.observe(key(2), 0)
        assertFalse(s.isStub)
        s.observe(key(3), 0)
        assertTrue(
            "estar a <78 m de tres antenas distintas no ocurre: el campo no se rellena",
            s.isStub
        )
        assertEquals(3, s.zeroOnlyCellCount)
    }

    @Test fun `un solo valor distinto de cero demuestra que el modem SI reporta`() {
        val s = TimingAdvanceSanity()
        s.observe(key(1), 0); s.observe(key(2), 0); s.observe(key(3), 0)
        assertTrue(s.isStub)

        s.observe(key(4), 7)   // una medida real
        assertTrue(s.hasSeenRealValue)
        assertFalse("demostrado que reporta, ya no se vuelve a sospechar", s.isStub)
    }

    @Test fun `tras demostrarse real, los ceros posteriores son medidas legitimas`() {
        val s = TimingAdvanceSanity()
        s.observe(key(1), 3)
        repeat(10) { i -> s.observe(key(i + 2), 0) }
        assertFalse("un cero real significa 'estoy encima de la antena' y debe respetarse", s.isStub)
        assertEquals(
            TimingAdvanceUnit.LTE_INDEX,
            s.effectiveUnit(TimingAdvanceUnit.LTE_INDEX, 0)
        )
    }

    @Test fun `un TA nulo no aporta evidencia en ninguna direccion`() {
        val s = TimingAdvanceSanity()
        repeat(20) { i -> s.observe(key(i), null) }
        assertFalse("null es el módem diciendo honestamente 'no hay dato'", s.isStub)
        assertFalse(s.hasSeenRealValue)
        assertEquals(0, s.zeroOnlyCellCount)
    }

    @Test fun `la unidad efectiva pasa a STUB_ZERO y deja de producir metros`() {
        val s = TimingAdvanceSanity()
        s.observe(key(1), 0); s.observe(key(2), 0); s.observe(key(3), 0)

        val unit = s.effectiveUnit(TimingAdvanceUnit.LTE_INDEX, 0)
        assertEquals(TimingAdvanceUnit.STUB_ZERO, unit)
        assertEquals(
            "el camino de siempre: sin unidad convertible, no hay geometría",
            null, unit.toMeters(0)
        )
        assertFalse(unit.isUsableForGeometry)
    }

    @Test fun `un TA no nulo distinto de cero nunca se marca como stub`() {
        val s = TimingAdvanceSanity()
        s.observe(key(1), 0); s.observe(key(2), 0); s.observe(key(3), 0)
        assertEquals(
            "solo el propio 0 se reinterpreta; un valor real se respeta siempre",
            TimingAdvanceUnit.LTE_INDEX,
            s.effectiveUnit(TimingAdvanceUnit.LTE_INDEX, 5)
        )
    }
}
