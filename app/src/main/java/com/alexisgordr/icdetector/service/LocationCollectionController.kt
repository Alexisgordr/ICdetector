package com.alexisgordr.icdetector.service

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.alexisgordr.icdetector.core.GpsFixAge
import com.alexisgordr.icdetector.core.GpsFixContinuity
import com.alexisgordr.icdetector.core.GpsPowerPolicy
import com.alexisgordr.icdetector.core.LocationMode
import com.alexisgordr.icdetector.core.PreciseFixBackoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * GPS-only acquisition. Listener ownership and timers are confined to Main; reference validation
 * is synchronized because currentLocation is also read by analysis workers. Network-derived
 * positions are deliberately excluded from the independent geographic evidence.
 */
internal class LocationCollectionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val onStreamFixAvailable: (Location) -> Unit,
    private val onPreciseFixAccepted: (Location) -> Unit,
    private val locationMode: () -> LocationMode = { LocationMode.CONTINUOUS }
) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val continuity = GpsFixContinuity()
    private val referenceLock = Any()
    private val powerPolicy = GpsPowerPolicy()
    private val preciseFixBackoff = PreciseFixBackoff(FORCED_FIX_DEBOUNCE_MS)

    @Volatile private var destroyed = false
    private var collectionEnabled = true
    private var screenOn = true
    private var appliedMode: LocationMode? = null
    private var streamActive = false
    private var streamStartedAtMs = 0L
    private var lastLiveFixAtMs: Long? = null
    private var forcedFixListener: LocationListener? = null
    private var forcedFixTimeout: Job? = null
    private var lastForcedFixAtMs: Long? = null
    private var savingProbeAttempted = false
    private var lastAcceptedLocation: Location? = null
    private var lastPersistedLocationTime = 0L
    private var awaitingFreshCoordinates = false
    private var pendingCoordinatesSinceMs = 0L
    private var lastScreenOffRetryMs: Long? = null

    private val streamListener = LocationListener { location ->
        if (!destroyed && collectionEnabled && streamActive && acceptCandidate(location, MAX_FIX_AGE_MS)) {
            noteLiveFix(location)
            onStreamFixAvailable(Location(location))
        }
    }

    init { restoreReference() }

    /** Called by the existing collection loop and screen events; never starts a second loop. */
    fun updateCollection(screenOn: Boolean, enabled: Boolean = true) = onMain {
        this.screenOn = screenOn
        collectionEnabled = enabled
        updateOnMain()
    }

    private fun updateOnMain() {
        val mode = locationMode()
        val changedMode = mode != appliedMode
        if (changedMode) {
            cancelPreciseFix()
            stopContinuousUpdates()
            appliedMode = mode
        }
        if (!collectionEnabled || !gpsAvailable()) {
            stopContinuousUpdates()
            cancelPreciseFix()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (mode != LocationMode.CONTINUOUS && streamActive &&
            powerPolicy.streamHasStalled(streamStartedAtMs, lastLiveFixAtMs, now)
        ) {
            powerPolicy.onStreamUnavailable(now)
            stopContinuousUpdates()
            logDegradedReception(now)
        }
        if (powerPolicy.streamWanted(mode, screenOn)) {
            startContinuousUpdates()
        } else {
            stopContinuousUpdates()
        }
        if (powerPolicy.periodicProbeDue(mode, now) ||
            (changedMode && mode == LocationMode.ADAPTIVE && !streamActive)
        ) {
            requestPreciseFixOnMain(force = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startContinuousUpdates() {
        if (streamActive || !gpsAvailable()) return
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, STREAM_INTERVAL_MS, 0f,
                streamListener, Looper.getMainLooper()
            )
            streamStartedAtMs = SystemClock.elapsedRealtime()
            streamActive = true
        } catch (error: Exception) {
            log("No se pudo registrar el GPS continuo: ${error.message}")
        }
    }

    private fun stopContinuousUpdates() {
        if (!streamActive) return
        streamActive = false
        try { manager.removeUpdates(streamListener) } catch (_: Exception) {}
    }

    fun markCoordinatesPending() = onMain {
        awaitingFreshCoordinates = true
        pendingCoordinatesSinceMs = SystemClock.elapsedRealtime()
    }

    fun resolvePendingCoordinates() = onMain { awaitingFreshCoordinates = false }

    fun retryPendingCoordinatesIfDue(screenOn: Boolean) = onMain {
        if (screenOn || !awaitingFreshCoordinates || powerPolicy.receptionDegraded) return@onMain
        val now = SystemClock.elapsedRealtime()
        if (now - pendingCoordinatesSinceMs < PENDING_WINDOW_MS &&
            (lastScreenOffRetryMs?.let { now - it >= SCREEN_OFF_RETRY_MS } != false)
        ) {
            lastScreenOffRetryMs = now
            requestPreciseFixOnMain(force = false)
        }
    }

    fun requestPreciseFix(force: Boolean = false) = onMain { requestPreciseFixOnMain(force) }

    @SuppressLint("MissingPermission")
    private fun requestPreciseFixOnMain(force: Boolean) {
        if (!collectionEnabled || !gpsAvailable() || forcedFixListener != null) return
        val mode = locationMode()
        val now = SystemClock.elapsedRealtime()
        if (mode != LocationMode.CONTINUOUS) {
            // An existing GPS stream already supplies live fixes. Do not add overlapping probes.
            if (streamActive) return
            if (!powerPolicy.tryStartProbe(mode, now)) return
        } else if (!force && lastForcedFixAtMs?.let {
            now - it < preciseFixBackoff.minIntervalMs()
        } == true) return

        lastForcedFixAtMs = now
        val timeoutMs = when {
            mode == LocationMode.CONTINUOUS -> if (force) COLD_FIX_TIMEOUT_MS else WARM_FIX_TIMEOUT_MS
            !savingProbeAttempted -> GpsPowerPolicy.INITIAL_PROBE_TIMEOUT_MS
            else -> GpsPowerPolicy.SAVING_PROBE_TIMEOUT_MS
        }
        if (mode != LocationMode.CONTINUOUS) savingProbeAttempted = true

        lateinit var listener: LocationListener
        listener = LocationListener { location ->
            if (destroyed || forcedFixListener !== listener || !collectionEnabled) return@LocationListener
            val fixAtMs = location.elapsedRealtimeNanos / 1_000_000L
            // Saving-mode probes require a live fix from this attempt (small cache allowance).
            // Re-delivery of an older cached fix must not conceal repeated acquisition failures.
            if (mode != LocationMode.CONTINUOUS && fixAtMs < now - LIVE_FIX_MAX_AGE_MS) return@LocationListener
            val maxAge = if (mode == LocationMode.CONTINUOUS) MAX_FIX_AGE_MS else LIVE_FIX_MAX_AGE_MS
            if (!acceptCandidate(location, maxAge)) return@LocationListener
            cancelPreciseFix()
            preciseFixBackoff.onSuccess()
            noteLiveFix(location)
            awaitingFreshCoordinates = false
            onPreciseFixAccepted(Location(location))
            updateOnMain()
        }
        forcedFixListener = listener
        log("Solicitando fix GPS preciso (fresco) para fijar coordenadas")
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
        } catch (error: Exception) {
            cancelPreciseFix()
            log("No se pudo registrar el fix GPS preciso: ${error.message}")
            return
        }
        forcedFixTimeout = scope.launch(Dispatchers.Main.immediate) {
            delay(timeoutMs)
            if (destroyed || forcedFixListener !== listener) return@launch
            cancelPreciseFix()
            val endedAtMs = SystemClock.elapsedRealtime()
            if (mode == LocationMode.CONTINUOUS) {
                preciseFixBackoff.onTimeout()
            } else {
                powerPolicy.onProbeFailed(endedAtMs)
                if (powerPolicy.receptionDegraded) logDegradedReception(endedAtMs)
            }
            log("Fix GPS preciso no disponible en ${timeoutMs / 1_000} s (timeout)")
        }
    }

    private fun cancelPreciseFix() {
        val listener = forcedFixListener
        forcedFixListener = null
        forcedFixTimeout?.cancel()
        forcedFixTimeout = null
        listener?.let { try { manager.removeUpdates(it) } catch (_: Exception) {} }
    }

    private fun noteLiveFix(location: Location) {
        val now = SystemClock.elapsedRealtime()
        val fixAtMs = location.elapsedRealtimeNanos / 1_000_000L
        if (!GpsFixAge.isRecent(now, fixAtMs, LIVE_FIX_MAX_AGE_MS) ||
            lastLiveFixAtMs?.let { fixAtMs <= it } == true
        ) return
        val wasDegraded = powerPolicy.receptionDegraded
        lastLiveFixAtMs = fixAtMs
        powerPolicy.onFreshFix(now)
        if (wasDegraded) log("Recepción GPS recuperada: reanudando el modo de ubicación.")
    }

    private fun logDegradedReception(nowMs: Long) {
        val seconds = (powerPolicy.nextProbeAtMs - nowMs).coerceAtLeast(0L) / 1_000L
        log("Recepción GPS degradada: próximo intento periódico en $seconds s. El escaneo celular continúa.")
    }

    /** UI availability check: validates the cached fix without registering GPS requests. */
    fun hasUsableGpsFix(): Boolean = gpsAvailable() && currentLocation() != null

    @SuppressLint("MissingPermission")
    fun currentLocation(): Location? {
        if (destroyed || !hasPermission()) return null
        return try {
            val candidate = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            candidate?.takeIf { acceptCandidate(it, MAX_FIX_AGE_MS) }?.let(::Location)
        } catch (_: Exception) { null }
    }

    fun lastAcceptedLocation(): Location? = synchronized(referenceLock) { lastAcceptedLocation?.let(::Location) }

    /** Service.onDestroy is on Main; cleanup still runs after its coroutine scope was cancelled. */
    fun destroy() {
        destroyed = true
        stopContinuousUpdates()
        cancelPreciseFix()
    }

    private fun onMain(action: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { if (!destroyed) action() }
    }

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun gpsAvailable(): Boolean = try {
        hasPermission() && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (_: Exception) { false }

    private fun acceptCandidate(candidate: Location, maxAgeMs: Long): Boolean = synchronized(referenceLock) {
        if (!candidate.hasAccuracy() || candidate.accuracy >= MAX_ACCURACY_METERS ||
            !GpsFixAge.isRecent(SystemClock.elapsedRealtime(), candidate.elapsedRealtimeNanos / 1_000_000L, maxAgeMs)
        ) return@synchronized false
        fun Location.asFix() = GpsFixContinuity.Fix(latitude, longitude, time, accuracy)
        if (!continuity.accept(lastAcceptedLocation?.asFix(), candidate.asFix())) return@synchronized false
        lastAcceptedLocation = Location(candidate)
        if (candidate.time > lastPersistedLocationTime) {
            lastPersistedLocationTime = candidate.time
            prefs.edit().putString(KEY_LAST_LOCATION,
                "${candidate.latitude},${candidate.longitude},${candidate.time}").apply()
        }
        true
    }

    private fun restoreReference() {
        try {
            val parts = prefs.getString(KEY_LAST_LOCATION, null)?.split(",") ?: return
            if (parts.size != 3) return
            val lat = parts[0].toDoubleOrNull() ?: return
            val lon = parts[1].toDoubleOrNull() ?: return
            val time = parts[2].toLongOrNull() ?: return
            if (System.currentTimeMillis() - time !in 0..REFERENCE_MAX_AGE_MS) return
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
        const val MAX_FIX_AGE_MS = 120_000L
        const val LIVE_FIX_MAX_AGE_MS = 10_000L
        const val MAX_ACCURACY_METERS = 100f
        const val FORCED_FIX_DEBOUNCE_MS = 30_000L
        const val WARM_FIX_TIMEOUT_MS = 20_000L
        const val COLD_FIX_TIMEOUT_MS = 90_000L
        const val PENDING_WINDOW_MS = 600_000L
        const val SCREEN_OFF_RETRY_MS = 90_000L
        const val REFERENCE_MAX_AGE_MS = 6L * 60 * 60 * 1_000
    }
}
