package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.RetentionClockGuard.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0 — La poda por antigüedad no se ejecuta con un reloj que va por delante de los datos. */
class RetentionClockGuardTest {
    private val day = 86_400_000L
    private val newest = 1_790_000_000_000L

    @Test fun `a normal daily prune goes ahead`() {
        assertEquals(Decision.Prune, RetentionClockGuard.decide(newest - 120 * day, newest, rowsDue = 300, totalRows = 36_000))
    }

    @Test fun `a cutoff after the newest observation is skipped`() {
        val cutoff = newest + day
        assertEquals(Decision.CutoffAfterNewest(cutoff, newest), RetentionClockGuard.decide(cutoff, newest, rowsDue = 36_000, totalRows = 36_000))
    }

    @Test fun `deleting more than a quarter of the history is skipped`() {
        assertEquals(Decision.TooManyRows(9_001, 36_000), RetentionClockGuard.decide(newest - 90 * day, newest, rowsDue = 9_001, totalRows = 36_000))
        assertEquals("justo el 25 % pasa", Decision.Prune, RetentionClockGuard.decide(newest - 90 * day, newest, rowsDue = 9_000, totalRows = 36_000))
    }

    @Test fun `a small database prunes normally`() {
        // Menos de MIN_ROWS_FOR_SHARE filas a borrar: la proporción no se mira.
        assertEquals(Decision.Prune, RetentionClockGuard.decide(newest - 120 * day, newest, rowsDue = 2, totalRows = 3))
        assertTrue(RetentionClockGuard.decide(newest - 120 * day, newest, rowsDue = 200, totalRows = 300) is Decision.TooManyRows)
    }

    @Test fun `rows without an instant leave only the share check`() {
        assertEquals(Decision.Prune, RetentionClockGuard.decide(newest, newestObservedMs = null, rowsDue = 0, totalRows = 0))
        assertTrue(RetentionClockGuard.decide(newest, newestObservedMs = null, rowsDue = 500, totalRows = 600) is Decision.TooManyRows)
    }

    @Test fun `maintenance says why nothing was pruned`() {
        val ahead = MaintenanceReport.pruneLine(MaintenanceReport.Prune.Skipped(Decision.CutoffAfterNewest(2, 1)), 120)!!
        assertTrue(ahead.startsWith("⚠️ Poda de histórico omitida: el reloj del teléfono va por delante"))
        val many = MaintenanceReport.pruneLine(MaintenanceReport.Prune.Skipped(Decision.TooManyRows(900, 1_000)), 120)!!
        assertEquals("⚠️ Poda de histórico omitida: borraría 900 registros (de 1000; más del 25 %); ¿reloj adelantado? No se ha borrado nada.", many)
    }

    @Test fun `the prune asks the guard before deleting anything`() {
        val src = listOf("src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt",
            "app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt").map { java.io.File(it) }.first { it.exists() }.readText()
        val prune = src.substringAfter("fun pruneOldRecords(").substringBefore("\n    /**")
        val guard = prune.indexOf("RetentionClockGuard.decide(")
        assertTrue(guard > 0)
        assertTrue("ningún borrado antes de la guardia", prune.indexOf("db.delete(") > guard && prune.indexOf("DELETE FROM") > guard)
        assertTrue(prune.contains("return MaintenanceReport.Prune.Skipped(guard)"))
        assertTrue("una sola hora de pared para todas las tablas", Regex("System\\.currentTimeMillis\\(\\)").findAll(prune).count() == 1)
    }
}
