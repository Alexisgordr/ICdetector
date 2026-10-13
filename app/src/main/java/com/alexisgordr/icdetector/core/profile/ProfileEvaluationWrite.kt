package com.alexisgordr.icdetector.core.profile

/**
 * 3.0 (L3) — Resultado de guardar una evaluación con `INSERT ... OR IGNORE`.
 *
 * `profile_evaluations` admite una sola evaluación por fila del historial (`history_id` único) y,
 * desde el esquema 24, por observación (`observation_id` único). Si una versión futura del algoritmo
 * vuelve a arrancar el perfil, sus evaluaciones reservadas de observaciones que ya tienen una de la
 * versión anterior no se pueden guardar: el `OR IGNORE` las descarta sin error. Eso es un conflicto
 * ESPERADO (la evaluación anterior se conserva, no se reemplaza). En cambio, que no se guarde y
 * tampoco exista ninguna evaluación de esa fila u observación es un FALLO de escritura real (un
 * `OR IGNORE` también descarta en silencio, p. ej., una restricción NOT NULL), y no puede darse por
 * bueno.
 */
object ProfileEvaluationWrite {
    enum class Outcome {
        INSERTED,
        /** Ya había una evaluación de esa fila u observación (de esta versión o de otra): se conserva. */
        ALREADY_EVALUATED,
        /** No se guardó y no hay ninguna: fallo real. */
        FAILED
    }

    /** ¿Hay ya una evaluación de esta fila o de esta observación? Parámetros: history_id, observation_id. */
    const val EXISTS_SQL =
        "SELECT 1 FROM profile_evaluations WHERE history_id = CAST(? AS INTEGER) OR (observation_id IS NOT NULL AND observation_id = ?) LIMIT 1"

    fun outcome(insertedRowId: Long, alreadyStored: Boolean): Outcome = when {
        insertedRowId != -1L -> Outcome.INSERTED
        alreadyStored -> Outcome.ALREADY_EVALUATED
        else -> Outcome.FAILED
    }
}
