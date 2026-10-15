package com.alexisgordr.icdetector.storage

/**
 * 3.0 — Migración a esquema 24 (L2). Aditiva: identidad de la OBSERVACIÓN, compartida por todas
 * las filas que se guardan de una misma lectura (p. ej. la fila periódica o de handover y la de
 * alarma confirmada del mismo ciclo).
 *
 *  - `history.observation_id`: identificador generado al recibir la lectura (UUID). Todas las filas
 *    de la misma observación llevan el mismo; filas anteriores, NULL.
 *  - `profile_evaluations.observation_id` con índice ÚNICO: el perfil local evalúa, aprende y
 *    cuenta cada observación una sola vez, también tras reiniciar o restaurar (no depende de la
 *    memoria). La evaluación sigue enlazada a la primera fila guardada (`history_id`); las demás
 *    filas se relacionan con ella por `observation_id`. En SQLite los NULL no chocan en un índice
 *    único: las evaluaciones anteriores se conservan tal cual.
 *
 * No borra ni reescribe nada. Sin dependencias de Android, como [SchemaV23].
 */
object SchemaV24 {

    const val VERSION = 24

    /** Sentencias en orden; ver [CellDbHelper.onUpgrade]. */
    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE history ADD COLUMN observation_id TEXT",
        "ALTER TABLE ${SchemaV22.TABLE_EVALUATIONS} ADD COLUMN observation_id TEXT",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_eval_observation ON ${SchemaV22.TABLE_EVALUATIONS}(observation_id)",
        "CREATE INDEX IF NOT EXISTS idx_history_observation ON history(observation_id)"
    )
}
