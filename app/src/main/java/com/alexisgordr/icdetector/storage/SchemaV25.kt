package com.alexisgordr.icdetector.storage

/**
 * 3.0 (UI2) — Migración a esquema 25. Aditiva: separa, en cada ruta de handover
 * (`cell_transitions`), la ÚLTIMA OBSERVACIÓN (que puede ser N/A) del ÚLTIMO RESULTADO EVALUADO
 * (PASSED/FAILED), y lleva la cuenta de fallos. Antes cada handover sobrescribía `last_status`,
 * también con NOT_EVALUATED, y un FAILED anterior se perdía.
 *
 *  - `last_evaluated_status` / `last_evaluated_ms`: último PASSED o FAILED y su fecha. NULL si no
 *    se conoce ninguno.
 *  - `last_explanation`: motivo de la última observación cuando fue N/A; NULL en otro caso o si no
 *    se guardó (filas anteriores).
 *  - `failed_count`: FAILED registrados desde que existe el dato (esta versión).
 *  - `failure_history`: qué se sabe de ANTES de que existiera `failed_count`. NULL = la ruta se ha
 *    seguido completa (creada con este esquema, o sin ningún handover observado antes).
 *    `LEGACY_FAILED` = su última observación anterior fue FAILED: hubo al menos un fallo, no se sabe
 *    cuántos. `LEGACY_UNKNOWN` = su última observación anterior fue PASSED o N/A: no se sabe si falló
 *    antes. Nunca se afirma «nunca falló» de una ruta antigua.
 *
 * Relleno de filas existentes ([BACKFILL], una sola vez, en la misma transacción que añade las
 * columnas): si `last_status` era PASSED/FAILED, ese es su último resultado evaluado, con
 * `last_seen_ms` como fecha (ambos se escribían juntos). `observations`, `trusted_observations`,
 * `trip_count`, `last_status` y `last_seen_ms` no se tocan; no se borra ninguna ruta.
 *
 * Sin dependencias de Android.
 */
object SchemaV25 {

    const val VERSION = 25

    const val TABLE = "cell_transitions"

    /** Columna cuya creación decide si hay que rellenar (ver [CellDbHelper.applySchemaV25]). */
    const val MARKER_COLUMN = "failure_history"

    const val LEGACY_FAILED = "LEGACY_FAILED"
    const val LEGACY_UNKNOWN = "LEGACY_UNKNOWN"

    /** Columnas nuevas; la última es [MARKER_COLUMN]. */
    val COLUMNS: List<String> = listOf(
        "ALTER TABLE $TABLE ADD COLUMN last_evaluated_status TEXT",
        "ALTER TABLE $TABLE ADD COLUMN last_evaluated_ms INTEGER",
        "ALTER TABLE $TABLE ADD COLUMN last_explanation TEXT",
        "ALTER TABLE $TABLE ADD COLUMN failed_count INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE $TABLE ADD COLUMN $MARKER_COLUMN TEXT"
    )

    /** Solo si [MARKER_COLUMN] se acaba de crear: todas las filas son anteriores a este esquema. */
    val BACKFILL: List<String> = listOf(
        "UPDATE $TABLE SET last_evaluated_status=last_status, last_evaluated_ms=last_seen_ms " +
            "WHERE last_status IN ('PASSED','FAILED')",
        "UPDATE $TABLE SET $MARKER_COLUMN=CASE WHEN last_status='FAILED' THEN '$LEGACY_FAILED' " +
            "ELSE '$LEGACY_UNKNOWN' END WHERE observations>0"
    )

    // ── Escrituras de `cell_transitions` (todas las de la app; ver CellDbHelper) ───────────────

    /**
     * Un handover observado ([CellDbHelper.recordCellTransition]). Contadores atómicos, y los datos
     * nuevos: el último evaluado solo cambia con PASSED/FAILED; el motivo solo se guarda si es N/A.
     * Argumentos: [observationArgs] + from, to.
     */
    const val RECORD_UPDATE =
        "UPDATE $TABLE SET observations=observations+1, trusted_observations=trusted_observations+?, " +
            "last_status=?, last_seen_ms=?, last_explanation=?, " +
            "last_evaluated_status=COALESCE(?, last_evaluated_status), " +
            "last_evaluated_ms=COALESCE(?, last_evaluated_ms), failed_count=failed_count+? " +
            "WHERE from_identity=? AND to_identity=?"

    /** Primera observación de la ruta. Argumentos: from, to + [observationArgs]. failure_history NULL: completa. */
    const val RECORD_INSERT =
        "INSERT INTO $TABLE (from_identity, to_identity, observations, trusted_observations, last_status, last_seen_ms, " +
            "last_explanation, last_evaluated_status, last_evaluated_ms, failed_count) VALUES (?,?,1,?,?,?,?,?,?,?)"

    /** trusted, status, seen, explanation, evaluated status, evaluated ms, failed increment. */
    fun observationArgs(trustedIncrement: Int, status: String, nowMs: Long, explanation: String?): Array<Any?> {
        val evaluated = status == "PASSED" || status == "FAILED"
        return arrayOf(
            trustedIncrement, status, nowMs,
            explanation.takeIf { status == "NOT_EVALUATED" },
            status.takeIf { evaluated }, nowMs.takeIf { evaluated },
            if (status == "FAILED") 1 else 0
        )
    }

    /**
     * Confirmación de un viaje ([CellDbHelper.commitMobilityTrip]): crea la ruta si no existe (sin
     * observación de handover: N/A y contadores a 0; datos nuevos por defecto) y nunca cambia una
     * que ya existe salvo los campos de movilidad.
     */
    const val TRIP_INSERT_IF_ABSENT =
        "INSERT OR IGNORE INTO $TABLE (from_identity, to_identity, observations, trusted_observations, last_status, last_seen_ms) " +
            "VALUES (?,?,0,0,'NOT_EVALUATED',?)"

    const val TRIP_UPDATE =
        "UPDATE $TABLE SET trip_count=trip_count+1,last_trip_id=?," +
            "mobility_first_seen_ms=COALESCE(mobility_first_seen_ms,?),mobility_last_seen_ms=? " +
            "WHERE from_identity=? AND to_identity=? AND (last_trip_id IS NULL OR last_trip_id<>?)"
}
