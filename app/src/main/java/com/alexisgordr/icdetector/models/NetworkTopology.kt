package com.alexisgordr.icdetector.models

/** Ruta agregada entre dos celdas servidoras observadas por el dispositivo. */
data class CellTransitionSummary(
    val fromIdentity: String,
    val toIdentity: String,
    val observations: Int,
    val trustedObservations: Int,
    val lastStatus: HeuristicStatus,
    val lastSeenMs: Long,
    val tripCount: Int = 0,
    val lastTripId: String? = null,
    val mobilityFirstSeenMs: Long? = null,
    val mobilityLastSeenMs: Long? = null,
    /** 3.0 (UI2) — Último PASSED/FAILED y su fecha; null si no se conoce (ver SchemaV25). */
    val lastEvaluatedStatus: HeuristicStatus? = null,
    val lastEvaluatedMs: Long? = null,
    /** 3.0 (UI2) — Motivo de la última observación si fue N/A; null si no se guardó. */
    val lastExplanation: String? = null,
    /** 3.0 (UI2) — FAILED registrados desde el esquema 25. */
    val failedCount: Int = 0,
    /** 3.0 (UI2) — Qué se sabe de los fallos anteriores al esquema 25. */
    val failureHistory: RouteFailureHistory = RouteFailureHistory.COMPLETE
) {
    val trustRatio: Float
        get() = if (observations <= 0) 0f else trustedObservations.toFloat() / observations
}

/** 3.0 (UI2) — Qué se sabe de los fallos de una ruta anteriores al esquema 25 (ver SchemaV25). */
enum class RouteFailureHistory {
    /** Seguida desde que se creó (o sin handovers antes): `failedCount` es el total. */
    COMPLETE,
    /** Su última observación antes del esquema 25 fue FAILED: al menos un fallo, número desconocido. */
    LEGACY_FAILED,
    /** Su última observación antes del esquema 25 fue PASSED o N/A: no se sabe si falló antes. */
    LEGACY_UNKNOWN;

    companion object {
        fun fromStored(value: String?): RouteFailureHistory = when (value) {
            null -> COMPLETE
            LEGACY_FAILED.name -> LEGACY_FAILED
            else -> LEGACY_UNKNOWN          // valor desconocido: lo prudente es no afirmar nada
        }
    }
}
