package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 3.0 (#21) — El TA solo se copia de la misma celda duplicada, nunca de otro emisor. */
class TimingAdvanceBorrowingTest {
    private val serving = CellData(
        isRegistered = true, networkType = "4G LTE", cellId = "100", mnc = "07", tac = "1", dbm = -90,
        mcc = "214", radioTech = RadioTech.LTE, arfcn = 1850, pci = 117
    )
    private fun entry(
        radio: RadioTech = RadioTech.LTE, ta: Int? = 3, pci: Int? = 117, arfcn: Int? = 1850,
        mcc: String = "214", mnc: String = "07", unit: TimingAdvanceUnit = TimingAdvanceUnit.LTE_INDEX
    ) = serving.copy(isRegistered = false, radioTech = radio, timingAdvance = ta, timingAdvanceUnit = unit,
        pci = pci, arfcn = arfcn, mcc = mcc, mnc = mnc)

    @Test fun `a same-technology same-carrier duplicate lends its TA`() {
        assertEquals(3, TimingAdvanceBorrowing.source(serving, listOf(serving, entry()))?.timingAdvance)
    }

    @Test fun `an NR or GSM entry with the same numbers never lends its TA`() {
        assertNull(TimingAdvanceBorrowing.source(serving, listOf(entry(radio = RadioTech.NR, unit = TimingAdvanceUnit.NR_RAW))))
        assertNull(TimingAdvanceBorrowing.source(serving, listOf(entry(radio = RadioTech.GSM, unit = TimingAdvanceUnit.GSM_INDEX))))
    }

    @Test fun `another carrier or physical cell does not lend its TA`() {
        assertNull(TimingAdvanceBorrowing.source(serving, listOf(entry(arfcn = 6300))))
        assertNull(TimingAdvanceBorrowing.source(serving, listOf(entry(pci = 90))))
        assertNull(TimingAdvanceBorrowing.source(serving, listOf(entry(mnc = "01"))))
    }

    @Test fun `a field the duplicate omits is not required, but one physical field must match`() {
        assertEquals(3, TimingAdvanceBorrowing.source(serving, listOf(entry(pci = null)))?.timingAdvance)
        assertEquals(3, TimingAdvanceBorrowing.source(serving, listOf(entry(mcc = "N/A", mnc = "N/A")))?.timingAdvance)
        assertNull(TimingAdvanceBorrowing.source(serving, listOf(entry(pci = null, arfcn = null))))
    }

    @Test fun `nothing is borrowed when the serving cell already has a TA or the duplicate has none`() {
        assertNull(TimingAdvanceBorrowing.source(serving.copy(timingAdvance = 1), listOf(entry())))
        assertNull(TimingAdvanceBorrowing.source(serving, listOf(entry(ta = null))))
        assertNull(TimingAdvanceBorrowing.source(serving, listOf(entry(ta = -1))))
        assertNull(TimingAdvanceBorrowing.source(serving.copy(radioTech = RadioTech.UNKNOWN), listOf(entry(radio = RadioTech.UNKNOWN))))
    }
}
