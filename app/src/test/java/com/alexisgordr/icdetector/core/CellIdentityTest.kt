package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.identityKey
import com.alexisgordr.icdetector.models.isSameCell
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regresión: la tecnología forma parte de la identidad usada por cachés y UI. */
class CellIdentityTest {
    private fun cell(radio: RadioTech) = CellData(
        isRegistered = true,
        networkType = "test",
        cellId = "123456",
        mnc = "07",
        tac = "31601",
        dbm = -85,
        mcc = "214",
        radioTech = radio
    )

    @Test
    fun `mismos numeros en radios distintos no son la misma celda`() {
        val lte = cell(RadioTech.LTE)
        val nr = cell(RadioTech.NR)

        assertNotEquals(lte.identityKey, nr.identityKey)
        assertFalse(lte.isSameCell(nr))
    }

    @Test
    fun `misma identidad completa produce la misma clave`() {
        val first = cell(RadioTech.GSM)
        val second = cell(RadioTech.GSM)

        assertTrue(first.isSameCell(second))
    }

    @Test
    fun `historial separa un mismo cid por tecnologia`() {
        fun row(radio: RadioTech) = HistoryRecord(
            timestamp = "2026-09-15 12:00:00", netType = "test", cid = "123456",
            mnc = "07", tac = "31601", mcc = "214", dbm = -85, radio = radio
        )

        assertNotEquals(row(RadioTech.LTE).identityKey, row(RadioTech.NR).identityKey)
    }
}
