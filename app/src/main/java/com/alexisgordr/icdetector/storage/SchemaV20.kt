package com.alexisgordr.icdetector.storage

/**
 * 3.0 — Migración a esquema 20. Aditiva y no destructiva: solo añade columnas e índices.
 *
 * Las filas existentes quedan con NULL en todas las columnas nuevas, que significa
 * "desconocido": nunca se rellenan con un valor supuesto. Vive fuera de [CellDbHelper] y sin
 * dependencias de Android para que la migración se pueda probar contra un esquema 19 real.
 */
object SchemaV20 {

    const val VERSION = 20

    // Mismos nombres que en [CellDbHelper]; repetidos aquí para que este objeto no dependa de
    // Android. SchemaV20MigrationTest comprueba que coinciden con el código fuente.
    const val TABLE_HISTORY = "history"
    const val TABLE_INCIDENTS = "incidents"
    const val TABLE_FORENSIC_CASES = "forensic_cases"

    // #23 — Instante de la observación, en milisegundos desde epoch (UTC).
    const val COLUMN_OBSERVED_AT_MS = "observed_at_ms"
    const val INCIDENT_STARTED_AT_MS = "started_at_ms"
    const val UPDATED_AT_MS = "updated_at_ms"
    const val CASE_CREATED_AT_MS = "created_at_ms"

    // #29 — Cobertura de evaluación y calidad del GPS de cada fila.
    const val COLUMN_NOT_EVALUATED = "not_evaluated_heuristics"
    const val COLUMN_GPS_ACCURACY_M = "gps_accuracy_m"

    /** Sentencias en orden. Cada una se ejecuta por separado; ver [CellDbHelper.onUpgrade]. */
    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_OBSERVED_AT_MS INTEGER",
        "CREATE INDEX IF NOT EXISTS idx_history_observed_at ON $TABLE_HISTORY ($COLUMN_OBSERVED_AT_MS)",
        "ALTER TABLE $TABLE_INCIDENTS ADD COLUMN $INCIDENT_STARTED_AT_MS INTEGER",
        "ALTER TABLE $TABLE_INCIDENTS ADD COLUMN $UPDATED_AT_MS INTEGER",
        "ALTER TABLE $TABLE_FORENSIC_CASES ADD COLUMN $CASE_CREATED_AT_MS INTEGER",
        "ALTER TABLE $TABLE_FORENSIC_CASES ADD COLUMN $UPDATED_AT_MS INTEGER",
        "ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_NOT_EVALUATED TEXT",
        "ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_GPS_ACCURACY_M REAL",
    )
}
