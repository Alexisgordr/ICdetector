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
    val mobilityLastSeenMs: Long? = null
) {
    val trustRatio: Float
        get() = if (observations <= 0) 0f else trustedObservations.toFloat() / observations
}
