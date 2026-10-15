package com.alexisgordr.icdetector.storage

/**
 * 3.0 — Consultas de [com.alexisgordr.icdetector.core.RetentionClockGuard], sin Android: las usa
 * CellDbHelper.pruneOldRecords y las prueba un test con SQLite real.
 */
object RetentionGuardSql {
    /** Observación más reciente con instante (NULL si ninguna lo tiene). */
    const val NEWEST_OBSERVED = "SELECT MAX(observed_at_ms) FROM history"

    /**
     * Filas totales y filas que la poda borraría. Mismos dos parámetros y misma condición que el
     * DELETE del historial ([ObservationTime.cutoffArgs], [ObservationTime.beforeClause]).
     */
    val DUE_AND_TOTAL = "SELECT COUNT(*), COALESCE(SUM(CASE WHEN " +
        ObservationTime.beforeClause("observed_at_ms", "timestamp") + " THEN 1 ELSE 0 END), 0) FROM history"
}
