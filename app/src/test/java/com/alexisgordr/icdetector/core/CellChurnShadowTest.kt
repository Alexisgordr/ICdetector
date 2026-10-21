package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.utils.ExportUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.1.0 — H17 (churn de celdas) en sombra: se guarda en cada fila y en el CSV, pero nunca se evalúa,
 * nunca figura entre las reglas fallidas y nunca cambia el score ni la confianza de anomalía.
 */
class CellChurnShadowTest {
    private fun cell(dbm: Int = -60, rsrq: Int? = -18, sinr: Int? = -2) = CellData(isRegistered = true, networkType = "4G LTE",
        cellId = "1000", mnc = "07", tac = "100", dbm = dbm, mcc = "214", rsrq = rsrq, sinr = sinr, radioTech = RadioTech.LTE)

    @Test fun `H17 is never evaluated and never listed as failed`() {
        listOf(cell(), cell(dbm = -95, rsrq = null, sinr = null)).forEach { c ->
            val analyzed = ThreatAnalyzer.analyzeThreats(c, emptyList(), emptyList(), null)
            assertEquals(HeuristicStatus.NOT_EVALUATED, analyzed.heuristicReport.cellChurn)
            assertTrue("H17(sombra)" in analyzed.heuristicReport.notEvaluatedIds().split(";"))
            assertFalse(analyzed.suspiciousReason.orEmpty().contains("H17"))
        }
    }

    @Test fun `even a failed H17 is not evidence, has no weight and moves no score`() {
        val failed = HeuristicReport(cellChurn = HeuristicStatus.FAILED)
        assertEquals("no cuenta como fallo de evidencia", 0, failed.failedCount)
        assertTrue("H17" in HeuristicReport.NON_EVIDENCE_IDS)
        assertFalse(HeuristicCatalog.CELL_CHURN.isEvidence)
        assertEquals(BayesianScorer.calculate(emptyList(), "PENDING"), BayesianScorer.calculate(listOf("cellChurn"), "PENDING"), 0f)
    }

    @Test fun `the churn reaches the row after the analysis and never enters it`() {
        fun src(p: String) = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/$p").readText()
        val analyzer = src("core/ThreatAnalyzer.kt")
        assertFalse("el analizador no recibe el churn", analyzer.substringAfter("fun analyzeThreats(").substringBefore("): CellData").contains("churn", ignoreCase = true))
        val service = src("service/MiniICService.kt")
        assertTrue(service.contains("stableSiteDecision\n                        ).copy(churn = churn)"))
        assertTrue("reinicio tras pérdida o hueco", service.substringAfter("if (continuityBroken) {").substringBefore("}").contains("cellChurn.restartAt(deliveredAtElapsedMs, servedAtMs = activeRaw != null)"))
        assertTrue(service.contains("appendLog(\"[H17 sombra]\", churnLog)"))
        assertTrue(src("service/ObservationPersistenceController.kt").contains("churn = cell.churn"))
    }

    @Test fun `the csv keeps the 3_0_0 columns in order and adds the churn at the end`() {
        val header = ExportUtils.CSV_HEADER
        assertTrue(header.startsWith(HEADER_3_0_0 + ","))
        assertEquals("ChurnCells15m,ChurnChanges15m,ChurnMotion", header.removePrefix(HEADER_3_0_0 + ","))
        val base = HistoryRecord(timestamp = "2026-10-21 10:00:00", netType = "4G", cid = "1", mnc = "07", tac = "1", mcc = "214", dbm = -90)
        val names = header.split(",")
        val row = ExportUtils.csvRow(base.copy(churnCells15m = 4, churnChanges15m = 6, churnMotion = "MOVING")).split(",")
        assertEquals(names.size, row.size)
        assertEquals(listOf("4", "6", "MOVING"), row.takeLast(3))
        assertEquals("vacías en filas anteriores", listOf("", "", ""), ExportUtils.csvRow(base).split(",").takeLast(3))
    }

    private companion object {
        /** Cabecera del CSV de historial de 3.0.0, literal. */
        const val HEADER_3_0_0 = "Timestamp,NetType,CID,MNC,TAC,MCC,DBM,Verified,SecurityScore,FailedHeuristics,Lat,Lon,PCI,ARFCN," +
            "RSRQ,SINR,AnomalyConfidence,ApiLat,ApiLon,TA,TAUnit,TAMeters,Radio,ServingConnection,BandwidthKHz,Bands,AdditionalPlmns," +
            "CsgIndicator,CsgIdentity,CsgName,SecondaryCarriers,ServiceState,NetworkOperator,SimOperator,NetworkRoaming,ObservedAtUtc," +
            "NotEvaluatedHeuristics,GpsAccuracyM,AppVersion,ExportDevice,ExportAndroid,LocationMode,ProfileMode,ProfileLevel,ProfileScore," +
            "ProfileCoverage,ProfileAbstainReason,ProfileAlgorithm,ProfileRevision,ProfileEvidence,ProfileLearned,ProfileHoldout,RowId,ObservationId"
    }
}
