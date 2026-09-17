package com.alexisgordr.icdetector.models

/** Punto GPS histórico del dispositivo mientras observaba una celda. */
data class CellLocationSample(val latitude: Double, val longitude: Double)

/** Evidencia calculada para H16 al producirse un handover real. */
data class TransitionCoherenceResult(
    val status: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val explanation: String = "N/A: todavía no se ha observado un handover evaluable.",
    val fromIdentity: String? = null,
    val toIdentity: String? = null,
    val centerDistanceMeters: Int? = null,
    val deviceDistanceMeters: Int? = null,
    val priorTrustedTransitions: Int = 0,
    /** Solo evidencia con baselines maduros puede enseñar una ruta al modelo local. */
    val eligibleForLearning: Boolean = false
)
