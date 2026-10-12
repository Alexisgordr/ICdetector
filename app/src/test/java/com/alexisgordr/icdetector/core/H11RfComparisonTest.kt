package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — H11 solo suma la severidad RF (+25) con PCI/PSC o canal válidos y comparables en ambos
 * lados. Centinelas y datos desconocidos no son discrepancia. La parte geográfica no cambia.
 */
class H11RfComparisonTest {
    private val MAX = Int.MAX_VALUE
    private fun active(radio: RadioTech = RadioTech.LTE, pci: Int? = 100, arfcn: Int? = 1300) = CellData(
        isRegistered = true, networkType = "4G LTE", radioTech = radio, cellId = "1000", mnc = "01", tac = "100",
        dbm = -80, mcc = "214", pci = pci, arfcn = arfcn)
    private fun record(radio: RadioTech = RadioTech.LTE, pci: Int? = 100, arfcn: Int? = 1300) = HistoryRecord(
        timestamp = "2026-10-01 10:00:00", netType = "4G LTE", cid = "1000", mnc = "01", tac = "100", mcc = "214",
        dbm = -80, lat = 40.0, lon = -3.0, pci = pci, arfcn = arfcn, radio = radio)
    private fun far(a: CellData, r: HistoryRecord) = ThreatAnalyzer.mobileCellRecord(a, r, distance = 30_000.0, threshold = 10_000.0)

    @Test fun `geographic part is unchanged`() {
        assertEquals(ThreatAnalyzer.RecordAnalysis(false, 0, null),
            ThreatAnalyzer.mobileCellRecord(active(), record(), 9_000.0, 10_000.0))
        assertEquals(10, ThreatAnalyzer.mobileCellRecord(active(), record(), 11_000.0, 10_000.0).severity)
        assertEquals(20, ThreatAnalyzer.mobileCellRecord(active(), record(), 15_000.0, 10_000.0).severity)
        assertEquals(30, ThreatAnalyzer.mobileCellRecord(active(), record(), 20_000.0, 10_000.0).severity)
        assertEquals(40, far(active(), record()).severity)
        assertEquals("Celda detectada a distancia anómala (> 10000m)", far(active(), record()).threatDetail)
    }

    @Test fun `valid mismatches keep the RF severity`() {
        assertEquals(65, far(active(pci = 100), record(pci = 200)).severity)
        assertEquals(65, far(active(arfcn = 1300), record(arfcn = 6300)).severity)
        assertEquals("Inconsistencia RF (PCI/ARFCN) detectada a gran distancia", far(active(pci = 1), record(pci = 2)).threatDetail)
        assertEquals(65, far(active(RadioTech.UMTS, pci = 10, arfcn = 10_700), record(RadioTech.UMTS, pci = 20, arfcn = 10_700)).severity)
        assertEquals(65, far(active(RadioTech.NR, pci = 500, arfcn = 632_448), record(RadioTech.NR, pci = 900, arfcn = 632_448)).severity)
    }

    @Test fun `unknown, invalid or sentinel data is not a mismatch`() {
        listOf(
            active(pci = 100) to record(pci = MAX),          // centinela en el historial
            active(pci = MAX) to record(pci = 100),          // centinela en la lectura actual
            active(pci = null) to record(pci = 100),         // desconocido frente a conocido
            active(pci = 100) to record(pci = null),
            active(pci = 100) to record(pci = 600),          // PCI LTE imposible (0..503)
            active(arfcn = 1300) to record(arfcn = MAX),
            active(arfcn = MAX) to record(arfcn = 1300),
            active(arfcn = 1300) to record(arfcn = -5),
            active(arfcn = 1300) to record(arfcn = 300_000), // fuera del rango EARFCN
            active() to record(radio = RadioTech.UNKNOWN, pci = 200, arfcn = 6300), // fila sin tecnología
            active(RadioTech.LTE, pci = 100) to record(RadioTech.NR, pci = 200),    // otro espacio de IDs
            active(RadioTech.GSM, pci = null, arfcn = 50) to record(RadioTech.GSM, pci = 7, arfcn = 50)
        ).forEach { (a, r) ->
            val result = far(a, r)
            assertEquals("${a.pci}/${a.arfcn} vs ${r.pci}/${r.arfcn} ${r.radio}", 40, result.severity)
        }
    }

    @Test fun `historical rows with sentinels add no RF severity`() {
        val history = listOf(record(pci = MAX, arfcn = MAX), record(pci = MAX, arfcn = 1300), record(pci = 100, arfcn = MAX))
        history.forEach { assertEquals(40, far(active(pci = 100, arfcn = 1300), it).severity) }
        assertEquals(RadioChannels.RfDifference(false, false),
            RadioChannels.rfDifference(RadioTech.LTE, 100, 1300, RadioTech.LTE, MAX, MAX))
    }

    @Test fun `the parser turns only the Android sentinel into null`() {
        assertEquals(null, RadioChannels.unavailableToNull(MAX))
        listOf(0, -1, 600, 300_000, 3_279_166, 1300).forEach { assertEquals(it, RadioChannels.unavailableToNull(it)) }
        val parser = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .walkTopDown().first { it.name == "CellParser.kt" }.readText()
        listOf("arfcn = RadioChannels.unavailableToNull(id.earfcn), pci = RadioChannels.unavailableToNull(id.pci)",
            "arfcn = RadioChannels.unavailableToNull(id.nrarfcn), pci = RadioChannels.unavailableToNull(id.pci)",
            "arfcn = RadioChannels.unavailableToNull(id.uarfcn), pci = RadioChannels.unavailableToNull(id.psc)",
            "arfcn = RadioChannels.unavailableToNull(id.arfcn))").forEach { assertTrue(it, parser.contains(it)) }
        assertFalse(Regex("""arfcn = id\.\w+[,)]|pci = id\.\w+[,)]""").containsMatchIn(parser))
    }

    @Test fun `H8 still fails a measured out-of-range channel and stays N-A without one`() {
        fun h8(radio: RadioTech, arfcn: Int?) = ThreatAnalyzer.analyzeThreats(
            active = active(radio, pci = null, arfcn = arfcn), neighbors = emptyList(), isHardwareCipheringActive = true,
            cellChangeHistory = emptyList(), currentLocation = null).heuristicReport.arfcnSanity
        assertEquals(HeuristicStatus.FAILED, h8(RadioTech.LTE, 300_000))
        assertEquals(HeuristicStatus.FAILED, h8(RadioTech.NR, 3_279_166))
        assertEquals(HeuristicStatus.PASSED, h8(RadioTech.LTE, 1300))
        assertEquals(HeuristicStatus.NOT_EVALUATED, h8(RadioTech.LTE, null))      // centinela ya convertido
        assertEquals(HeuristicStatus.NOT_EVALUATED, h8(RadioTech.LTE, MAX))       // filas o entradas antiguas
    }
}
