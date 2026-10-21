package com.alexisgordr.icdetector.storage

/**
 * 3.1.0 — Migración a esquema 26. Aditiva: churn de celdas de 15 min (H17, en sombra) en cada fila
 * de `history` (ver core/CellChurn).
 *
 *  - `churn_cells_15m`: identidades completas distintas que sirvieron en los 15 min anteriores.
 *  - `churn_changes_15m`: cambios de servidora en esos 15 min.
 *  - `churn_motion`: `STILL`, `MOVING` o `UNKNOWN` (fix de más de 60 s o sin velocidad).
 *
 * Solo añade columnas: las filas anteriores quedan con NULL (no se calculaba). No borra ni
 * reescribe nada. Sin dependencias de Android, como [SchemaV24].
 */
object SchemaV26 {

    const val VERSION = 26

    const val COLUMN_CHURN_CELLS = "churn_cells_15m"
    const val COLUMN_CHURN_CHANGES = "churn_changes_15m"
    const val COLUMN_CHURN_MOTION = "churn_motion"

    /** Sentencias en orden; ver [CellDbHelper.onUpgrade]. */
    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE history ADD COLUMN $COLUMN_CHURN_CELLS INTEGER",
        "ALTER TABLE history ADD COLUMN $COLUMN_CHURN_CHANGES INTEGER",
        "ALTER TABLE history ADD COLUMN $COLUMN_CHURN_MOTION TEXT"
    )
}
