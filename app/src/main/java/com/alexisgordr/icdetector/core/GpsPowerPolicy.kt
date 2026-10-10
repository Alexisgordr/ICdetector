package com.alexisgordr.icdetector.core

/**
 * Limits GPS acquisition, not cellular polling. Times are elapsed realtime, including sleep.
 * Callers own single-flight registration and stop every unsuccessful saving-mode probe.
 *
 * Three failed probes (or three minutes of a GPS stream without a live usable fix) mean poor
 * reception. They do not establish that the device is indoors. Periodic probes then wait 2, 5,
 * and 10 minutes. A handover or suspicious episode may probe earlier, at most once every 2 minutes;
 * it cannot reset the failure count. Only a new live fix clears the degraded state.
 */
class GpsPowerPolicy {
    var consecutiveFailures: Int = 0
        private set
    var nextProbeAtMs: Long = 0L
        private set
    private var lastProbeAtMs: Long? = null

    val receptionDegraded: Boolean get() = consecutiveFailures >= FAILURES_BEFORE_PAUSE

    fun streamWanted(mode: LocationMode, screenOn: Boolean): Boolean =
        LocationPolicy.streamWanted(mode, screenOn) &&
            (mode == LocationMode.CONTINUOUS || !receptionDegraded)

    fun periodicProbeDue(mode: LocationMode, nowMs: Long): Boolean =
        mode != LocationMode.CONTINUOUS &&
            (mode == LocationMode.INTELLIGENT || receptionDegraded) && nowMs >= nextProbeAtMs

    /** A shared gate for periodic, handover, screen and alarm requests, including forced ones. */
    fun tryStartProbe(mode: LocationMode, nowMs: Long): Boolean {
        if (mode == LocationMode.CONTINUOUS) return true
        val spacing = when {
            receptionDegraded -> DEGRADED_EVENT_SPACING_MS
            mode == LocationMode.ADAPTIVE -> LocationMode.ADAPTIVE_MIN_FIX_INTERVAL_MS
            else -> INTELLIGENT_EVENT_SPACING_MS
        }
        if (lastProbeAtMs?.let { nowMs - it < spacing } == true) return false
        lastProbeAtMs = nowMs
        return true
    }

    fun onProbeFailed(nowMs: Long) {
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(5)
        nextProbeAtMs = nowMs + when (consecutiveFailures) {
            3 -> 120_000L
            4 -> 300_000L
            5 -> 600_000L
            else -> INTELLIGENT_FIX_INTERVAL_MS
        }
    }

    fun onStreamUnavailable(nowMs: Long) {
        consecutiveFailures = maxOf(consecutiveFailures, FAILURES_BEFORE_PAUSE - 1)
        onProbeFailed(nowMs)
    }

    /** Called only for a newly delivered, usable, recent GPS fix, never a cache re-read. */
    fun onFreshFix(nowMs: Long) {
        consecutiveFailures = 0
        nextProbeAtMs = nowMs + INTELLIGENT_FIX_INTERVAL_MS
    }

    fun streamHasStalled(startedAtMs: Long, lastLiveFixAtMs: Long?, nowMs: Long): Boolean =
        nowMs - maxOf(startedAtMs, lastLiveFixAtMs ?: startedAtMs) >= STREAM_NO_FIX_TIMEOUT_MS

    companion object {
        const val INTELLIGENT_FIX_INTERVAL_MS = 45_000L
        const val INTELLIGENT_EVENT_SPACING_MS = 30_000L
        const val DEGRADED_EVENT_SPACING_MS = 120_000L
        const val STREAM_NO_FIX_TIMEOUT_MS = 180_000L
        const val FAILURES_BEFORE_PAUSE = 3
        const val SAVING_PROBE_TIMEOUT_MS = 20_000L
        const val INITIAL_PROBE_TIMEOUT_MS = 45_000L
    }
}

/** Wall-clock changes must not make an old fix appear fresh, or invalidate a recent one. */
object GpsFixAge {
    fun isRecent(nowElapsedMs: Long, fixElapsedMs: Long, maxAgeMs: Long): Boolean =
        nowElapsedMs - fixElapsedMs in 0 until maxAgeMs
}
