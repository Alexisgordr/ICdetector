package com.alexisgordr.icdetector.storage

/**
 * 3.0 — Migración a esquema 22. Aditiva: tres tablas nuevas para el perfil local personalizado
 * (ver [com.alexisgordr.icdetector.core.profile]). No toca ninguna tabla ni columna existente; el
 * historial y sus resultados originales se conservan tal cual.
 *
 *  - [TABLE_PROFILE]: una referencia por contexto (identidad completa de la celda + tecnología) y
 *    métrica, con la versión del algoritmo, su revisión, el dispositivo que la aprendió, la cantidad
 *    y antigüedad de la evidencia, la madurez y la causa de abstención.
 *  - [TABLE_EVALUATIONS]: el resultado en sombra de cada observación nueva. `history_id` es único y
 *    referencia a `history(id)`: una evaluación pertenece a una sola fila y desaparece con ella
 *    (borrado, retención).
 *  - [TABLE_MODE_EVENTS]: cuándo y con qué modo estuvo activo el perfil (corte metodológico).
 *
 * Sin dependencias de Android, como [SchemaV21].
 */
object SchemaV22 {

    const val VERSION = 22

    const val TABLE_PROFILE = "local_profile"
    const val TABLE_EVALUATIONS = "profile_evaluations"
    const val TABLE_MODE_EVENTS = "profile_mode_events"

    /** Sentencias en orden; ver [CellDbHelper.onUpgrade]. Todas idempotentes. */
    val STATEMENTS: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS $TABLE_PROFILE (" +
            "context_key TEXT NOT NULL, " +
            "metric TEXT NOT NULL, " +
            "radio TEXT NOT NULL, " +
            "device_model TEXT NOT NULL, " +
            "algorithm_version INTEGER NOT NULL, " +
            "revision INTEGER NOT NULL DEFAULT 0, " +
            "mean REAL, " +
            "variance REAL, " +
            "effective_samples INTEGER NOT NULL DEFAULT 0, " +
            "bootstrap_samples INTEGER NOT NULL DEFAULT 0, " +
            "live_samples INTEGER NOT NULL DEFAULT 0, " +
            "quarantined_samples INTEGER NOT NULL DEFAULT 0, " +
            "distinct_days INTEGER NOT NULL DEFAULT 0, " +
            "last_day TEXT, " +
            "day_samples INTEGER NOT NULL DEFAULT 0, " +
            "day_start_mean REAL, " +
            "first_evidence_ms INTEGER, " +
            "last_evidence_ms INTEGER, " +
            "last_sample_ms INTEGER, " +
            "maturity TEXT NOT NULL, " +
            "abstain_reason TEXT, " +
            "updated_at_ms INTEGER NOT NULL, " +
            "PRIMARY KEY (context_key, metric))",
        "CREATE TABLE IF NOT EXISTS $TABLE_EVALUATIONS (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "history_id INTEGER NOT NULL UNIQUE REFERENCES history(id) ON DELETE CASCADE, " +
            "evaluated_at_ms INTEGER NOT NULL, " +
            "algorithm_version INTEGER NOT NULL, " +
            "profile_revision TEXT, " +
            "context_key TEXT NOT NULL, " +
            "mode TEXT NOT NULL, " +
            "deviation_level TEXT NOT NULL, " +
            "deviation_score REAL, " +
            "metrics_evaluated INTEGER NOT NULL, " +
            "metrics_total INTEGER NOT NULL, " +
            "evidence TEXT, " +
            "abstain_reason TEXT, " +
            "learned INTEGER NOT NULL DEFAULT 0, " +
            "holdout INTEGER NOT NULL DEFAULT 0)",
        "CREATE INDEX IF NOT EXISTS idx_profile_eval_time ON $TABLE_EVALUATIONS(evaluated_at_ms)",
        "CREATE TABLE IF NOT EXISTS $TABLE_MODE_EVENTS (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "at_ms INTEGER NOT NULL, " +
            "mode TEXT NOT NULL, " +
            "app_version TEXT)",
    )
}
