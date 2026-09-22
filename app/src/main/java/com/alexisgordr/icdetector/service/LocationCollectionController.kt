package com.alexisgordr.icdetector.service

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.alexisgordr.icdetector.core.GpsFixContinuity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the complete GNSS lifecycle: continuous GPS subscription, bounded one-shot fixes,
 * continuity validation, persisted reference state and listener cleanup.
 *
 * Network-derived location is deliberately excluded because it would use the cellular
 * infrastructure that the detector is trying to assess.
 */
internal class LocationCollectionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val onStreamFixAvailable: (Location) -> Unit,
    private val onPreciseFixAccepted: (Location) -> Unit
) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val continuity = GpsFixContinuity()
    private val forcedFixLock = Any()

    private var streamActive = false
    private var forcedFixListener: LocationListener? = null
    private var lastForcedFixTime = 0L
    private var lastAcceptedLocation: Location? = null
    private var lastPersistedLocationTime = 0L
    private var awaitingFreshCoordinates = false
    private var pendingCoordinatesSince = 0L
    private var lastScreenOffRetry = 0L

    private val streamListener = LocationListener { location ->
        onStreamFixAvailable(Location(location))
    }

    init {
        restoreReference()
    }

    fun startContinuousUpdates() {
        if (!hasPermission()) return
        try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                stopContinuousUpdates()
                return
            }
            if (streamActive) return
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                STREAM_INTERVAL_MS,
                STREAM_MIN_DISTANCE_METERS,
                streamListener,
                Looper.getMainLooper()
            )
            streamActive = true
        } catch (_: SecurityException) {}
    }

    fun stopContinuousUpdates() {
        if (!streamActive) return
        try { manager.removeUpdates(streamListener) } catch (_: Exception) {}
        streamActive = false
    }

    fun markCoordinatesPending(now: Long = System.currentTimeMillis()) {
        awaitingFreshCoordinates = true
        pendingCoordinatesSince = now
    }

    fun resolvePendingCoordinates() {
        awaitingFreshCoordinates = false
    }

    fun retryPendingCoordinatesIfDue(screenOn: Boolean, now: Long = System.currentTimeMillis()) {
        if (screenOn || !awaitingFreshCoordinates) return
        val pendingFor = now - pendingCoordinatesSince
        if (pendingFor < PENDING_WINDOW_MS && now - lastScreenOffRetry > SCREEN_OFF_RETRY_MS) {
            lastScreenOffRetry = now
            requestPreciseFix()
        }
    }

    @SuppressLint("MissingPermission")
    fun requestPreciseFix(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!hasPermission()) return
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return

        lateinit var listener: LocationListener
        synchronized(forcedFixLock) {
            if (!force && now - lastForcedFixTime < FORCED_FIX_DEBOUNCE_MS) return
            if (forcedFixListener != null) return
            lastForcedFixTime = now
            listener = LocationListener { location ->
                val fresh = System.currentTimeMillis() - location.time < MAX_FIX_AGE_MS
                if (location.accuracy > MAX_ACCURACY_METERS || !fresh || !isPlausible(location)) {
                    return@LocationListener
                }
                val ownsRegistration = synchronized(forcedFixLock) {
                    if (forcedFixListener === listener) {
                        forcedFixListener = null
                        true
                    } else false
                }
                if (!ownsRegistration) return@LocationListener
                try { manager.removeUpdates(listener) } catch (_: Exception) {}
                accept(location)
                resolvePendingCoordinates()
                onPreciseFixAccepted(Location(location))
            }
            forcedFixListener = listener
        }

        log("Solicitando fix GPS preciso (fresco) para fijar coordenadas")
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper()
            )
        } catch (error: Exception) {
            synchronized(forcedFixLock) {
                if (forcedFixListener === listener) forcedFixListener = null
            }
            log("No se pudo registrar el fix GPS preciso: ${error.message}")
            return
        }

        val timeoutMs = if (force) COLD_FIX_TIMEOUT_MS else WARM_FIX_TIMEOUT_MS
        scope.launch {
            delay(timeoutMs)
            val timedOut = synchronized(forcedFixLock) {
                if (forcedFixListener === listener) {
                    forcedFixListener = null
                    true
                } else false
            }
            if (timedOut) {
                try { manager.removeUpdates(listener) } catch (_: Exception) {}
                log("Fix GPS preciso no disponible en ${timeoutMs / 1_000} s (timeout)")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun currentLocation(): Location? {
        if (!hasPermission()) return null
        return try {
            val now = System.currentTimeMillis()
            val candidate = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.takeIf {
                it.accuracy < MAX_ACCURACY_METERS && now - it.time < MAX_FIX_AGE_MS
            }
            if (candidate != null && isPlausible(candidate)) {
                accept(candidate)
                Location(candidate)
            } else null
        } catch (_: Exception) { null }
    }

    fun lastAcceptedLocation(): Location? = lastAcceptedLocation?.let(::Location)

    fun destroy() {
        stopContinuousUpdates()
        val pending = synchronized(forcedFixLock) {
            forcedFixListener.also { forcedFixListener = null }
        }
        pending?.let { try { manager.removeUpdates(it) } catch (_: Exception) {} }
    }

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun isPlausible(candidate: Location): Boolean {
        fun Location.asFix() = GpsFixContinuity.Fix(latitude, longitude, time, accuracy)
        return continuity.accept(lastAcceptedLocation?.asFix(), candidate.asFix())
    }

    private fun accept(location: Location) {
        lastAcceptedLocation = Location(location)
        if (location.time <= lastPersistedLocationTime) return
        lastPersistedLocationTime = location.time
        try {
            prefs.edit().putString(
                KEY_LAST_LOCATION,
                "${location.latitude},${location.longitude},${location.time}"
            ).apply()
        } catch (_: Exception) {}
    }

    private fun restoreReference() {
        try {
            val parts = prefs.getString(KEY_LAST_LOCATION, null)?.split(",") ?: return
            if (parts.size != 3) return
            val lat = parts[0].toDoubleOrNull() ?: return
            val lon = parts[1].toDoubleOrNull() ?: return
            val time = parts[2].toLongOrNull() ?: return
            val age = System.currentTimeMillis() - time
            if (age !in 0..REFERENCE_MAX_AGE_MS) return
            lastAcceptedLocation = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = lat
                longitude = lon
                this.time = time
            }
            lastPersistedLocationTime = time
        } catch (_: Exception) {}
    }

    private companion object {
        const val PREFS_NAME = "miniic_prefs"
        const val KEY_LAST_LOCATION = "last_accepted_loc"
        const val STREAM_INTERVAL_MS = 15_000L
        // Stable motion needs periodic fixes while the device is stationary. A distance gate
        // would suppress precisely those samples; the 15 s time gate remains the power bound.
        const val STREAM_MIN_DISTANCE_METERS = 0f
        const val MAX_FIX_AGE_MS = 120_000L
        const val MAX_ACCURACY_METERS = 100f
        const val FORCED_FIX_DEBOUNCE_MS = 30_000L
        const val WARM_FIX_TIMEOUT_MS = 20_000L
        const val COLD_FIX_TIMEOUT_MS = 90_000L
        const val PENDING_WINDOW_MS = 600_000L
        const val SCREEN_OFF_RETRY_MS = 90_000L
        const val REFERENCE_MAX_AGE_MS = 6L * 60 * 60 * 1_000
    }
}
