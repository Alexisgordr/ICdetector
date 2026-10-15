package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 (#11) — TAC 65535 y Cell ID 268435455 en vecinas LTE son relleno, no identidades. */
class NeighbourPlaceholdersTest {

    @Test fun `neighbour placeholders are read as not available`() {
        assertEquals("N/A", NeighbourPlaceholders.lteTac(65_535, registered = false))
        assertEquals("N/A", NeighbourPlaceholders.lteCellId(268_435_455, registered = false))
    }

    @Test fun `the serving cell keeps what the modem reports`() {
        assertEquals("65535", NeighbourPlaceholders.lteTac(65_535, registered = true))
        assertEquals("268435455", NeighbourPlaceholders.lteCellId(268_435_455, registered = true))
    }

    @Test fun `real neighbour values and android unavailable values keep their meaning`() {
        assertEquals("16486", NeighbourPlaceholders.lteTac(16_486, registered = false))
        assertEquals("213078394", NeighbourPlaceholders.lteCellId(213_078_394, registered = false))
        assertEquals("N/A", NeighbourPlaceholders.lteTac(Int.MAX_VALUE, registered = false))
        assertEquals("N/A", NeighbourPlaceholders.lteCellId(-1, registered = true))
    }

    @Test fun `a placeholder neighbour is not a full identity for stable-site`() {
        val neighbour = com.alexisgordr.icdetector.models.CellData(
            isRegistered = false, networkType = "4G LTE", cellId = NeighbourPlaceholders.lteCellId(268_435_455, false),
            mnc = "N/A", tac = NeighbourPlaceholders.lteTac(65_535, false), dbm = -100, mcc = "N/A",
            arfcn = 1850, pci = 117
        )
        assertEquals(0, StableSiteNeighbourEvidence.diagnostic(listOf(neighbour)).fullIdentity)
    }

    @Test fun `the parser uses the placeholder rules for lte`() {
        val parser = listOf(
            File("src/main/java/com/alexisgordr/icdetector/telephony/CellParser.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/telephony/CellParser.kt")
        ).first { it.exists() }.readText()
        assertTrue(parser.contains("NeighbourPlaceholders.lteCellId(id.ci, reg)"))
        assertTrue(parser.contains("NeighbourPlaceholders.lteTac(id.tac, reg)"))
    }
}
