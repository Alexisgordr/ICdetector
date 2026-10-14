package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — LocalCellTrustEngine valida el PCI/ARFCN ACTUAL igual que las referencias aprendidas: un
 * valor ausente, un sentinel o fuera de rango no es por sí solo una contradicción RF. CellData no se
 * modifica, así que H8 sigue viendo el valor original.
 */
class LocalTrustRfValidationTest {
    private fun cell(pci: Int?, arfcn: Int?, radio: RadioTech = RadioTech.LTE) =
        CellData(true, if (radio == RadioTech.NR) "5G NR" else "4G LTE", "100", "07", "31601", -90, "214",
            verified = VerificationStatus.VERIFIED, pci = pci, arfcn = arfcn, radioTech = radio)

    private fun mature(byArfcn: Map<Int, Set<Int>> = mapOf(2850 to setOf(10))) = LocalCellTrustEvidence(
        cleanObservations = 80, cappedCleanObservations = 35, distinctDays = 14, ageHours = 14L * 24,
        locatedObservations = 30, knownPcis = byArfcn.values.flatten().toSet(), knownArfcns = byArfcn.keys,
        rfObservations = 60, knownPcisByArfcn = byArfcn, trustedTransitions = 20, trustedRoutes = 4
    )

    private fun contradictions(c: CellData, e: LocalCellTrustEvidence = mature()) =
        LocalCellTrustEngine.apply(c, e).localCellTrust.contradictions

    @Test fun `valid values that match are coherent`() {
        assertEquals(emptySet<String>(), contradictions(cell(10, 2850)))
    }

    @Test fun `legitimately detectable rf changes are still detected`() {
        assertEquals(setOf("PCI"), contradictions(cell(311, 2850)))
        assertEquals(setOf("ARFCN"), contradictions(cell(10, 6400)))
        val nr = mature(mapOf(632_448 to setOf(12)))
        assertEquals(setOf("PCI"), contradictions(cell(500, 632_448, RadioTech.NR), nr))   // 500 válido en NR (0..1007)
    }

    @Test fun `null, sentinel and out-of-range values are unknown, not a contradiction`() {
        listOf(null, Int.MAX_VALUE, 504, -1).forEach { badPci ->
            assertEquals("PCI $badPci", emptySet<String>(), contradictions(cell(badPci, 2850)))
        }
        listOf(null, Int.MAX_VALUE, 262_144, -1).forEach { badArfcn ->
            assertEquals("ARFCN $badArfcn", emptySet<String>(), contradictions(cell(10, badArfcn)))
        }
        assertEquals("ambos inválidos", emptySet<String>(), contradictions(cell(Int.MAX_VALUE, Int.MAX_VALUE)))
    }

    @Test fun `a valid change is still detected next to an invalid other value`() {
        // ARFCN inválido: no se puede buscar la portadora, así que el PCI tampoco se compara.
        assertEquals(emptySet<String>(), contradictions(cell(311, Int.MAX_VALUE)))
        // PCI inválido en una portadora desconocida: el ARFCN nuevo sí es un cambio.
        assertEquals(setOf("ARFCN"), contradictions(cell(Int.MAX_VALUE, 6400)))
    }

    @Test fun `cell data keeps the original values so H8 can still fail them`() {
        val out = LocalCellTrustEngine.apply(cell(Int.MAX_VALUE, 262_144), mature())
        assertEquals(Int.MAX_VALUE, out.pci)
        assertEquals(262_144, out.arfcn)
        val h8 = ThreatAnalyzer.analyzeThreats(active = cell(10, 262_144), neighbors = emptyList(),
            cellChangeHistory = emptyList(), currentLocation = null)
        assertEquals(HeuristicStatus.FAILED, h8.heuristicReport.arfcnSanity)
    }

    @Test fun `accepted reconfiguration uses validated values`() {
        val candidate = LocalRfReconfiguration(pci = 311, arfcn = 2850, distinctDays = 14, cappedObservations = 25,
            locatedObservations = 20, ageHours = 13L * 24L, oldPairSeenRecently = false)
        val evidence = mature().copy(reconfigurationCandidate = candidate)
        assertEquals("aceptada con valores válidos", emptySet<String>(), contradictions(cell(311, 2850), evidence))
        assertEquals("un sentinel no la activa ni contradice", emptySet<String>(), contradictions(cell(Int.MAX_VALUE, 2850), evidence))
    }

    @Test fun `the reconfiguration candidate query validates the current pair first`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val db = main.resolve("com/alexisgordr/icdetector/storage/CellDbHelper.kt").readText()
        val evidence = db.substringAfter("fun getLocalCellTrustEvidence(")
        assertTrue(evidence.contains("val currentPci = RadioChannels.physicalIdOrNull(cell.radioTech, cell.pci)"))
        assertTrue(evidence.contains("val currentArfcn = RadioChannels.channelOrNull(cell.radioTech, cell.arfcn)"))
        assertTrue(evidence.contains("currentPci.toString(), currentArfcn.toString(),"))
        assertFalse(evidence.substringBefore("val candidateWhere").contains("cell.pci.toString()"))
    }
}
