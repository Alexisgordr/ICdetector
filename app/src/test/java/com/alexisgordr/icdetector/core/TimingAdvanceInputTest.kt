package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — Saneado del TA: rangos del getter, conversión acotada y extracción de texto estricta. */
class TimingAdvanceInputTest {
    private val odd = listOf(Int.MAX_VALUE, Int.MIN_VALUE, -1, -100, 1_000_000_000)

    @Test fun `LTE getter accepts 0 to 1282 and nothing else`() {
        assertEquals(0, TimingAdvanceInput.lte(0))
        assertEquals(1282, TimingAdvanceInput.lte(1282))
        assertNull(TimingAdvanceInput.lte(1283))
        assertNull(TimingAdvanceInput.lte(null))
        odd.forEach { assertNull("$it", TimingAdvanceInput.lte(it)) }
    }

    @Test fun `GSM getter accepts 0 to 219 and nothing else`() {
        assertEquals(0, TimingAdvanceInput.gsm(0))
        assertEquals(219, TimingAdvanceInput.gsm(219))
        assertNull(TimingAdvanceInput.gsm(220))
        odd.forEach { assertNull("$it", TimingAdvanceInput.gsm(it)) }
    }

    @Test fun `NR public getter keeps 0 to 1282 without implying a distance`() {
        assertEquals(0, TimingAdvanceInput.nr(0))
        assertEquals(1282, TimingAdvanceInput.nr(1282))
        assertNull(TimingAdvanceInput.nr(1283))
        odd.forEach { assertNull("$it", TimingAdvanceInput.nr(it)) }
        assertNull(TimingAdvanceUnit.NR_RAW.toMeters(5))
    }

    @Test fun `toMeters applies the same limits, keeps valid zeros and cannot overflow`() {
        assertEquals(0, TimingAdvanceUnit.LTE_INDEX.toMeters(0))
        assertEquals(99_996, TimingAdvanceUnit.LTE_INDEX.toMeters(1282))
        assertNull(TimingAdvanceUnit.LTE_INDEX.toMeters(1283))
        assertEquals(0, TimingAdvanceUnit.GSM_INDEX.toMeters(0))
        assertEquals(121_326, TimingAdvanceUnit.GSM_INDEX.toMeters(219))
        assertNull(TimingAdvanceUnit.GSM_INDEX.toMeters(220))
        // Antes: Int.MAX_VALUE / 1e9 · 78 desbordaba a un número cualquiera (incluso negativo).
        odd.forEach {
            assertNull("$it", TimingAdvanceUnit.LTE_INDEX.toMeters(it))
            assertNull("$it", TimingAdvanceUnit.GSM_INDEX.toMeters(it))
        }
        listOf(TimingAdvanceUnit.NR_RAW, TimingAdvanceUnit.UNKNOWN, TimingAdvanceUnit.STUB_ZERO).forEach { u ->
            listOf(0, 1, 1282).forEach { assertNull("$u $it", u.toMeters(it)) }
        }
    }

    @Test fun `text extraction needs a delimited field name and a complete integer`() {
        assertEquals(7, TimingAdvanceInput.scrapedNr("CellSignalStrengthNr: ssRsrp=-90 ta=7 csiRsrp=-95"))
        assertEquals(12, TimingAdvanceInput.scrapedNr("{timingAdvance=12, x=1}"))
        assertEquals(0, TimingAdvanceInput.scrapedNr("ta=0"))
        listOf(
            "data=0", "delta=0", "mData=5 delta=3", "beta=4", "x_ta=3", "ta2=5",
            "ta=12.5", "ta=-1", "ta=12abc", "ta=", "ta= 3",
            "ta=99999999999999999999", "ta=2147483647", "ta=1283", "timingAdvanceMicros=9"
        ).forEach { assertNull(it, TimingAdvanceInput.scrapedNr(it)) }
        assertNull(TimingAdvanceInput.scrapedNr(null))
        // Una coincidencia inválida no tapa una válida posterior.
        assertEquals(3, TimingAdvanceInput.scrapedNr("data=0 ta=12.5 ta=3"))
    }

    @Test fun `the parser no longer scrapes LTE text and reads NR through the public API`() {
        val parser = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .walkTopDown().first { it.name == "CellParser.kt" }.readText()
        val lte = parser.substringAfter("is CellInfoLte -> {").substringBefore("is CellInfoNr -> {")
        assertFalse(lte.contains("cellSignalStrength.toString") || lte.contains("Regex("))
        assertTrue(lte.contains("TimingAdvanceInput.lte(info.cellSignalStrength.timingAdvance)"))
        val nr = parser.substringAfter("is CellInfoNr -> {").substringBefore("is CellInfoWcdma -> {")
        assertFalse(nr.contains("getMethod("))
        assertTrue(nr.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE"))
        assertTrue(nr.contains("TimingAdvanceInput.nr(strength.timingAdvanceMicros)"))
        assertTrue(nr.contains("TimingAdvanceInput.scrapedNr(info.toString())"))
        assertTrue(parser.contains("TimingAdvanceInput.gsm(info.cellSignalStrength.timingAdvance)"))
    }
}
