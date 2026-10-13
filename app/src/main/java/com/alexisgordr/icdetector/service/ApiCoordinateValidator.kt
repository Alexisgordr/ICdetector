package com.alexisgordr.icdetector.service

import android.location.Location
import android.os.SystemClock
import com.alexisgordr.icdetector.core.ApiCoordinateCheck
import com.alexisgordr.icdetector.core.CoordinateReference
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.storage.CellDbHelper

/**
 * Validates antenna coordinates returned by OpenCellID without owning verification policy.
 * 3.0 (V3) — The decision lives in [ApiCoordinateCheck]; the fallback age is monotonic.
 */
internal class ApiCoordinateValidator(
    private val db: CellDbHelper,
    private val currentLocation: () -> Location?,
    private val lastAcceptedLocation: () -> Location?,
    private val log: (String) -> Unit,
    private val elapsedNowMs: () -> Long = SystemClock::elapsedRealtime
) {
    fun check(lat: Double, lon: Double, cell: CellData? = null): ApiCoordinateCheck.Outcome {
        val shared = cell?.let {
            try {
                db.countDistinctAreasWithApiCoordinate(lat, lon, it.mcc, it.mnc, it.tac)
            } catch (_: Exception) {
                null // A database failure must not bypass the independent distance barriers.
            }
        }
        val outcome = ApiCoordinateCheck.check(
            lat, lon, shared,
            live = currentLocation()?.toReference(),
            recent = lastAcceptedLocation()?.toReference(),
            nowElapsedMs = elapsedNowMs()
        )
        when (outcome.reason) {
            ApiCoordinateCheck.Reason.SHARED_ACROSS_AREAS ->
                log("Coordenada rechazada: ya consta en $shared áreas de seguimiento sin relación entre sí (valor por defecto de la API, no identifica ninguna antena).")
            ApiCoordinateCheck.Reason.TOO_FAR_FROM_LIVE, ApiCoordinateCheck.Reason.TOO_FAR_FROM_RECENT ->
                log("Coordenada rechazada: a ${((outcome.distanceM ?: 0.0) / 1000).toInt()} km de tu posición de referencia.")
            else -> Unit
        }
        return outcome
    }

    /** elapsedRealtimeNanos 0 = sin tiempo monotónico (p. ej. restaurada de otro arranque). */
    private fun Location.toReference() = CoordinateReference(
        latitude, longitude, (elapsedRealtimeNanos / 1_000_000L).takeIf { it > 0L }
    )
}
