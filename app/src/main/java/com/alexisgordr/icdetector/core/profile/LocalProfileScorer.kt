package com.alexisgordr.icdetector.core.profile

import java.util.Locale
import kotlin.math.abs

/**
 * 3.0 — Evaluación de una observación contra el perfil local. Pura: sin Android ni base de datos.
 *
 * Desviación robusta por métrica: |x − media| / dispersión, donde la dispersión nunca es menor que
 * el ruido mínimo de la métrica. El nivel sale de la mayor desviación entre las métricas evaluadas.
 * Los cortes (2, 3, 4) son convenciones fijas, NO están calibrados: un nivel HIGH no es una
 * probabilidad de ataque ni una alerta.
 *
 * Se abstiene (nivel [DeviationLevel.ABSTAIN] con motivo) si el contexto está incompleto, si no hay
 * perfil, o si ninguna métrica tiene un perfil maduro, compatible y un valor válido.
 */
object LocalProfileScorer {
    const val LOW_AT = 2.0
    const val MODERATE_AT = 3.0
    const val HIGH_AT = 4.0

    data class Result(
        val level: DeviationLevel,
        val score: Double?,
        val metricsEvaluated: Int,
        val metricsTotal: Int,
        val evidence: String?,
        val abstainReason: AbstainReason?,
        /**
         * Generación y revisión de cada referencia evaluada, p. ej.
         * `SIGNAL_DBM@g1790000000000:42;SIGNAL_RSRQ@g1790000000000:17`.
         */
        val revision: String?
    )

    /** Si ninguna métrica se evalúa, el motivo más informativo (incompatibilidad antes que falta de datos). */
    private val PRIORITY = listOf(
        AbstainReason.INCOMPATIBLE_ALGORITHM, AbstainReason.INCOMPATIBLE_DEVICE, AbstainReason.INCOMPATIBLE_RADIO,
        AbstainReason.PROFILE_STALE, AbstainReason.PROFILE_IMMATURE, AbstainReason.NO_PROFILE,
        AbstainReason.NO_COMPATIBLE_METRIC
    )

    fun evaluate(
        profiles: Map<ProfileMetric, MetricProfile>,
        context: ProfileContext,
        values: Map<ProfileMetric, Int?>,
        deviceModel: String,
        nowMs: Long,
        learner: LocalProfileLearner = LocalProfileLearner()
    ): Result {
        val total = ProfileMetric.entries.size
        if (!context.isComplete) return abstain(AbstainReason.INCOMPLETE_CONTEXT, total)
        if (profiles.isEmpty()) return abstain(AbstainReason.NO_PROFILE, total)

        val reasons = mutableListOf<AbstainReason>()
        val evaluated = mutableListOf<Triple<MetricProfile, Int, Double>>()
        for (metric in ProfileMetric.entries) {
            val p = profiles[metric]
            val value = values[metric]
            val reason = when {
                p == null -> AbstainReason.NO_PROFILE
                else -> ProfileCompatibility.check(p, deviceModel, context.radio)
                    ?: when (learner.maturity(p, nowMs)) {
                        ProfileMaturity.STALE -> AbstainReason.PROFILE_STALE
                        ProfileMaturity.LEARNING -> AbstainReason.PROFILE_IMMATURE
                        ProfileMaturity.MATURE -> if (!metric.isValid(value) || p.mean == null) AbstainReason.NO_COMPATIBLE_METRIC else null
                    }
            }
            if (reason != null || p == null) { reasons += reason ?: AbstainReason.NO_PROFILE; continue }
            evaluated += Triple(p, value!!, abs(value - p.mean!!) / learner.spread(p))
        }
        if (evaluated.isEmpty()) return abstain(PRIORITY.first { it in reasons }, total)

        val score = evaluated.maxOf { it.third }
        val level = when {
            score < LOW_AT -> DeviationLevel.NONE
            score < MODERATE_AT -> DeviationLevel.LOW
            score < HIGH_AT -> DeviationLevel.MODERATE
            else -> DeviationLevel.HIGH
        }
        val evidence = evaluated.joinToString("; ") { (p, v, z) ->
            // Parámetros con 3 decimales: suficientes para repetir el cálculo de la desviación.
            String.format(Locale.ROOT, "%s %d vs %.3f±%.3f z=%.3f", p.metric.name, v, p.mean, learner.spread(p), z)
        }
        val revision = evaluated.joinToString(";") { revisionOf(it.first) }
        return Result(level, round2(score), evaluated.size, total, evidence, null, revision)
    }

    fun revisionOf(p: MetricProfile) = "${p.metric.name}@${p.generation}:${p.revision}"

    private fun abstain(reason: AbstainReason, total: Int) =
        Result(DeviationLevel.ABSTAIN, null, 0, total, null, reason, null)

    private fun round2(x: Double) = Math.round(x * 100.0) / 100.0
}

/**
 * 3.0 — Un paso del perfil local para UNA fila ya guardada del historial. Orden prequential: primero
 * se evalúa con la referencia anterior y después, si la fila no está reservada para evaluación, se
 * aprende de ella. Así una observación nunca se evalúa contra un perfil que ya la contiene.
 *
 * No recibe el score, las alertas ni las heurísticas del motor: no puede usarlos como etiqueta ni
 * cambiarlos.
 */
class LocalProfileShadow(private val learner: LocalProfileLearner = LocalProfileLearner()) {

    data class Observation(
        val historyId: Long,
        val context: ProfileContext,
        val dbm: Int?,
        val rsrq: Int?,
        val observedAtMs: Long,
        val deviceModel: String
    ) {
        val values: Map<ProfileMetric, Int?> get() = mapOf(ProfileMetric.SIGNAL_DBM to dbm, ProfileMetric.SIGNAL_RSRQ to rsrq)
    }

    /** [changed]: referencias a guardar (aprendidas o con el contador de cuarentena actualizado). */
    data class Step(val record: ProfileEvaluationRecord, val changed: List<MetricProfile>)

    fun process(
        current: Map<ProfileMetric, MetricProfile>,
        obs: Observation,
        mode: ProfileMode,
        nowMs: Long,
        holdout: Boolean = ProfileBootstrap.isLiveHoldout(obs.context.key, obs.observedAtMs),
        provenance: Provenance = Provenance.LIVE
    ): Step {
        val r = LocalProfileScorer.evaluate(current, obs.context, obs.values, obs.deviceModel, nowMs, learner)
        val changed = if (holdout) emptyList() else learn(current, obs, provenance, nowMs)
        val learned = changed.any { p -> current[p.metric]?.revision != p.revision }
        val record = ProfileEvaluationRecord(
            historyId = obs.historyId, evaluatedAtMs = nowMs, algorithmVersion = LocalProfile.ALGORITHM_VERSION,
            profileRevision = r.revision, contextKey = obs.context.key, mode = mode, level = r.level, score = r.score,
            metricsEvaluated = r.metricsEvaluated, metricsTotal = r.metricsTotal, evidence = r.evidence,
            abstainReason = r.abstainReason, learned = learned, holdout = holdout
        )
        return Step(record, changed)
    }

    /** Solo aprende; para el arranque con historial, donde las filas de aprendizaje no se evalúan. */
    fun learn(current: Map<ProfileMetric, MetricProfile>, obs: Observation, provenance: Provenance, nowMs: Long): List<MetricProfile> =
        ProfileMetric.entries.mapNotNull { metric ->
            val before = current[metric]
            val after = learner.learn(before, ProfileSample(obs.context, metric, obs.values[metric], obs.observedAtMs, obs.deviceModel, provenance), nowMs).profile
            after?.takeIf { it != before }
        }
}
