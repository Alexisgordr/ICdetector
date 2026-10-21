package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un módem que no implementa el Timing Advance suele devolver 0 en lugar de declarar
 * "no disponible". Estos tests fijan la regla que distingue ese cero vacío de un cero real.
 * 3.1.0 (#43): ventana de 200 observaciones por unidad en lugar del latch de un sentido.
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

    @Test fun `valores distintos de cero en dos celdas demuestran que el modem SI reporta`() {
        val s = TimingAdvanceSanity()
        s.observe(LTE, key(1), 0); s.observe(LTE, key(2), 0); s.observe(LTE, key(3), 0)
        assertTrue(s.isStub(LTE))

        s.observe(LTE, key(4), 7)   // una medida real, de una sola celda: no basta
        assertTrue(s.isStub(LTE))
        s.observe(LTE, key(5), 3)   // otra celda: 2 de 5 (40 %) en dos celdas
        assertEquals(TimingAdvanceSanity.Verdict.REAL, s.verdict(LTE))
        assertFalse(s.isStub(LTE))
    }

    @Test fun `tras demostrarse real, los ceros posteriores son medidas legitimas`() {
        val s = TimingAdvanceSanity()
        s.observe(LTE, key(1), 3); s.observe(LTE, key(2), 4)
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
        assertEquals(TimingAdvanceSanity.Verdict.UNKNOWN, s.verdict(LTE))
        assertEquals(0, s.windowSize(LTE))
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
        s.observe(LTE, key(1), 7); s.observe(LTE, key(2), 5)   // LTE reporta de verdad
        s.observe(GSM, key(11), 0); s.observe(GSM, key(12), 0); s.observe(GSM, key(13), 0)
        assertTrue("el camino GSM es un campo sin rellenar", s.isStub(GSM))
        assertFalse("LTE sigue demostrado", s.isStub(LTE))
        assertEquals(TimingAdvanceUnit.STUB_ZERO, s.effectiveUnit(GSM, 0))
        assertEquals(TimingAdvanceUnit.LTE_INDEX, s.effectiveUnit(LTE, 0))
    }

    @Test fun `el escenario inverso tampoco se contamina`() {
        val s = TimingAdvanceSanity()
        s.observe(GSM, key(1), 2); s.observe(GSM, key(2), 1)
        s.observe(LTE, key(11), 0); s.observe(LTE, key(12), 0); s.observe(LTE, key(13), 0)
        assertTrue(s.isStub(LTE))
        assertFalse(s.isStub(GSM))
        assertFalse(s.isStub(NR))
    }

    @Test fun `la evidencia se restaura por unidad tras un reinicio`() {
        val before = TimingAdvanceSanity()
        before.observe(LTE, key(1), 5); before.observe(LTE, key(2), 6)
        listOf(11, 12, 13).forEach { before.observe(GSM, key(it), 0) }
        val after = TimingAdvanceSanity()
        TimingAdvanceSanity.EVIDENCE_UNITS.forEach { unit -> after.restore(unit, before.summary(unit)) }
        assertTrue(after.isStub(GSM))
        assertEquals(TimingAdvanceSanity.Verdict.REAL, after.verdict(LTE))
        assertFalse(after.isStub(LTE))
        TimingAdvanceSanity.EVIDENCE_UNITS.forEach { assertEquals(before.summary(it), after.summary(it)) }
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
        // Una lectura sin clave cuenta en la proporción, pero no como celda distinta.
        val s = TimingAdvanceSanity()
        repeat(5) { s.observe(LTE, null, 0) }
        assertEquals(0, s.zeroOnlyCellCount(LTE))
        s.observe(LTE, null, 4); s.observe(LTE, null, 6)
        assertEquals("valores no cero sin celda: no hay dos celdas", TimingAdvanceSanity.Verdict.UNKNOWN, s.verdict(LTE))
        assertEquals(7, s.windowSize(LTE))
    }

    @Test fun `legacy stored keys are converted so one site is not counted three times`() {
        val enb = (54_321L shl 8)
        val legacy = setOf("214-07-31601-${enb + 1}-LTE", "214-07-31601-${enb + 2}-LTE", "214-07-N/A-${enb + 3}-LTE",
            "214-07-31601-N/A-LTE", "basura", "214-07-1-9-RADIO_RARO")
        val s = TimingAdvanceSanity()
        s.restoreLegacy(LTE, legacy)
        assertEquals(1, s.zeroOnlyCellCount(LTE))
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

    // ── 3.1.0 (#43) — Ventana en lugar del latch ───────────────────────────────────────────

    private fun site(n: Int) = "site:LTE:214-07-${1000 + n}"

    /** (a) Un flujo constante a 0 con un valor espurio sigue siendo STUB_ZERO. */
    @Test fun `a constant zero stream with one spurious value stays STUB_ZERO`() {
        val s = TimingAdvanceSanity()
        repeat(150) { i -> s.observe(LTE, site(i % 4), 0) }
        assertTrue(s.isStub(LTE))
        s.observe(LTE, site(9), 37)                   // un valor espurio (el latch lo fijaba REAL para siempre)
        repeat(300) { i -> s.observe(LTE, site(i % 4), 0) }
        assertTrue(s.isStub(LTE))
        assertEquals(TimingAdvanceUnit.STUB_ZERO, s.effectiveUnit(LTE, 0))
        // Ni siquiera justo después del valor espurio: 1 de 200 y de una sola celda no decide.
        val t = TimingAdvanceSanity()
        repeat(199) { i -> t.observe(LTE, site(i % 4), 0) }
        t.observe(LTE, site(9), 37)
        assertTrue(t.isStub(LTE))
    }

    /** (b) Un flujo real mixto pasa a LTE_INDEX y se mantiene, también en un tramo largo a 0. */
    @Test fun `a mixed real stream becomes LTE_INDEX and stays`() {
        val s = TimingAdvanceSanity()
        val values = listOf(0, 3, 0, 5, 7, 0, 2, 0, 0, 4)
        repeat(300) { i -> s.observe(LTE, site(i / 30), values[i % values.size]) }
        assertEquals(TimingAdvanceSanity.Verdict.REAL, s.verdict(LTE))
        assertEquals(TimingAdvanceUnit.LTE_INDEX, s.effectiveUnit(LTE, 0))
        // En casa, pegado a una antena: 500 ceros de una sola celda no son tres emplazamientos.
        repeat(500) { s.observe(LTE, site(42), 0) }
        assertEquals(TimingAdvanceUnit.LTE_INDEX, s.effectiveUnit(LTE, 0))
    }

    /** (c) Patrón de campo: 1000 filas a TA=0 recorriendo varios eNodeB, con un reinicio en medio. */
    @Test fun `the field pattern of 1000 rows at zero stays STUB_ZERO`() {
        val blocks = listOf(120, 15, 240, 60, 8, 300, 45, 12, 150, 50)   // filas seguidas por eNodeB
        var s = TimingAdvanceSanity()
        val units = ArrayList<TimingAdvanceUnit>()
        var row = 0
        blocks.forEachIndexed { b, length ->
            repeat(length) {
                s.observe(LTE, site(b % 6), 0)
                units += s.effectiveUnit(LTE, 0)
                if (++row == 500) s = TimingAdvanceSanity().also { it.restore(LTE, s.summary(LTE)) }   // reinicio
            }
        }
        assertEquals(1000, units.size)
        val firstStub = units.indexOf(TimingAdvanceUnit.STUB_ZERO)
        assertEquals("en la primera fila del tercer eNodeB", 120 + 15, firstStub)
        assertTrue(units.drop(firstStub).all { it == TimingAdvanceUnit.STUB_ZERO })
    }

    /** (d) STUB→REAL→STUB cuando los datos lo justifican (p. ej. una actualización del módem). */
    @Test fun `stub to real to stub when the data justify it`() {
        val s = TimingAdvanceSanity()
        repeat(60) { i -> s.observe(LTE, site(i % 3), 0) }
        assertTrue(s.isStub(LTE))
        // El módem empieza a medir: valores reales en varias celdas.
        repeat(41) { i -> s.observe(LTE, site(i % 5), if (i % 3 == 0) 0 else 2 + i % 6) }   // la última, real
        assertEquals(TimingAdvanceSanity.Verdict.REAL, s.verdict(LTE))
        assertEquals(TimingAdvanceUnit.LTE_INDEX, s.effectiveUnit(LTE, 0))
        // Vuelve a dar 0 siempre: cuando la ventana entera es 0 en tres o más celdas, STUB otra vez.
        repeat(199) { i -> s.observe(LTE, site(i % 3), 0) }
        assertEquals("aún queda un valor real en la ventana", TimingAdvanceSanity.Verdict.REAL, s.verdict(LTE))
        repeat(TimingAdvanceSanity.WINDOW) { i -> s.observe(LTE, site(i % 3), 0) }
        assertTrue(s.isStub(LTE))
    }

    @Test fun `a legacy latch does not force REAL and the zero cells are migrated`() {
        val s = TimingAdvanceSanity()
        // 3.0 guardaba ta_seen_real_LTE_INDEX=true y vaciaba las celdas: no hay nada que migrar.
        s.restoreLegacy(LTE, emptySet())
        assertEquals(TimingAdvanceSanity.Verdict.UNKNOWN, s.verdict(LTE))
        listOf(1, 2, 3).forEach { s.observe(LTE, site(it), 0) }
        assertTrue("con el latch antiguo habría seguido REAL", s.isStub(LTE))
        // Sin latch, las celdas a cero de 3.0 entran en la ventana.
        val t = TimingAdvanceSanity()
        t.restoreLegacy(GSM, setOf("cell:GSM:1", "cell:GSM:2", "cell:GSM:3"))
        assertTrue(t.isStub(GSM))
    }

    @Test fun `the summary is compact, round-trips and a broken one is ignored`() {
        val s = TimingAdvanceSanity()
        repeat(150) { s.observe(LTE, site(1), 0) }
        repeat(50) { s.observe(LTE, null, 3) }
        assertEquals("v2|UNKNOWN|150,0,site:LTE:214-07-1001;50,1,", s.summary(LTE))
        val r = TimingAdvanceSanity()
        r.restore(LTE, s.summary(LTE))
        assertEquals(200, r.windowSize(LTE))
        assertEquals(s.summary(LTE), r.summary(LTE))
        listOf("", "v1|STUB|", "v2|NADA|", "v2|STUB|x,0,a", "v2|STUB|5,2,a").forEach {
            val b = TimingAdvanceSanity()
            assertFalse(it, b.restore(LTE, it))
            assertEquals(it, TimingAdvanceSanity.Verdict.UNKNOWN, b.verdict(LTE))
        }
        // Indeciso: se conserva el veredicto guardado.
        val k = TimingAdvanceSanity()
        k.restore(LTE, "v2|STUB|199,0,site:LTE:214-07-1001;1,1,site:LTE:214-07-1002")
        assertTrue(k.isStub(LTE))
    }

    @Test fun `it asks to be saved on a new verdict and every 30 observations, not every cycle`() {
        val s = TimingAdvanceSanity()
        val saves = (1..90).count { i -> s.observe(LTE, site(1), 0) }
        assertEquals(3, saves)
        val t = TimingAdvanceSanity()
        t.observe(LTE, site(1), 0); t.observe(LTE, site(2), 0)
        assertTrue("veredicto nuevo", t.observe(LTE, site(3), 0))
    }

    @Test fun `the service persists the window and migrates the 3_0 keys`() {
        val service = listOf(java.io.File("src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt"),
            java.io.File("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt")).first { it.exists() }.readText()
        assertTrue(service.contains("editor.putString(KEY_TA_WINDOW_PREFIX + unit.name, taSanity.summary(unit))"))
        assertTrue(service.contains("taSanity.restoreLegacy(unit, cells)"))
        assertTrue(service.contains("legacy.remove(KEY_TA_SEEN_REAL_PREFIX + unit.name).remove(KEY_TA_ZERO_CELLS_PREFIX + unit.name)"))
        assertFalse("el latch antiguo no se lee", service.contains("p.getBoolean(KEY_TA_SEEN_REAL_PREFIX"))
    }
}
