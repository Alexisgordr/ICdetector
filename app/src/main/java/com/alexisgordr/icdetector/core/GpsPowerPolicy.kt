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
    private var handoverUntilMs: Long = 0L

    val receptionDegraded: Boolean get() = consecutiveFailures >= FAILURES_BEFORE_PAUSE

    fun streamWanted(mode: LocationMode, screenOn: Boolean, nowMs: Long = 0L): Boolean =
        (LocationPolicy.streamWanted(mode, screenOn) ||
            (mode == LocationMode.INTELLIGENT && nowMs < handoverUntilMs)) &&
            (mode == LocationMode.CONTINUOUS || !receptionDegraded)

    /** Handover windows never bypass degraded-reception protection. */
    fun onHandover(nowMs: Long): Boolean {
        if (receptionDegraded) return false
        handoverUntilMs = nowMs + HANDOVER_WINDOW_MS
        return true
    }

    fun clearHandoverWindow() { handoverUntilMs = 0L }

    /**
     * Qué hace un handover con el GPS:
     *  - [WINDOW]: antena nueva con recepción; ventana continua hasta 60 s desde el último handover.
     *  - [BOUNDED_PROBE]: antena nueva con recepción degradada; un intento acotado (20 s) sin esperar
     *    el límite de 2 minutos, como mucho uno por minuto. Con un fix nuevo se recupera la
     *    política normal.
     *  - [PROBE]: antena reciente sin posición de los últimos 45 s; petición con el límite compartido.
     *  - [NONE]: antena reciente con un fix de menos de 45 s, o antena nueva sin recepción dentro del
     *    minuto de espera entre intentos; no se enciende nada más.
     *  - [SHARED_GATE]: continuo y adaptativo; petición puntual con sus esperas de siempre.
     */
    enum class HandoverAction { WINDOW, BOUNDED_PROBE, PROBE, NONE, SHARED_GATE }

    // Antenas vistas en los últimos [RECENT_CELL_MS] (clave de identidad completa -> instante).
    private val recentCells = LinkedHashMap<String, Long>()

    /**
     * Ahorro de batería: solo las antenas nuevas abren o alargan la ventana. Quieto en casa, con
     * el móvil saltando entre dos antenas, los handovers son de antenas recientes y no encadenan
     * ventanas: el GPS sigue con los intentos cortos cada 45 s. En un trayecto las antenas son
     * nuevas y el GPS se mantiene activo.
     *
     * Todos los tiempos son `SystemClock.elapsedRealtime()` (monotónico, cuenta el reposo) y las
     * antenas se comparan por identidad completa (MCC-MNC-TAC-CID-tecnología).
     *
     * @param lastFixAtMs instante (elapsed realtime) del último fix vivo y válido, o null.
     */
    fun onHandover(
        mode: LocationMode,
        nowMs: Long,
        newCell: String,
        previousCell: String?,
        lastFixAtMs: Long?
    ): HandoverAction {
        if (mode != LocationMode.INTELLIGENT) return HandoverAction.SHARED_GATE
        recentCells.entries.removeAll { nowMs - it.value >= RECENT_CELL_MS }
        val isNew = newCell !in recentCells
        previousCell?.let { recentCells[it] = nowMs }
        recentCells[newCell] = nowMs
        while (recentCells.size > MAX_RECENT_CELLS) recentCells.remove(recentCells.keys.first())

        val fixAgeMs = lastFixAtMs?.let { nowMs - it }?.takeIf { it >= 0 }
        return when {
            // Antena nueva: siempre abre o alarga la ventana (un fix fresco no la omite, para no
            // cortar la continuidad de un trayecto justo al terminar una ventana).
            isNew && onHandover(nowMs) -> HandoverAction.WINDOW
            // Antena nueva sin recepción: intento corto (20 s), y solo si el último intento de
            // cualquier tipo empezó hace al menos [DEGRADED_HANDOVER_PROBE_SPACING_MS]. Sin este
            // límite, una sucesión de antenas nuevas (un tren en un túnel) encadenaría intentos.
            isNew -> if (probeAllowed(mode, nowMs, newCellHandover = true)) HandoverAction.BOUNDED_PROBE else HandoverAction.NONE
            // Antena reciente: se aprovecha un fix fresco y solo se pide si tiene más de 45 s.
            fixAgeMs != null && fixAgeMs < INTELLIGENT_FIX_INTERVAL_MS -> HandoverAction.NONE
            else -> HandoverAction.PROBE
        }
    }


    fun periodicProbeDue(mode: LocationMode, nowMs: Long): Boolean =
        mode != LocationMode.CONTINUOUS &&
            (mode == LocationMode.INTELLIGENT || receptionDegraded) && nowMs >= nextProbeAtMs

    /**
     * Control común de los modos de ahorro: periódicos, handovers, pantalla y alarmas, forzados
     * incluidos. Todos los límites se miden desde el último intento realmente iniciado
     * ([onProbeStarted]), sea del tipo que sea, para que dos caminos distintos no puedan encadenar
     * intentos. [newCellHandover]: antena nueva con recepción degradada, que espera
     * [DEGRADED_HANDOVER_PROBE_SPACING_MS] en vez de [DEGRADED_EVENT_SPACING_MS].
     */
    fun probeAllowed(mode: LocationMode, nowMs: Long, newCellHandover: Boolean = false): Boolean {
        if (mode == LocationMode.CONTINUOUS) return true
        val spacing = when {
            receptionDegraded && newCellHandover -> DEGRADED_HANDOVER_PROBE_SPACING_MS
            receptionDegraded -> DEGRADED_EVENT_SPACING_MS
            mode == LocationMode.ADAPTIVE -> LocationMode.ADAPTIVE_MIN_FIX_INTERVAL_MS
            else -> INTELLIGENT_EVENT_SPACING_MS
        }
        return lastProbeAtMs?.let { nowMs - it >= spacing } != false
    }

    /** Se llama solo cuando el GPS se ha pedido de verdad (el registro no ha fallado). */
    fun onProbeStarted(nowMs: Long) { lastProbeAtMs = nowMs }

    /** [probeAllowed] y, si se permite, [onProbeStarted] en un paso. */
    fun tryStartProbe(mode: LocationMode, nowMs: Long): Boolean {
        if (!probeAllowed(mode, nowMs)) return false
        if (mode != LocationMode.CONTINUOUS) onProbeStarted(nowMs)
        return true
    }

    fun onProbeFailed(nowMs: Long) {
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(5)
        if (receptionDegraded) clearHandoverWindow()
        nextProbeAtMs = nowMs + when (consecutiveFailures) {
            3 -> 120_000L
            4 -> 300_000L
            5 -> 600_000L
            else -> INTELLIGENT_FIX_INTERVAL_MS
        }
    }

    fun onStreamUnavailable(nowMs: Long) {
        clearHandoverWindow()
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
        const val HANDOVER_WINDOW_MS = 60_000L
        /** Una antena vista en los últimos 10 minutos no es "nueva" para la ventana de handover. */
        const val RECENT_CELL_MS = 600_000L
        const val MAX_RECENT_CELLS = 64
        /**
         * Sin recepción, una antena nueva solo inicia un intento (20 s) si el último intento de
         * cualquier tipo empezó hace al menos un minuto: como mucho 20 s de búsqueda por minuto.
         */
        const val DEGRADED_HANDOVER_PROBE_SPACING_MS = 60_000L
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
