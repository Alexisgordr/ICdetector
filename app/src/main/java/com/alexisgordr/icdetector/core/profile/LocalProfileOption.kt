package com.alexisgordr.icdetector.core.profile

/**
 * 3.0 — Opción experimental del perfil local (Ajustes), bajo control del usuario. Desactivada por
 * defecto; desactivarla vuelve al modo sombra al instante y siempre está permitido.
 *
 * Contrato (lo comprueban las pruebas):
 *  - El perfil puede AÑADIR información visible, nunca silenciar ni rebajar una alerta, ni saltarse
 *    la confirmación temporal: no tiene ninguna vía hacia el motor, las alertas ni los scores.
 *  - Alertas adicionales: no. Mientras [LocalProfile.ALGORITHM_VALIDATED] sea false no hay criterio
 *    defendible para alertar con él, así que la opción se limita a MOSTRAR la última evaluación.
 *  - Un perfil inmaduro, obsoleto o incompatible se abstiene y se muestra como abstención.
 *  - H1–H16, el score y la confirmación temporal siguen siendo independientes.
 *
 * Cada evaluación guarda el modo con que se hizo, y cada cambio de la opción queda en
 * `profile_mode_events`, para saber después cuándo estuvo activa y en qué modo.
 */
object LocalProfileOption {
    const val PREF_KEY = "local_profile_experimental"
    const val DEFAULT_ENABLED = false

    const val MAY_ADD_ALERTS = false
    const val MAY_SILENCE_OR_DOWNGRADE_ALERTS = false
    const val MAY_SKIP_TEMPORAL_CONFIRMATION = false

    /** Etiqueta del registro de modos cuando el usuario reinicia el perfil. */
    const val RESET_EVENT = "RESET"

    fun mode(enabled: Boolean): ProfileMode = if (enabled) ProfileMode.EXPERIMENTAL_DISPLAY else ProfileMode.SHADOW
}

/**
 * 3.0 — Lo que muestra la opción experimental, calculado con el estado ACTUAL (F08): una referencia
 * guardada como madura puede haber caducado desde entonces o ser de otro teléfono.
 */
object LocalProfileSummary {
    /** La tarjeta de Ajustes se refresca con esta frecuencia mientras está visible. */
    const val REFRESH_MS = 30_000L

    /** Referencias utilizables ahora (maduras y compatibles con este teléfono) y total guardado. */
    data class Counts(val usable: Int, val total: Int)

    fun counts(profiles: List<MetricProfile>, deviceModel: String, nowMs: Long, learner: LocalProfileLearner = LocalProfileLearner()) =
        Counts(
            usable = profiles.count {
                ProfileCompatibility.check(it, deviceModel, it.radio) == null && learner.maturity(it, nowMs) == ProfileMaturity.MATURE
            },
            total = profiles.size
        )

    /** La evaluación se hizo con el perfil que había antes del último reinicio. */
    fun fromPreviousProfile(evaluatedAtMs: Long, lastResetMs: Long?): Boolean =
        lastResetMs != null && evaluatedAtMs <= lastResetMs
}
