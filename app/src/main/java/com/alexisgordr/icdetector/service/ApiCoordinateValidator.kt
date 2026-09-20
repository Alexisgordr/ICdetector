package com.alexisgordr.icdetector.service

import android.location.Location
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.storage.CellDbHelper

/** Validates antenna coordinates returned by OpenCellID without owning verification policy. */
internal class ApiCoordinateValidator(
    private val db: CellDbHelper,
    private val currentLocation: () -> Location?,
    private val lastAcceptedLocation: () -> Location?,
    private val log: (String) -> Unit
) {
    fun isValid(lat: Double, lon: Double, cell: CellData? = null): Boolean {
        if (lat == 0.0 && lon == 0.0) return false
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return false

        if (cell != null) {
            try {
                val sharedBy = db.countDistinctAreasWithApiCoordinate(
                    lat, lon, cell.mcc, cell.mnc, cell.tac
                )
                if (sharedBy >= SENTINEL_MIN_AREAS) {
                    log("Coordenada rechazada: ya consta en $sharedBy áreas de seguimiento sin relación entre sí (valor por defecto de la API, no identifica ninguna antena).")
                    return false
                }
            } catch (_: Exception) {
                // A database failure must not bypass the independent distance barriers.
            }
        }

        val live = currentLocation()
        val reference: Location?
        val maxDistanceMeters: Float
        if (live != null) {
            reference = live
            maxDistanceMeters = LIVE_MAX_DISTANCE_METERS
        } else {
            val fallback = lastAcceptedLocation()
            val age = if (fallback != null) System.currentTimeMillis() - fallback.time else Long.MAX_VALUE
            reference = if (fallback != null && age in 0..REFERENCE_MAX_AGE_MS) fallback else null
            maxDistanceMeters = FALLBACK_MAX_DISTANCE_METERS
        }

        if (reference == null) return true

        val distance = FloatArray(1)
        Location.distanceBetween(reference.latitude, reference.longitude, lat, lon, distance)
        if (distance[0] > maxDistanceMeters) {
            log("Coordenada rechazada: a ${(distance[0] / 1000f).toInt()} km de tu posición de referencia.")
            return false
        }
        return true
    }

    private companion object {
        const val SENTINEL_MIN_AREAS = 3
        const val REFERENCE_MAX_AGE_MS = 6L * 60L * 60L * 1000L
        const val LIVE_MAX_DISTANCE_METERS = 50_000f
        const val FALLBACK_MAX_DISTANCE_METERS = 500_000f
    }
}
