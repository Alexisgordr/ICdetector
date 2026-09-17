package com.alexisgordr.icdetector.models

/** Progreso de la confirmación temporal de una anomalía. */
data class TemporalProgress(
    val phase: Int = 0,
    val required: Int = 3
) {
    val active: Boolean get() = phase > 0
    val confirmed: Boolean get() = phase >= required
    val label: String get() = if (active) "${phase.coerceAtMost(required)}/$required" else "INACTIVA"
}

/** Explicación observable de por qué una regla se evaluó, falló o se abstuvo. */
data class HeuristicDiagnostic(
    val id: Int,
    val name: String,
    val status: HeuristicStatus,
    val explanation: String
)

enum class BaselineLevel { EMPTY, LEARNING, USABLE, MATURE }

/** Madurez de cada fuente histórica usada por las heurísticas. */
data class BaselineMaturity(
    val signalSamples: Int = 0,
    val fingerprintSamples: Int = 0,
    val rfIdentitySamples: Int = 0,
    val reputationSamples: Int = 0
) {
    private fun level(samples: Int, usableAt: Int, matureAt: Int): BaselineLevel = when {
        samples <= 0 -> BaselineLevel.EMPTY
        samples < usableAt -> BaselineLevel.LEARNING
        samples < matureAt -> BaselineLevel.USABLE
        else -> BaselineLevel.MATURE
    }

    val signalLevel get() = level(signalSamples, usableAt = 5, matureAt = 20)
    val fingerprintLevel get() = level(fingerprintSamples, usableAt = 30, matureAt = 60)
    val rfIdentityLevel get() = level(rfIdentitySamples, usableAt = 4, matureAt = 20)
    val reputationLevel get() = level(reputationSamples, usableAt = 5, matureAt = 20)
}

enum class IncidentState { OBSERVING, CONFIRMED, RECOVERED, INTERRUPTED }

/** Registro forense de un episodio, separado del histórico periódico de antenas. */
data class IncidentRecord(
    val id: Long,
    val startedAt: String,
    val updatedAt: String,
    val endedAt: String?,
    val identity: String,
    val cid: String,
    val radio: RadioTech,
    val state: IncidentState,
    val highestPhase: Int,
    val requiredPhases: Int,
    val score: Int,
    val anomalyConfidence: Float,
    val reason: String,
    val heuristicSnapshot: String
)

