package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.MaintenanceReport.Prune
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — Un fallo de la poda nunca se presenta como "0 filas" ni como mantenimiento correcto. */
class MaintenanceReportTest {
    @Test fun `success, nothing to delete and failure read differently`() {
        assertEquals("Poda de histórico: 12 registros de más de 120 días eliminados.", MaintenanceReport.pruneLine(Prune.Done(12), 120))
        assertNull("nada que borrar no ensucia el terminal", MaintenanceReport.pruneLine(Prune.Done(0), 120))
        val failed = MaintenanceReport.pruneLine(Prune.Failed("SQLiteDiskIOException"), 120)!!
        assertTrue(failed.startsWith("⚠️ Poda de histórico fallida (SQLiteDiskIOException)"))
        assertFalse("no se anuncia como borrado", failed.contains("eliminados"))
    }

    @Test fun `storage propagates the error after rollback and maintenance reports it once`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val db = main.resolve("com/alexisgordr/icdetector/storage/CellDbHelper.kt").readText()
        val prune = db.substringAfter("fun pruneOldRecords(").substringBefore("\n    /**")
        assertTrue(prune.contains("db.beginTransaction()") && prune.contains("db.setTransactionSuccessful()"))
        assertTrue(prune.contains("} finally {\n            db.endTransaction()"))
        assertFalse("un error ya no es «0 filas»", Regex("""catch \(_: Exception\) \{\s*0\s*\}""").containsMatchIn(prune))
        assertTrue(prune.contains("return try {"))
        val maintenance = main.resolve("com/alexisgordr/icdetector/service/DatabaseMaintenance.kt").readText()
        assertTrue(maintenance.contains("MaintenanceReport.Prune.Failed(e.javaClass.simpleName)"))
        assertTrue("el tope forense se aplica igual", maintenance.indexOf("pruneLine(") < maintenance.indexOf("enforceForensicSampleCap()"))
        assertFalse("sin mensajes de error en bruto", maintenance.contains("e.message"))
    }
}
