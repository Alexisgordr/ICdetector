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
    /** Fechada después de ahora (más allá de [LocalProfileLearner.Config.clockToleranceMs]). */
    FUTURE,
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
 *    con referencia formada; el residuo que entra en la media y la varianza se recorta a
 *    [Config.residualClipSigma] dispersiones; la media no se aleja en un día más de
 *    [Config.dailyDriftFloors] veces el ruido mínimo de la métrica desde el inicio de ese día, y la
 *    dispersión no crece en un día más de [Config.dailySpreadGrowthFloors] ruidos mínimos. Una ráfaga
 *    extraña no redefine la normalidad ni la vuelve permisiva; una contaminación lenta y sostenida
 *    durante muchos días solo se frena (limitación documentada), no se impide. Coste: una celda
 *    realmente más ruidosa tarda varios días en reflejar toda su dispersión.
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
        /** Residuo máximo, en dispersiones, que entra en la media y la varianza (Huber). */
        val residualClipSigma: Double = 2.5,
        /** Cuánto puede crecer la dispersión en un día, en veces el ruido mínimo de la métrica. */
        val dailySpreadGrowthFloors: Double = 1.0,
        val matureSamples: Int = 30,
        val matureDays: Int = 3,
        val staleAfterMs: Long = 30L * DAY_MS,
        /** Margen para relojes ligeramente adelantados; más allá, una fecha futura se rechaza. */
        val clockToleranceMs: Long = CLOCK_TOLERANCE_MS
    )

    fun learn(stored: MetricProfile?, sample: ProfileSample, nowMs: Long): LearnResult {
        if (!sample.context.isComplete) return LearnResult(stored, LearnOutcome.INCOMPLETE_CONTEXT)
        // Una referencia de otra versión del algoritmo no se mezcla con la actual: se reaprende
        // desde cero, con una generación nueva. Otro teléfono, en cambio, exige reiniciar a mano.
        // F06 — Una referencia con fechas futuras (el reloj iba adelantado y luego se corrigió, o una
        // copia de otro reloj) bloquearía todo lo posterior como repetido: se rehace desde cero.
        val sameAlgorithm = stored?.takeIf { it.algorithmVersion == LocalProfile.ALGORITHM_VERSION }
            ?.takeUnless { p -> listOfNotNull(p.lastSeenMs, p.lastSampleMs, p.lastEvidenceMs).any { it > nowMs + config.clockToleranceMs } }
        // F05 — Revalidación tras caducar: si entre la última evidencia y esta muestra pasaron más
        // de [Config.staleAfterMs], la referencia antigua no se reactiva con los contadores de
        // entonces (una sola muestra la devolvía a MATURE). Empieza una generación nueva, en
        // aprendizaje, que necesita otra vez [Config.matureSamples] muestras en [Config.matureDays] días.
        val current = sameAlgorithm?.takeUnless { p ->
            p.lastEvidenceMs != null && sample.observedAtMs - p.lastEvidenceMs > config.staleAfterMs
        }
        if (current != null && ProfileCompatibility.check(current, sample.deviceModel, sample.context.radio) != null) {
            return LearnResult(current, LearnOutcome.INCOMPATIBLE)
        }
        if (!sample.metric.isValid(sample.value)) return LearnResult(stored, LearnOutcome.INVALID)
        if (nowMs - sample.observedAtMs > config.maxSampleAgeMs) return LearnResult(stored, LearnOutcome.TOO_OLD)
        if (sample.observedAtMs - nowMs > config.clockToleranceMs) return LearnResult(stored, LearnOutcome.FUTURE)
        val x = sample.value!!.toDouble()
        val day = dayOf(sample.observedAtMs)

        if (current != null) {
            // F09 — Se compara con la última muestra PROCESADA (aprendida o en cuarentena): repetir
            // una lectura en cuarentena ya no vuelve a sumar. La caducidad sigue usando solo la
            // evidencia aprendida (lastEvidenceMs).
            listOfNotNull(current.lastSeenMs, current.lastSampleMs).maxOrNull()?.let { last ->
                if (sample.observedAtMs - last < config.dedupWindowMs) return LearnResult(current, LearnOutcome.DUPLICATE)
            }
            if (current.lastDay == day && current.daySamples >= config.maxPerDay) {
                return LearnResult(current, LearnOutcome.DAILY_CAP)
            }
            val mean = current.mean
            if (mean != null && current.effectiveSamples >= config.quarantineAfter &&
                abs(x - mean) > config.quarantineSigma * spread(current)
            ) {
                val q = current.copy(quarantinedSamples = current.quarantinedSamples + 1, lastSeenMs = sample.observedAtMs, updatedAtMs = nowMs)
                return LearnResult(q.copy(maturity = maturity(q, nowMs)), LearnOutcome.QUARANTINED)
            }
        }

        val base = current ?: MetricProfile(
            contextKey = sample.context.key, metric = sample.metric, radio = sample.context.radio,
            deviceModel = sample.deviceModel, algorithmVersion = LocalProfile.ALGORITHM_VERSION, revision = 0,
            mean = null, variance = null, effectiveSamples = 0, bootstrapSamples = 0, liveSamples = 0,
            quarantinedSamples = 0, distinctDays = 0, lastDay = null, daySamples = 0, dayStartMean = null,
            firstEvidenceMs = null, lastEvidenceMs = null, lastSampleMs = null,
            maturity = ProfileMaturity.LEARNING, abstainReason = null, updatedAtMs = nowMs,
            generation = MetricProfile.generationAt(nowMs)
        )
        val newDay = base.lastDay != day
        val dayStart = if (newDay) base.mean else base.dayStartMean
        val dayStartVariance = if (newDay) base.variance else base.dayStartVariance
        val n = base.effectiveSamples
        val floor = sample.metric.floor
        val (mean, variance) = if (base.mean == null || n == 0) {
            x to floor * floor
        } else {
            val w = 1.0 / min(n + 1, config.maxWeightSamples)
            // F02 — Residuo recortado: una muestra lejana (pero bajo la cuarentena) no puede inflar
            // la varianza de golpe. Sin esto, 48 muestras de un día llevaban la dispersión de 3 a
            // 22 dB y una lectura antes HIGH pasaba a NONE.
            val clip = config.residualClipSigma * spread(base)
            val delta = (x - base.mean).coerceIn(-clip, clip)
            var m = base.mean + w * delta
            var v = (1 - w) * ((base.variance ?: 0.0) + w * delta * delta)
            if (dayStart != null) {
                val cap = config.dailyDriftFloors * floor
                m = m.coerceIn(dayStart - cap, dayStart + cap)
            }
            // Y la dispersión crece como mucho un ruido mínimo al día (bajar, siempre puede).
            if (dayStartVariance != null) {
                val maxSd = sqrt(max(dayStartVariance, floor * floor)) + config.dailySpreadGrowthFloors * floor
                v = min(v, maxSd * maxSd)
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
            dayStartVariance = dayStartVariance ?: variance,
            firstEvidenceMs = base.firstEvidenceMs?.let { min(it, sample.observedAtMs) } ?: sample.observedAtMs,
            lastEvidenceMs = max(base.lastEvidenceMs ?: sample.observedAtMs, sample.observedAtMs),
            lastSampleMs = sample.observedAtMs,
            lastSeenMs = sample.observedAtMs,
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
        const val CLOCK_TOLERANCE_MS = 5L * 60L * 1000L
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
    /** [observationId] (esquema 24): filas de la misma observación lo comparten; null en filas antiguas. */
    data class Row(val historyId: Long, val context: ProfileContext, val dbm: Int?, val rsrq: Int?, val observedAtMs: Long?,
                   val observationId: String? = null)
    data class Plan(val learn: List<Row>, val holdout: List<Row>)

    fun plan(rows: List<Row>, nowMs: Long, restoredFromBackup: Boolean, holdoutFraction: Double = 0.2, maxAgeMs: Long = 30L * LocalProfileLearner.DAY_MS): Plan {
        if (restoredFromBackup) return Plan(emptyList(), emptyList())
        val usable = rows.filter {
            it.observedAtMs != null && nowMs - it.observedAtMs <= maxAgeMs &&
                it.observedAtMs - nowMs <= LocalProfileLearner.CLOCK_TOLERANCE_MS && it.context.isComplete
        }
            .sortedWith(compareBy<Row> { it.observedAtMs }.thenBy { it.historyId })
            .let(::onePerObservation)
        val holdoutCount = (usable.size * holdoutFraction).toInt()
        if (holdoutCount == 0) return Plan(usable, emptyList())
        // Corte por instante: filas del mismo instante (una misma observación) nunca quedan a los
        // dos lados; todas van a la reserva (F03).
        val cutoff = usable[usable.size - holdoutCount].observedAtMs!!
        return Plan(usable.filter { it.observedAtMs!! < cutoff }, usable.filter { it.observedAtMs!! >= cutoff })
    }

    /**
     * 3.0 (L2) — Una fila por observación (la primera guardada): la observación se aprende o se
     * evalúa una vez aunque tenga varias filas. Las filas sin identidad (anteriores al esquema 24)
     * se mantienen una a una, como antes.
     */
    fun onePerObservation(rows: List<Row>): List<Row> {
        val seen = HashSet<String>()
        return rows.filter { r -> r.observationId == null || seen.add(r.observationId) }
    }

    /**
     * Observaciones nuevas reservadas solo para evaluar (≈1 de cada 10): deterministas y sin
     * aleatoriedad por instalación. Se mezcla antes del módulo para no seguir el ritmo de escritura.
     *
     * F03 — Se decide por la OBSERVACIÓN (contexto e instante de captura), no por la fila: una misma
     * lectura puede guardarse en varias filas (periódica y alarma confirmada del mismo ciclo). Por
     * id de fila, una se aprendía y otra quedaba reservada pero se evaluaba contra una referencia
     * que ya contenía esa lectura. Ahora todas las filas de una observación comparten la decisión.
     */
    fun isLiveHoldout(contextKey: String, observedAtMs: Long): Boolean =
        java.lang.Long.remainderUnsigned(mix(observedAtMs * 31L + contextKey.hashCode()), 10L) == 0L

    /** Finalizador de SplitMix64: constante, igual en todos los teléfonos. */
    private fun mix(x: Long): Long {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
