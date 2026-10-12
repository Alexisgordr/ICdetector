package com.alexisgordr.icdetector.core.profile

import com.alexisgordr.icdetector.models.RadioTech

/**
 * 3.0 — Perfil local personalizado: referencias estadísticas propias del entorno celular de esta
 * instalación, complementarias al motor H1–H16. Empieza en modo sombra: no cambia scores, rachas,
 * alertas, aprendizaje del motor ni decisiones forenses.
 *
 * No es un detector de IMSI catchers ni resiste evasión: dice cuánto se aparta una observación de
 * lo que ESTE teléfono suele ver en ESTE contexto. Sus niveles no están calibrados y no son
 * probabilidades de ataque.
 */
object LocalProfile {
    /**
     * Versión del algoritmo. Un perfil aprendido con otra versión se abstiene hasta reaprenderse:
     * cambiar reglas de aprendizaje o de puntuación es un corte metodológico.
     */
    const val ALGORITHM_VERSION = 1

    /**
     * La madurez del perfil (muchas muestras) NO valida el algoritmo. Mientras esto sea false, el
     * perfil solo se evalúa en sombra o se muestra; nunca interviene en decisiones. Cambiarlo exige
     * la evidencia descrita en el MANUAL, no solo perfiles maduros.
     */
    const val ALGORITHM_VALIDATED = false
}

/**
 * Métricas iniciales, pocas y justificadas: el nivel y la calidad de la señal servidora ya los usa
 * el motor (H3, H12, baseline de señal de [com.alexisgordr.icdetector.models.SignalBaseline]) y
 * están en cada fila del historial (`dbm`, `rsrq`), así que no hace falta ninguna consulta nueva.
 * La latencia no se incluye: su estado vive en memoria y depende de la red de datos, no de la celda.
 *
 * [floor]: desviación mínima que se supone (ruido normal de la medida) para no dividir por casi cero.
 */
enum class ProfileMetric(val validRange: IntRange, val floor: Double) {
    /** Nivel de la señal servidora (dBm). */
    SIGNAL_DBM(-140..-30, 3.0),
    /** Calidad de la señal (RSRQ, dB). Solo LTE/NR y solo si el módem la da. */
    SIGNAL_RSRQ(-34..3, 1.5);

    fun isValid(value: Int?): Boolean = value != null && value in validRange
}

/** Contexto de una referencia: identidad completa de la celda y tecnología. */
data class ProfileContext(
    val mcc: String,
    val mnc: String,
    val tac: String,
    val cellId: String,
    val radio: RadioTech
) {
    val key: String get() = "$mcc-$mnc-$tac-$cellId-${radio.name}"

    /** Una identidad incompleta no forma contexto: no se aprende ni se evalúa. */
    val isComplete: Boolean get() = listOf(mcc, mnc, tac, cellId).none { it.isBlank() || it == "N/A" } &&
        radio != RadioTech.UNKNOWN
}

enum class ProfileMaturity {
    /** Pocas muestras o pocos días distintos. */
    LEARNING,
    /** Suficiente evidencia reciente. NO significa que el algoritmo esté validado. */
    MATURE,
    /** Sin evidencia reciente: el entorno puede haber cambiado. */
    STALE
}

/** Por qué una evaluación no da un nivel. */
enum class AbstainReason {
    INCOMPLETE_CONTEXT,
    NO_PROFILE,
    PROFILE_IMMATURE,
    PROFILE_STALE,
    /** El perfil lo aprendió otro modelo de teléfono (p. ej. copia restaurada en otro móvil). */
    INCOMPATIBLE_DEVICE,
    INCOMPATIBLE_RADIO,
    /** Aprendido con otra versión del algoritmo. */
    INCOMPATIBLE_ALGORITHM,
    /** Ninguna métrica del perfil está disponible y es válida en esta observación. */
    NO_COMPATIBLE_METRIC
}

/** Referencia de una métrica en un contexto (una fila de `local_profile`). */
data class MetricProfile(
    val contextKey: String,
    val metric: ProfileMetric,
    val radio: RadioTech,
    val deviceModel: String,
    val algorithmVersion: Int,
    val revision: Long,
    val mean: Double?,
    val variance: Double?,
    val effectiveSamples: Int,
    val bootstrapSamples: Int,
    val liveSamples: Int,
    val quarantinedSamples: Int,
    val distinctDays: Int,
    val lastDay: String?,
    val daySamples: Int,
    val dayStartMean: Double?,
    val firstEvidenceMs: Long?,
    val lastEvidenceMs: Long?,
    val lastSampleMs: Long?,
    val maturity: ProfileMaturity,
    val abstainReason: AbstainReason?,
    val updatedAtMs: Long
)

/**
 * Compatibilidad de un perfil con el teléfono, la tecnología y el algoritmo actuales.
 *
 *  - **Modelo de dispositivo**: cada módem mide y redondea distinto. Un perfil aprendido en otro
 *    modelo (por ejemplo, tras restaurar una copia en otro teléfono) queda inactivo hasta que se
 *    reinicie y se vuelva a aprender aquí.
 *  - **Tecnología de radio**: forma parte del contexto; LTE y NR de la misma celda son perfiles
 *    distintos. Una discrepancia se trata como incompatible.
 *  - **Datos disponibles**: una métrica que este teléfono no da (RSRQ en 2G/3G) no se evalúa; se
 *    refleja en la cobertura, no en el nivel.
 *  - **Versión del algoritmo**: otra versión, incompatible.
 */
object ProfileCompatibility {
    fun check(
        profile: MetricProfile,
        deviceModel: String,
        radio: RadioTech,
        algorithmVersion: Int = LocalProfile.ALGORITHM_VERSION
    ): AbstainReason? = when {
        profile.algorithmVersion != algorithmVersion -> AbstainReason.INCOMPATIBLE_ALGORITHM
        profile.deviceModel != deviceModel -> AbstainReason.INCOMPATIBLE_DEVICE
        profile.radio != radio -> AbstainReason.INCOMPATIBLE_RADIO
        else -> null
    }

    /** Identificador del modelo, estable y sin datos personales (no incluye número de serie). */
    fun deviceModel(manufacturer: String?, model: String?): String =
        "${manufacturer.orEmpty().trim()}/${model.orEmpty().trim()}".lowercase()
}

/** Con qué modo se hizo una evaluación (ver [ProfileModeEvent]). */
enum class ProfileMode {
    /** Se evalúa y se guarda, sin mostrarse ni intervenir. Por defecto. */
    SHADOW,
    /** Opción experimental activada: además se muestra al usuario. No interviene en alertas. */
    EXPERIMENTAL_DISPLAY
}

/** Nivel de desviación respecto a la referencia. No calibrado: no es una probabilidad. */
enum class DeviationLevel { NONE, LOW, MODERATE, HIGH, ABSTAIN }

/**
 * Resultado de una evaluación, ligado a UNA fila del historial ([historyId], único). Se guarda en
 * `profile_evaluations`; nunca modifica la fila original.
 */
data class ProfileEvaluationRecord(
    val historyId: Long,
    val evaluatedAtMs: Long,
    val algorithmVersion: Int,
    /** Revisión de cada referencia usada, p. ej. `SIGNAL_DBM:42;SIGNAL_RSRQ:17`. */
    val profileRevision: String?,
    val contextKey: String,
    val mode: ProfileMode,
    val level: DeviationLevel,
    /** Mayor desviación robusta (en desviaciones típicas) entre las métricas evaluadas. */
    val score: Double?,
    val metricsEvaluated: Int,
    val metricsTotal: Int,
    val evidence: String?,
    val abstainReason: AbstainReason?,
    /** La observación se usó después para aprender. */
    val learned: Boolean,
    /** Reservada solo para evaluar (nunca se aprende de ella). */
    val holdout: Boolean
)
