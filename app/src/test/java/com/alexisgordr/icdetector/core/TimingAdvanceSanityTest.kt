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
    private val LTE = TimingAdvanceUnit.LTE_INDEX
    private val GSM = TimingAdvanceUnit.GSM_INDEX
    private val NR = TimingAdvanceUnit.NR_RAW


    private fun key(cid: Int) = "cell:UMTS:$cid"

    @Test fun `una sola celda a cero no basta para sospechar`() {
        val s = TimingAdvanceSanity()
        repeat(50) { s.observe(LTE, key(1), 0) }
        assertFalse(
            "puedes estar de verdad pegado a UNA antena; 50 lecturas de la misma celda no prueban nada",
            s.isStub(LTE)
        )
    }

    @Test fun `tres celdas distintas a cero delatan un campo sin rellenar`() {
        val s = TimingAdvanceSanity()
        s.observe(LTE, key(1), 0)
        s.observe(LTE, key(2), 0)
        assertFalse(s.isStub(LTE))
        s.observe(LTE, key(3), 0)
        assertTrue(
            "estar a <78 m de tres antenas distintas no ocurre: el campo no se rellena",
            s.isStub(LTE)
        )
        assertEquals(3, s.zeroOnlyCellCount(LTE))
    }

    @Test fun `un solo valor distinto de cero demuestra que el modem SI reporta`() {
        val s = TimingAdvanceSanity()
        s.observe(LTE, key(1), 0); s.observe(LTE, key(2), 0); s.observe(LTE, key(3), 0)
        assertTrue(s.isStub(LTE))

        s.observe(LTE, key(4), 7)   // una medida real
        assertTrue(s.hasSeenRealValue(LTE))
        assertFalse("demostrado que reporta, ya no se vuelve a sospechar", s.isStub(LTE))
    }

    @Test fun `tras demostrarse real, los ceros posteriores son medidas legitimas`() {
        val s = TimingAdvanceSanity()
        s.observe(LTE, key(1), 3)
        repeat(10) { i -> s.observe(LTE, key(i + 2), 0) }
        assertFalse("un cero real significa 'estoy encima de la antena' y debe respetarse", s.isStub(LTE))
        assertEquals(
            TimingAdvanceUnit.LTE_INDEX,
            s.effectiveUnit(TimingAdvanceUnit.LTE_INDEX, 0)
        )
    }

    @Test fun `un TA nulo no aporta evidencia en ninguna direccion`() {
        val s = TimingAdvanceSanity()
        repeat(20) { i -> s.observe(LTE, key(i), null) }
        assertFalse("null es el módem diciendo honestamente 'no hay dato'", s.isStub(LTE))
        assertFalse(s.hasSeenRealValue(LTE))
        assertEquals(0, s.zeroOnlyCellCount(LTE))
    }

    @Test fun `la unidad efectiva pasa a STUB_ZERO y deja de producir metros`() {
        val s = TimingAdvanceSanity()
        s.observe(LTE, key(1), 0); s.observe(LTE, key(2), 0); s.observe(LTE, key(3), 0)

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
        s.observe(LTE, key(1), 0); s.observe(LTE, key(2), 0); s.observe(LTE, key(3), 0)
        assertEquals(
            "solo el propio 0 se reinterpreta; un valor real se respeta siempre",
            TimingAdvanceUnit.LTE_INDEX,
            s.effectiveUnit(TimingAdvanceUnit.LTE_INDEX, 5)
        )
    }

    // ---------- 3.0 (#33): evidencia por tecnología / unidad ----------

    @Test fun `un LTE que reporta no impide detectar un GSM que siempre da cero`() {
        val s = TimingAdvanceSanity()
        s.observe(LTE, key(1), 7)                     // LTE reporta de verdad
        s.observe(GSM, key(11), 0); s.observe(GSM, key(12), 0); s.observe(GSM, key(13), 0)
        assertTrue("el camino GSM es un campo sin rellenar", s.isStub(GSM))
        assertFalse("LTE sigue demostrado", s.isStub(LTE))
        assertEquals(TimingAdvanceUnit.STUB_ZERO, s.effectiveUnit(GSM, 0))
        assertEquals(TimingAdvanceUnit.LTE_INDEX, s.effectiveUnit(LTE, 0))
    }

    @Test fun `el escenario inverso tampoco se contamina`() {
        val s = TimingAdvanceSanity()
        s.observe(GSM, key(1), 2)
        s.observe(LTE, key(11), 0); s.observe(LTE, key(12), 0); s.observe(LTE, key(13), 0)
        assertTrue(s.isStub(LTE))
        assertFalse(s.isStub(GSM))
        assertFalse(s.isStub(NR))
    }

    @Test fun `la evidencia se restaura por unidad tras un reinicio`() {
        val before = TimingAdvanceSanity()
        before.observe(LTE, key(1), 5)
        listOf(11, 12, 13).forEach { before.observe(GSM, key(it), 0) }
        val after = TimingAdvanceSanity()
        TimingAdvanceSanity.EVIDENCE_UNITS.forEach { unit ->
            after.restore(unit, before.hasSeenRealValue(unit), before.zeroOnlyCellKeys(unit))
        }
        assertTrue(after.isStub(GSM))
        assertTrue(after.hasSeenRealValue(LTE))
        assertFalse(after.isStub(LTE))
    }

    @Test fun `STUB_ZERO no es una unidad con evidencia propia`() {
        val s = TimingAdvanceSanity()
        assertFalse(s.observe(TimingAdvanceUnit.STUB_ZERO, key(1), 0))
        assertFalse(TimingAdvanceUnit.STUB_ZERO in TimingAdvanceSanity.EVIDENCE_UNITS)
    }

    @Test fun `un TA ausente y un cero aislado siguen igual que antes en cada unidad`() {
        val s = TimingAdvanceSanity()
        repeat(10) { s.observe(NR, key(it), null) }
        s.observe(GSM, key(1), 0)
        assertFalse(s.isStub(NR))
        assertFalse(s.isStub(GSM))
        assertEquals(TimingAdvanceUnit.GSM_INDEX, s.effectiveUnit(GSM, 0))
    }

    @Test fun `el servicio no lee la evidencia global antigua`() {
        val service = listOf(
            java.io.File("src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt"),
            java.io.File("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt")
        ).first { it.exists() }.readText()
        assertFalse(service.contains("\"ta_seen_real_value\""))
        assertFalse(service.contains("\"ta_zero_only_cells\""))
        assertTrue(service.contains("taSanity.observe(current.timingAdvanceUnit, taKey, current.timingAdvance)"))
    }

    // ── 3.0 — Qué cuenta como «distinto» ────────────────────────────────────────────────────

    private fun lte(cid: Long, tac: String = "31601", mcc: String = "214", mnc: String = "07") =
        com.alexisgordr.icdetector.models.CellData(isRegistered = true, networkType = "LTE", cellId = cid.toString(), mnc = mnc,
            tac = tac, dbm = -60, mcc = mcc, radioTech = com.alexisgordr.icdetector.models.RadioTech.LTE)

    @Test fun `three sectors or carriers of the same eNodeB count once`() {
        val enb = 12_345L shl 8
        val s = TimingAdvanceSanity()
        listOf(1L, 2L, 3L, 11L).forEach { s.observe(LTE, TimingAdvanceSanity.evidenceKey(lte(enb + it)), 0) }
        assertFalse("misma azotea: no son tres emplazamientos", s.isStub(LTE))
        assertEquals(1, s.zeroOnlyCellCount(LTE))
        // Tres eNodeB distintos sí.
        listOf(1L, 2L).forEach { s.observe(LTE, TimingAdvanceSanity.evidenceKey(lte(((12_345L + it) shl 8) + 1)), 0) }
        assertTrue(s.isStub(LTE))
    }

    @Test fun `an unreadable Cell ID or a TAC filled later do not add keys`() {
        assertEquals(null, TimingAdvanceSanity.evidenceKey(lte(1).copy(cellId = "N/A")))
        assertEquals(TimingAdvanceSanity.evidenceKey(lte(256, tac = "N/A")), TimingAdvanceSanity.evidenceKey(lte(256)))
        val umts = com.alexisgordr.icdetector.models.CellData(isRegistered = true, networkType = "3G", cellId = "777", mnc = "07",
            tac = "N/A", dbm = -60, mcc = "214", radioTech = com.alexisgordr.icdetector.models.RadioTech.UMTS)
        assertEquals("cell:UMTS:777", TimingAdvanceSanity.evidenceKey(umts))
        assertEquals(TimingAdvanceSanity.evidenceKey(umts), TimingAdvanceSanity.evidenceKey(umts.copy(tac = "1200")))
        // LTE sin red conocida: no se puede identificar el eNodeB, no se cuenta.
        assertEquals(null, TimingAdvanceSanity.evidenceKey(lte(256, mcc = "N/A")))
        // Una lectura sin clave no cuenta un cero, pero un TA real sí cierra la sospecha.
        val s = TimingAdvanceSanity()
        assertFalse(s.observe(LTE, null, 0))
        assertTrue(s.observe(LTE, null, 4))
        assertTrue(s.hasSeenRealValue(LTE))
    }

    @Test fun `legacy stored keys are converted so one site is not counted three times`() {
        val enb = (54_321L shl 8)
        val legacy = setOf("214-07-31601-${enb + 1}-LTE", "214-07-31601-${enb + 2}-LTE", "214-07-N/A-${enb + 3}-LTE",
            "214-07-31601-N/A-LTE", "basura", "214-07-1-9-RADIO_RARO")
        val s = TimingAdvanceSanity()
        s.restore(LTE, false, legacy)
        assertEquals(setOf("site:LTE:214-07-54321"), s.zeroOnlyCellKeys(LTE))
        assertFalse(s.isStub(LTE))
        assertEquals("cell:GSM:42", TimingAdvanceSanity.normalizeStoredKey("214-07-1200-42-GSM"))
        assertEquals("cell:GSM:42", TimingAdvanceSanity.normalizeStoredKey("cell:GSM:42"))
    }

    @Test fun `the service observes with the evidence key`() {
        val service = listOf(java.io.File("src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt"),
            java.io.File("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt")).first { it.exists() }.readText()
        assertTrue(service.contains("val taKey = com.alexisgordr.icdetector.core.TimingAdvanceSanity.evidenceKey(current)"))
        assertFalse(service.contains("val taKey = current.identityKey"))
    }
}
