package com.alexisgordr.icdetector.core.profile

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** De dónde viene una muestra (procedencia guardada en los contadores del perfil). */
enum class Provenance {
    /** Historial anterior a la activación del perfil (arranque). */
    BOOTSTRAP_HISTORY,
    /** Observación nueva, después de la evaluación en sombra. */
    LIVE
}

data class ProfileSample(
    val context: ProfileContext,
    val metric: ProfileMetric,
    val value: Int?,
    val observedAtMs: Long,
    val deviceModel: String,
    val provenance: Provenance
)

enum class LearnOutcome {
    LEARNED,
    /** Valor ausente o imposible. */
    INVALID,
    /** Más antigua que [LocalProfileLearner.Config.maxSampleAgeMs]. */
    TOO_OLD,
    /** Repetida: dentro de la ventana de la anterior o fuera de orden. */
    DUPLICATE,
    /** Ya hay bastantes muestras de ese día: una sesión larga no pesa más que varios días. */
    DAILY_CAP,
    /** Muy lejos de la referencia ya formada: se cuenta, no se aprende. */
    QUARANTINED,
    /** El perfil es de otro teléfono, tecnología o algoritmo: hay que reiniciarlo. */
    INCOMPATIBLE,
    INCOMPLETE_CONTEXT
}

data class LearnResult(val profile: MetricProfile?, val outcome: LearnOutcome)

/**
 * 3.0 — Aprendizaje protegido de una referencia por contexto y métrica. Sin Android ni base de datos:
 * recibe la referencia actual y una muestra, devuelve la nueva. No mira el score, las alertas ni las
 * heurísticas del motor: no usa sus decisiones como verdad.
 *
 * Modelo deliberadamente simple: media y varianza con peso exponencial. No hay evidencia de que haga
 * falta algo más complejo, y así cada decisión se puede explicar y probar. Protecciones:
 *  - **Datos ausentes, inválidos o antiguos**: no se aprenden ([LearnOutcome.INVALID], [LearnOutcome.TOO_OLD]).
 *  - **Repeticiones**: una muestra cada [Config.dedupWindowMs] como mucho; fuera de orden, se ignora.
 *  - **Concentración en una sesión**: como mucho [Config.maxPerDay] muestras por día, y la madurez
 *    exige [Config.matureDays] días distintos.
 *  - **Cuarentena**: con referencia formada, una muestra a más de [Config.quarantineSigma]
 *    desviaciones no se aprende; se cuenta en `quarantinedSamples`.
 *  - **Actualizaciones acotadas**: el peso de una muestra nunca pasa de 1/[Config.maxWeightSamples]
 *    con referencia formada, y la media no se aleja en un día más de [Config.dailyDriftFloors] veces
 *    el ruido mínimo de la métrica desde el inicio de ese día. Una ráfaga extraña no redefine la
 *    normalidad; una contaminación lenta y sostenida durante muchos días solo se frena (limitación
 *    documentada), no se impide.
 */
class LocalProfileLearner(private val config: Config = Config()) {

    data class Config(
        val dedupWindowMs: Long = 60_000L,
        val maxSampleAgeMs: Long = 30L * DAY_MS,
        val maxPerDay: Int = 48,
        val quarantineAfter: Int = 20,
        val quarantineSigma: Double = 4.0,
        val maxWeightSamples: Int = 200,
        val dailyDriftFloors: Double = 1.0,
        val matureSamples: Int = 30,
        val matureDays: Int = 3,
        val staleAfterMs: Long = 30L * DAY_MS
    )

    fun learn(current: MetricProfile?, sample: ProfileSample, nowMs: Long): LearnResult {
        if (!sample.context.isComplete) return LearnResult(current, LearnOutcome.INCOMPLETE_CONTEXT)
        if (current != null && ProfileCompatibility.check(current, sample.deviceModel, sample.context.radio) != null) {
            return LearnResult(current, LearnOutcome.INCOMPATIBLE)
        }
        if (!sample.metric.isValid(sample.value)) return LearnResult(current, LearnOutcome.INVALID)
        if (nowMs - sample.observedAtMs > config.maxSampleAgeMs) return LearnResult(current, LearnOutcome.TOO_OLD)
        val x = sample.value!!.toDouble()
        val day = dayOf(sample.observedAtMs)

        if (current != null) {
            current.lastSampleMs?.let { last ->
                if (sample.observedAtMs - last < config.dedupWindowMs) return LearnResult(current, LearnOutcome.DUPLICATE)
            }
            if (current.lastDay == day && current.daySamples >= config.maxPerDay) {
                return LearnResult(current, LearnOutcome.DAILY_CAP)
            }
            val mean = current.mean
            if (mean != null && current.effectiveSamples >= config.quarantineAfter &&
                abs(x - mean) > config.quarantineSigma * spread(current)
            ) {
                val q = current.copy(quarantinedSamples = current.quarantinedSamples + 1, updatedAtMs = nowMs)
                return LearnResult(q.copy(maturity = maturity(q, nowMs)), LearnOutcome.QUARANTINED)
            }
        }

        val base = current ?: MetricProfile(
            contextKey = sample.context.key, metric = sample.metric, radio = sample.context.radio,
            deviceModel = sample.deviceModel, algorithmVersion = LocalProfile.ALGORITHM_VERSION, revision = 0,
            mean = null, variance = null, effectiveSamples = 0, bootstrapSamples = 0, liveSamples = 0,
            quarantinedSamples = 0, distinctDays = 0, lastDay = null, daySamples = 0, dayStartMean = null,
            firstEvidenceMs = null, lastEvidenceMs = null, lastSampleMs = null,
            maturity = ProfileMaturity.LEARNING, abstainReason = null, updatedAtMs = nowMs
        )
        val newDay = base.lastDay != day
        val dayStart = if (newDay) base.mean else base.dayStartMean
        val n = base.effectiveSamples
        val (mean, variance) = if (base.mean == null || n == 0) {
            x to sample.metric.floor * sample.metric.floor
        } else {
            val w = 1.0 / min(n + 1, config.maxWeightSamples)
            val delta = x - base.mean
            var m = base.mean + w * delta
            val v = (1 - w) * ((base.variance ?: 0.0) + w * delta * delta)
            if (dayStart != null) {
                val cap = config.dailyDriftFloors * sample.metric.floor
                m = m.coerceIn(dayStart - cap, dayStart + cap)
            }
            m to v
        }
        val learned = base.copy(
            revision = base.revision + 1,
            mean = mean,
            variance = variance,
            effectiveSamples = n + 1,
            bootstrapSamples = base.bootstrapSamples + if (sample.provenance == Provenance.BOOTSTRAP_HISTORY) 1 else 0,
            liveSamples = base.liveSamples + if (sample.provenance == Provenance.LIVE) 1 else 0,
            distinctDays = base.distinctDays + if (newDay) 1 else 0,
            lastDay = day,
            daySamples = if (newDay) 1 else base.daySamples + 1,
            dayStartMean = dayStart ?: mean,
            firstEvidenceMs = base.firstEvidenceMs?.let { min(it, sample.observedAtMs) } ?: sample.observedAtMs,
            lastEvidenceMs = max(base.lastEvidenceMs ?: sample.observedAtMs, sample.observedAtMs),
            lastSampleMs = sample.observedAtMs,
            updatedAtMs = nowMs
        )
        return LearnResult(learned.copy(maturity = maturity(learned, nowMs), abstainReason = null), LearnOutcome.LEARNED)
    }

    fun maturity(p: MetricProfile, nowMs: Long): ProfileMaturity = when {
        p.lastEvidenceMs == null || nowMs - p.lastEvidenceMs > config.staleAfterMs -> ProfileMaturity.STALE
        p.effectiveSamples >= config.matureSamples && p.distinctDays >= config.matureDays -> ProfileMaturity.MATURE
        else -> ProfileMaturity.LEARNING
    }

    /** Desviación usada para comparar: la aprendida, nunca menos que el ruido mínimo de la métrica. */
    fun spread(p: MetricProfile): Double = max(sqrt(max(p.variance ?: 0.0, 0.0)), p.metric.floor)

    companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
        fun dayOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
    }
}

/**
 * 3.0 — Arranque del perfil con el historial anterior. Solo filas con instante exacto (3.0 en
 * adelante) y de los últimos [maxAgeMs]. Se ordenan por tiempo: las primeras alimentan el perfil
 * (procedencia [Provenance.BOOTSTRAP_HISTORY]) y la parte final ([holdoutFraction]) se RESERVA para
 * evaluarlo, nunca para aprender. No se arranca con un historial restaurado de otro teléfono.
 */
object ProfileBootstrap {
    data class Row(val historyId: Long, val context: ProfileContext, val dbm: Int?, val rsrq: Int?, val observedAtMs: Long?)
    data class Plan(val learn: List<Row>, val holdout: List<Row>)

    fun plan(rows: List<Row>, nowMs: Long, restoredFromBackup: Boolean, holdoutFraction: Double = 0.2, maxAgeMs: Long = 30L * LocalProfileLearner.DAY_MS): Plan {
        if (restoredFromBackup) return Plan(emptyList(), emptyList())
        val usable = rows.filter { it.observedAtMs != null && nowMs - it.observedAtMs <= maxAgeMs && it.context.isComplete }
            .sortedWith(compareBy<Row> { it.observedAtMs }.thenBy { it.historyId })
        val holdoutCount = (usable.size * holdoutFraction).toInt()
        return Plan(usable.dropLast(holdoutCount), usable.takeLast(holdoutCount))
    }

    /**
     * Observaciones nuevas reservadas solo para evaluar (≈1 de cada 10): determinista y sin
     * aleatoriedad por instalación. Se mezcla el id antes del módulo: con `id % 10` y un ritmo de
     * escritura periódico, una celda podía caer siempre en la reserva y no aprender nunca.
     */
    fun isLiveHoldout(historyId: Long): Boolean = java.lang.Long.remainderUnsigned(mix(historyId), 10L) == 0L

    /** Finalizador de SplitMix64: constante, igual en todos los teléfonos. */
    private fun mix(x: Long): Long {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
