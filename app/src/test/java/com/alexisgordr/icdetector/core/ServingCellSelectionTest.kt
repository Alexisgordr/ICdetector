package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellConnectionState
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.toCompactString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.10.4 — Regresión de la elección de la celda servidora con agregación de portadoras.
 *
 * El caso que motivó el cambio: dos entradas registradas, la secundaria con MÁS señal. Antes se
 * analizaban las dos con el historial de la primera y ganaba la más fuerte.
 */
class ServingCellSelectionTest {
    private fun cell(
        cid: String,
        registered: Boolean,
        state: CellConnectionState,
        arfcn: Int,
        pci: Int,
        dbm: Int = -95,
        radio: RadioTech = RadioTech.LTE
    ) = CellData(
        isRegistered = registered, networkType = "4G LTE", cellId = cid, mnc = "07", tac = "31601",
        dbm = dbm, mcc = "214", radioTech = radio, arfcn = arfcn, pci = pci, connectionState = state
    )

    @Test
    fun `la primaria declarada gana aunque no sea la primera ni la mas fuerte`() {
        val secondary = cell("200", true, CellConnectionState.SECONDARY_SERVING, 6400, 200, dbm = -70)
        val primary = cell("100", true, CellConnectionState.PRIMARY_SERVING, 1301, 48, dbm = -100)
        val cells = listOf(secondary, primary)

        assertEquals(1, ServingCellSelection.primaryIndex(cells))
        val ordered = ServingCellSelection.withPrimaryFirst(cells)
        assertEquals("100", ordered.first().cellId)
        assertEquals(listOf("LTE:6400:200"), ordered.first().secondaryCarriers.map { it.compact })
    }

    @Test
    fun `sin estado de conexion se conserva el comportamiento anterior`() {
        val first = cell("100", true, CellConnectionState.UNKNOWN, 1301, 48)
        val second = cell("200", true, CellConnectionState.UNKNOWN, 6400, 200, dbm = -60)

        assertEquals(0, ServingCellSelection.primaryIndex(listOf(first, second)))
    }

    @Test
    fun `primaria declarada sin dbm obliga a abstenerse aunque haya secundaria valida`() {
        val primary = cell("100", true, CellConnectionState.PRIMARY_SERVING, 1301, 48, dbm = Int.MAX_VALUE)
        val secondary = cell("200", true, CellConnectionState.SECONDARY_SERVING, 6400, 200, dbm = -65)
        val cells = listOf(secondary, primary)

        assertEquals(-1, ServingCellSelection.primaryIndex(cells))
        assertTrue(ServingCellSelection.hasDeclaredPrimary(cells))
        assertTrue(ServingCellSelection.abstentionPublication().none { it.isRegistered })
    }

    @Test
    fun `fallback omite registrada inutilizable solo cuando no hay primaria declarada`() {
        val unusable = cell("100", true, CellConnectionState.UNKNOWN, 1301, 48, dbm = Int.MAX_VALUE)
        val usable = cell("200", true, CellConnectionState.UNKNOWN, 6400, 200, dbm = -90)

        assertEquals(1, ServingCellSelection.primaryIndex(listOf(unusable, usable)))
    }

    @Test
    fun `sin celdas registradas no hay servidora y la lista no cambia`() {
        val cells = listOf(cell("N/A", false, CellConnectionState.NONE, 1301, 16))

        assertEquals(-1, ServingCellSelection.primaryIndex(cells))
        assertSame(cells, ServingCellSelection.withPrimaryFirst(cells))
    }

    @Test
    fun `la pata NR de NSA cuenta como secundaria aunque no venga registrada`() {
        val anchor = cell("100", true, CellConnectionState.PRIMARY_SERVING, 1301, 48)
        val nrLeg = cell("N/A", false, CellConnectionState.SECONDARY_SERVING, 632448, 12, radio = RadioTech.NR)
        val neighbour = cell("300", false, CellConnectionState.NONE, 1301, 177)

        val carriers = ServingCellSelection.secondaryCarriers(listOf(anchor, nrLeg, neighbour), 0)

        assertEquals("NR:632448:12", carriers.toCompactString())
    }

    @Test
    fun `varias secundarias nunca cambian la identidad analizada`() {
        val primary = cell("100", true, CellConnectionState.PRIMARY_SERVING, 1301, 48, dbm = -110)
        val lteSecondary = cell("200", true, CellConnectionState.SECONDARY_SERVING, 6400, 200, dbm = -60)
        val nrSecondary = cell("N/A", false, CellConnectionState.SECONDARY_SERVING, 632448, 12, dbm = -55, radio = RadioTech.NR)

        val ordered = ServingCellSelection.withPrimaryFirst(listOf(lteSecondary, nrSecondary, primary))

        assertEquals("100", ordered.first().cellId)
        assertEquals(setOf("LTE:6400:200", "NR:632448:12"), ordered.first().secondaryCarriers.map { it.compact }.toSet())
    }

    @Test
    fun `una entrada duplicada de la misma portadora no es una secundaria`() {
        val primary = cell("100", true, CellConnectionState.PRIMARY_SERVING, 1301, 48)
        val duplicate = cell("100", true, CellConnectionState.UNKNOWN, 1301, 48)

        assertTrue(ServingCellSelection.secondaryCarriers(listOf(primary, duplicate), 0).isEmpty())
    }

    @Test
    fun `las vecinas conservan su orden detras de la servidora`() {
        val n1 = cell("301", false, CellConnectionState.NONE, 1301, 1)
        val primary = cell("100", true, CellConnectionState.PRIMARY_SERVING, 1301, 48)
        val n2 = cell("302", false, CellConnectionState.NONE, 1301, 2)

        val ordered = ServingCellSelection.withPrimaryFirst(listOf(n1, primary, n2))

        assertEquals(listOf("100", "301", "302"), ordered.map { it.cellId })
    }
}
