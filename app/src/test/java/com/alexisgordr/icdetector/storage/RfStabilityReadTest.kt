package com.alexisgordr.icdetector.storage

import com.alexisgordr.icdetector.core.DiagnosticEngine
import com.alexisgordr.icdetector.core.ThreatAnalyzer
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — H15: una lectura del historial que falla a medias no se entrega como completa. H15 se
 * abstiene en ese ciclo, el resto de reglas sigue, y el motivo distingue error de historial escaso.
 */
class RfStabilityReadTest {
    private val now = 1_700_000_000_000L
    private val recent = now - 48L * 3600_000

    private fun rows(acc: RfStabilityAccumulator, n: Int, pciA: Int = 100, pciB: Int = 200) {
        // Alternancia reciente de dos PCI en la misma portadora: la firma que H15 busca.
        repeat(n) { i -> acc.add(if (i % 4 < 2) pciA else pciB, 1300, null, now - (n - i) * 60_000L) }
    }

    @Test fun `a complete read gives the accumulated result`() {
        val ok = RfStabilityAccumulator.readComplete(RadioTech.LTE, recent) { rows(it, 12) }
        assertNotNull(ok)
        assertEquals(12, ok!!.totalObservations)
        assertEquals(setOf(100, 200), ok.distinctPci.map { it.first }.toSet())
    }

    @Test fun `a failure during the read gives null, never the partial result`() {
        val failed = RfStabilityAccumulator.readComplete(RadioTech.LTE, recent) { acc ->
            rows(acc, 8)
            throw IllegalStateException("cursor window")
        }
        assertNull(failed)
    }

    @Test fun `insufficient history is a complete result, not an error`() {
        val empty = RfStabilityAccumulator.readComplete(RadioTech.LTE, recent) { }
        assertNotNull(empty)
        assertEquals(0, empty!!.totalObservations)
    }

    @Test fun `a failed read makes only H15 abstain and names the cause`() {
        val cell = CellData(isRegistered = true, networkType = "4G LTE", radioTech = RadioTech.LTE, cellId = "1000",
            mnc = "07", tac = "1", dbm = -90, mcc = "214", arfcn = 1300, pci = 100)
        val analyzed = ThreatAnalyzer.analyzeThreats(active = cell, neighbors = emptyList(), cellChangeHistory = emptyList(), currentLocation = null, rfStability = null)
        assertEquals(HeuristicStatus.NOT_EVALUATED, analyzed.heuristicReport.rfStability)
        assertEquals("las demás reglas siguen", HeuristicStatus.PASSED, analyzed.heuristicReport.arfcnSanity)
        val inputs = DiagnosticEngine.Inputs(neighborCount = 0, wifiActive = false, locationAvailable = false, historyWithLocation = 0,
            latencyAvailable = false, previousBandAvailable = false, signalBaseline = null,
            rfFingerprint = null, rfStability = null, rfStabilityReadFailed = true, reputation = null)
        val h15 = DiagnosticEngine.explain(analyzed, inputs).first { it.id == 15 }
        assertEquals("N/A: no se pudo leer el historial RF en este ciclo.", h15.explanation)
        val insufficient = DiagnosticEngine.explain(analyzed, inputs.copy(rfStabilityReadFailed = false)).first { it.id == 15 }
        assertTrue(insufficient.explanation.startsWith("N/A: historial RF insuficiente"))
    }

    @Test fun `storage returns null on failure and the service neither caches it nor floods the terminal`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val db = main.resolve("com/alexisgordr/icdetector/storage/CellDbHelper.kt").readText()
        val fn = db.substringAfter("fun getCellRfStability(").substringBefore("private fun readCellRfStability(")
        assertTrue(fn.contains("): CellRfStability? = try {"))
        assertTrue(fn.contains("} catch (_: Exception) {\n        null"))
        assertFalse("ya no se devuelve lo acumulado", db.contains("ante cualquier problema, devolver lo acumulado"))
        assertTrue(db.contains("RfStabilityAccumulator.readComplete(radio, recentWindow)"))
        val service = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val block = service.substringAfter("if (st == null) {").substringBefore("                        st\n")
        assertTrue(block.contains("if (!rfStabilityReadFailing) {"))
        assertTrue("solo se cachea una lectura correcta", block.substringAfter("} else {").contains("cachedRfStability = st"))
        assertFalse(block.substringBefore("} else {").contains("cachedRfStability"))
        assertFalse("sin identificadores en el aviso", block.substringBefore("} else {").contains("activeRaw"))
    }
}
