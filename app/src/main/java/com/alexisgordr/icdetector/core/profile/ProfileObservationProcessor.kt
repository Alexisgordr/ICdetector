package com.alexisgordr.icdetector.core.profile

/**
 * 3.0 (L2) — Una evaluación del perfil local por OBSERVACIÓN, aunque la observación se guarde en
 * varias filas del historial (p. ej. la periódica o de handover y la de alarma confirmada del mismo
 * ciclo, que comparten [LocalProfileShadow.Observation.observationId]).
 *
 * Antes cada fila llamaba al perfil: la segunda se evaluaba contra una referencia que ya contenía la
 * primera y contaba dos veces en las estadísticas. Ahora, dentro de la MISMA transacción que la
 * evaluación y el aprendizaje:
 *  - si la observación ya tiene evaluación guardada, no se evalúa, no se aprende y no se cuenta: la
 *    fila nueva queda relacionada con esa evaluación por su `observation_id`;
 *  - si no, se evalúa contra la referencia anterior, se aprende como mucho una vez (o nada si es una
 *    observación reservada; la reserva depende de la observación, no de la fila) y se guarda la
 *    evaluación con su `observation_id`. Si esa escritura falla, se lanza y la transacción entera se
 *    deshace: ni evaluación ni aprendizaje, y la siguiente fila de la observación la evalúa.
 * La comprobación usa lo guardado (índice único en `profile_evaluations.observation_id`), no la
 * memoria: vale igual tras reiniciar el proceso o restaurar una copia. Filas sin identidad
 * (anteriores al esquema 24) se tratan una a una, como antes.
 */
class ProfileObservationProcessor(private val shadow: LocalProfileShadow = LocalProfileShadow(LocalProfileLearner())) {

    /** Lo que el procesador necesita de la base, dentro de una transacción abierta por quien llama. */
    interface Store {
        fun hasEvaluation(observationId: String): Boolean
        fun profiles(contextKey: String): Map<ProfileMetric, MetricProfile>
        fun upsert(profile: MetricProfile)
        /** Id de la evaluación guardada, o -1 si no se guardó. */
        fun insertEvaluation(record: ProfileEvaluationRecord): Long
    }

    enum class Outcome {
        /** Primera fila de la observación: evaluada (y aprendida si procede). */
        EVALUATED,
        /** Otra fila de una observación ya evaluada: nada que evaluar, aprender ni contar. */
        ALREADY_EVALUATED
    }

    fun onRow(store: Store, obs: LocalProfileShadow.Observation, mode: ProfileMode, nowMs: Long): Outcome {
        obs.observationId?.let { if (store.hasEvaluation(it)) return Outcome.ALREADY_EVALUATED }
        val current = if (obs.context.isComplete) store.profiles(obs.context.key) else emptyMap()
        val step = shadow.process(current, obs, mode, nowMs)
        step.changed.forEach(store::upsert)
        // F10 — Si la evaluación no se guarda, tampoco el aprendizaje: quien llama deshace la transacción.
        check(store.insertEvaluation(step.record) != -1L) { "profile evaluation not written" }
        return Outcome.EVALUATED
    }
}
