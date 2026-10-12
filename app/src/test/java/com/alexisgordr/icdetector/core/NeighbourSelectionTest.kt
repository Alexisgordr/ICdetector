package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellConnectionState
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — Las portadoras SECONDARY_SERVING publicadas sin `isRegistered` ya no cuentan como vecinas.
 *
 * Se recorre el mismo camino que el servicio: [ServingCellSelection.withPrimaryFirst] sobre la
 * lista leída y después [ServingCellSelection.neighbours], que es la lista que reciben
 * ThreatAnalyzer (H1-H5, H7, H11 y la densidad del bayesiano), H16 y Stable-Site.
 */
class NeighbourSelectionTest {
    private fun cell(
        cid: String,
        registered: Boolean,
        state: CellConnectionState,
        arfcn: Int,
        pci: Int,
        dbm: Int = -95,
        radio: RadioTech = RadioTech.LTE
    ) = CellData(
        isRegistered = registered, networkType = if (radio == RadioTech.NR) "5G NR" else "4G LTE",
        cellId = cid, mnc = "07", tac = "31601", dbm = dbm, mcc = "214", radioTech = radio,
        arfcn = arfcn, pci = pci, connectionState = state
    )

    private val primary = cell("100", true, CellConnectionState.PRIMARY_SERVING, 1301, 48, dbm = -70)

    @Test fun `nr secondary leg is a secondary carrier and not a neighbour`() {
        val nrLeg = cell("N/A", false, CellConnectionState.SECONDARY_SERVING, 632448, 12, dbm = -85, radio = RadioTech.NR)
        val ordered = ServingCellSelection.withPrimaryFirst(listOf(nrLeg, primary))

        assertEquals("100", ordered.first().cellId)
        assertEquals(listOf("NR:632448:12"), ordered.first().secondaryCarriers.map { it.compact })
        assertTrue(ServingCellSelection.neighbours(ordered).isEmpty())
        assertEquals("sigue disponible para mostrarla", listOf(nrLeg), ServingCellSelection.nonServingEntries(ordered))
    }

    @Test fun `unregistered lte secondary is not a neighbour either`() {
        val lteSecondary = cell("200", false, CellConnectionState.SECONDARY_SERVING, 6400, 200, dbm = -80)
        val ordered = ServingCellSelection.withPrimaryFirst(listOf(primary, lteSecondary))

        assertEquals(listOf("LTE:6400:200"), ordered.first().secondaryCarriers.map { it.compact })
        assertTrue(ServingCellSelection.neighbours(ordered).isEmpty())
    }

    @Test fun `real lte and nr neighbours are kept in order`() {
        val lteNeighbour = cell("301", false, CellConnectionState.NONE, 1301, 177, dbm = -100)
        val nrNeighbour = cell("N/A", false, CellConnectionState.NONE, 632448, 301, dbm = -105, radio = RadioTech.NR)
        val nrLeg = cell("N/A", false, CellConnectionState.SECONDARY_SERVING, 632448, 12, radio = RadioTech.NR)
        val ordered = ServingCellSelection.withPrimaryFirst(listOf(lteNeighbour, primary, nrLeg, nrNeighbour))

        assertEquals(listOf(lteNeighbour, nrNeighbour), ServingCellSelection.neighbours(ordered))
    }

    @Test fun `a secondary alone does not make the neighbour count positive`() {
        // Secundaria muy débil: antes contaba como vecina, hacía evaluable H2/H7 y H1 no veía
        // "sin vecinas"; el bayesiano y H11 la sumaban a la densidad.
        val nrLeg = cell("N/A", false, CellConnectionState.SECONDARY_SERVING, 632448, 12, dbm = -120, radio = RadioTech.NR)
        val ordered = ServingCellSelection.withPrimaryFirst(listOf(primary, nrLeg))
        val neighbours = ServingCellSelection.neighbours(ordered)

        assertEquals(0, neighbours.size)
        assertEquals(25_000.0, ThreatAnalyzer.getDynamicLocationThreshold(neighbours, RadioTech.LTE), 0.0)
        val result = ThreatAnalyzer.analyzeThreats(
            active = ordered.first(),
            neighbors = neighbours,
            isHardwareCipheringActive = false,
            cellChangeHistory = emptyList(),
            currentLocation = null,
            isWifiActive = false,
            isolatedCellConfirmed = true
        )
        assertEquals(HeuristicStatus.FAILED, result.heuristicReport.isolatedCell)
        assertEquals(HeuristicStatus.NOT_EVALUATED, result.heuristicReport.powerJump)
        assertEquals(HeuristicStatus.NOT_EVALUATED, result.heuristicReport.ghostNeighbors)
    }

    @Test fun `without a usable primary a secondary is never chosen`() {
        val unusablePrimary = cell("100", true, CellConnectionState.PRIMARY_SERVING, 1301, 48, dbm = Int.MAX_VALUE)
        val nrLeg = cell("N/A", false, CellConnectionState.SECONDARY_SERVING, 632448, 12, dbm = -60, radio = RadioTech.NR)
        val lteSecondary = cell("200", true, CellConnectionState.SECONDARY_SERVING, 6400, 200, dbm = -65)

        assertEquals(-1, ServingCellSelection.primaryIndex(listOf(unusablePrimary, nrLeg, lteSecondary)))
        assertEquals("sin registrada no hay servidora", -1, ServingCellSelection.primaryIndex(listOf(nrLeg)))
    }

    @Test fun `unknown state keeps the previous behaviour`() {
        // No se inventa el papel de UNKNOWN: no registrada sigue siendo vecina; registrada sigue
        // siendo portadora y nunca vecina, igual que antes del cambio.
        val unknownUnregistered = cell("302", false, CellConnectionState.UNKNOWN, 1301, 2)
        val unknownRegistered = cell("303", true, CellConnectionState.UNKNOWN, 6400, 3)
        val ordered = ServingCellSelection.withPrimaryFirst(listOf(primary, unknownUnregistered, unknownRegistered))

        assertEquals(listOf(unknownUnregistered), ServingCellSelection.neighbours(ordered))
        assertEquals(listOf("LTE:6400:3"), ordered.first().secondaryCarriers.map { it.compact })
        val previous = ordered.filter { !it.isRegistered }
        assertEquals(previous, ServingCellSelection.neighbours(ordered))
    }

    @Test fun `service and screens use the shared selection`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val service = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        assertTrue(service.contains("val neighbors = com.alexisgordr.icdetector.core.ServingCellSelection.neighbours(list)"))
        assertTrue("la caja negra conserva el contexto completo", service.contains("neighbors = nonServingContext,"))
        main.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            assertFalse("${file.name} vuelve a filtrar vecinas por su cuenta",
                Regex("""\.filter \{ !it\.isRegistered \}""").containsMatchIn(file.readText()) &&
                    file.name != "ServingCellSelection.kt")
        }
    }
}
