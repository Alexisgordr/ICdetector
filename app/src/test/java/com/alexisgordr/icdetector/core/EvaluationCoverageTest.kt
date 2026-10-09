package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HeuristicStatus.FAILED
import com.alexisgordr.icdetector.models.HeuristicStatus.NOT_EVALUATED
import com.alexisgordr.icdetector.models.HeuristicStatus.PASSED
import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.utils.ExportUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (#29) — Cada fila guarda qué reglas se abstuvieron y la precisión de su GPS, para poder
 * medir después cuánto tiempo fue evaluable cada regla.
 */
class EvaluationCoverageTest {

    private fun allPassed() = HeuristicReport(
        isolatedCell = PASSED, powerJump = PASSED, mccConsistency = PASSED, mncCount = PASSED,
        tacDeviation = PASSED, taDistance = PASSED, ghostNeighbors = PASSED, arfcnSanity = PASSED,
        hardwareCiphering = PASSED, pingPong = PASSED, mobileCellId = PASSED,
        latencyCorrelation = PASSED, signalBaseline = PASSED, bandDowngrade = PASSED,
        rfStability = PASSED, transitionCoherence = PASSED
    )

    @Test fun `not evaluated rules are listed by id in order`() {
        val report = allPassed().copy(isolatedCell = NOT_EVALUATED, taDistance = NOT_EVALUATED, hardwareCiphering = NOT_EVALUATED)
        assertEquals("H1;H6;H9", report.notEvaluatedIds())
    }

    @Test fun `a failed rule is never listed as not evaluated`() {
        val report = allPassed().copy(tacDeviation = FAILED, hardwareCiphering = NOT_EVALUATED)
        val ids = report.notEvaluatedIds().split(";")
        assertEquals(listOf("H9"), ids)
        assertFalse("H5" in ids)
    }

    @Test fun `all rules evaluated is an explicit NONE, not an empty value`() {
        assertEquals(HeuristicReport.NONE_NOT_EVALUATED, allPassed().notEvaluatedIds())
        // Un informe sin calcular dice la verdad: no se evaluó ninguna.
        assertEquals((1..16).joinToString(";") { "H$it" }, HeuristicReport().notEvaluatedIds())
    }

    @Test fun `the csv exports both columns at the end and leaves them empty for legacy rows`() {
        val header = ExportUtils.CSV_HEADER.split(",")
        assertEquals(listOf("ObservedAtUtc", "NotEvaluatedHeuristics", "GpsAccuracyM"), header.takeLast(3))
        val base = HistoryRecord(
            timestamp = "2026-10-09 12:00:00", netType = "4G", cid = "1", mnc = "07",
            tac = "1", mcc = "214", dbm = -90, lat = 40.4, lon = -3.7
        )
        val row = ExportUtils.csvRow(base.copy(notEvaluatedHeuristics = "H1;H9", gpsAccuracyM = 12.34f)).split(",")
        val legacy = ExportUtils.csvRow(base).split(",")
        assertEquals(header.size, row.size)
        assertEquals("H1;H9", row[header.indexOf("NotEvaluatedHeuristics")])
        assertEquals("12.3", row[header.indexOf("GpsAccuracyM")])
        assertEquals("", legacy[header.indexOf("NotEvaluatedHeuristics")])
        assertEquals("", legacy[header.indexOf("GpsAccuracyM")])
    }

    @Test fun `every history write records coverage and the accuracy of its own fix`() {
        val source = listOf(
            File("src/main/java/com/alexisgordr/icdetector/service/ObservationPersistenceController.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/service/ObservationPersistenceController.kt")
        ).first { it.exists() }.readText()
        assertTrue(source.contains("notEvaluatedHeuristics = cell.heuristicReport.notEvaluatedIds()"))
        assertTrue(source.contains("gpsAccuracyM = fix?.takeIf { it.hasAccuracy() }?.accuracy"))
    }
}
