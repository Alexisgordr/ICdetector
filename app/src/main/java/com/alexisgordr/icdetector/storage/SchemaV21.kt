package com.alexisgordr.icdetector.storage

/**
 * 3.0 — Migración a esquema 21. Aditiva y no destructiva: una columna con el modo de ubicación con
 * el que se observó cada fila (`CONTINUOUS` o `ADAPTIVE`, ver
 * [com.alexisgordr.icdetector.core.LocationMode]).
 *
 * Las filas existentes quedan con NULL, que significa "desconocido". Las de 2.10.x y de las
 * primeras betas 3.0 se registraron siempre en continuo, pero no se rellena: el modo no se guardó y
 * no se supone. Sin dependencias de Android, como [SchemaV20].
 */
object SchemaV21 {

    const val VERSION = 21

    const val TABLE_HISTORY = SchemaV20.TABLE_HISTORY

    const val COLUMN_LOCATION_MODE = "location_mode"

    /** Sentencias en orden; ver [CellDbHelper.onUpgrade]. */
    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_LOCATION_MODE TEXT",
    )
}
