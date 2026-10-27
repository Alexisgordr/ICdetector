package com.alexisgordr.icdetector.core

/**
 * 3.0 — Vigilancia de los callbacks de telefonía, sin Android para poder probarla.
 *
 * Si el módem deja de entregar celdas durante más del plazo, tras [maxRetries] peticiones de
 * refresco seguidas se reinician los callbacks. Una ENTREGA es cualquier lista de celdas no vacía:
 * la del callback espontáneo y también la respuesta a requestCellInfoUpdate. Antes esa respuesta no
 * contaba: con el móvil quieto y sin cambios de pantalla, a los 30 s el watchdog reiniciaba los
 * callbacks cada pocas peticiones aunque el módem contestara a todas.
 *
 * 3.1.7 (#49) — Con la ubicación apagada Android entrega listas vacías; el watchdog daba los
 * callbacks por muertos y los reiniciaba cada ~30 s, indefinidamente. En algunos dispositivos los
 * callbacks desregistrados no se liberan del todo y se llegaba al tope de listeners por proceso
 * (IllegalStateException en registerTelephonyCallback, crash en segundo plano). Ahora:
 *  - con una causa conocida de que falten celdas (ubicación del sistema apagada o sin permiso de
 *    ubicación precisa) no se reinicia: es Android quien no da celdas ([Decision.KnownCause]);
 *  - un ServiceState o un DisplayInfo que llega es señal de vida ([onSignOfLife]): los callbacks no
 *    están muertos, solo se reinicia si no llega NADA en el plazo;
 *  - el plazo crece entre reinicios (30 s, 60 s, 2 min… hasta [maxTimeoutMs]) y vuelve a 30 s con
 *    una entrega con celdas;
 *  - tras [maxConsecutiveRestarts] reinicios seguidos sin celdas deja de reiniciar
 *    ([Decision.Paused]) hasta que cambie algo ([onStateChanged]) o lleguen celdas.
 *
 * Reloj monotónico inyectable: un cambio de hora no debe disparar ni retrasar un reinicio.
 */
class CallbackWatchdog(
    private val timeoutMs: Long = TIMEOUT_MS,
    private val maxRetries: Int = MAX_RETRIES,
    private val maxTimeoutMs: Long = MAX_TIMEOUT_MS,
    private val maxConsecutiveRestarts: Int = MAX_CONSECUTIVE_RESTARTS,
    private val clock: () -> Long
) {
    /** Qué hacer tras una petición de refresco. */
    sealed interface Decision {
        /** Nada: hay actividad reciente o aún no se cumple el plazo. */
        data object Wait : Decision

        /** Reiniciar los callbacks; [silentMs] desde la última actividad, para el terminal. */
        data class Restart(val silentMs: Long) : Decision

        /** Faltan celdas por una causa conocida; [announce] la primera vez de cada episodio. */
        data class KnownCause(val announce: Boolean) : Decision

        /** Demasiados reinicios seguidos sin celdas; [announce] solo al pararse. */
        data class Paused(val announce: Boolean) : Decision
    }

    /** Empieza contando desde la creación: sin actividad aún, el primer plazo es completo. */
    private var lastActivityMs: Long = clock()
    private var retries = 0
    private var consecutiveRestarts = 0
    private var paused = false
    private var knownCauseActive = false

    /** Llegó una lista de celdas no vacía, por cualquier vía: el módem contesta. */
    @Synchronized
    fun onDelivery() {
        lastActivityMs = clock()
        retries = 0
        consecutiveRestarts = 0
        paused = false
    }

    /** 3.1.7 — Llegó un ServiceState o un DisplayInfo: los callbacks siguen vivos. */
    @Synchronized
    fun onSignOfLife() {
        lastActivityMs = clock()
        retries = 0
    }

    /** 3.1.7 — Cambió algo que puede devolver las celdas (ubicación, permiso, red): plazo nuevo. */
    @Synchronized
    fun onStateChanged() {
        lastActivityMs = clock()
        retries = 0
        consecutiveRestarts = 0
        paused = false
    }

    /** Plazo actual antes de reiniciar: [timeoutMs] doblado por cada reinicio seguido, con tope. */
    @Synchronized
    fun currentTimeoutMs(): Long {
        var t = timeoutMs
        repeat(consecutiveRestarts) { t = (t * 2).coerceAtMost(maxTimeoutMs) }
        return t.coerceAtMost(maxTimeoutMs)
    }

    /**
     * Petición de refresco. [cellsBlockedByKnownCause]: la ubicación del sistema está apagada o falta
     * el permiso de ubicación precisa (Android no da celdas, los callbacks no están rotos).
     */
    @Synchronized
    fun onRefreshRequested(cellsBlockedByKnownCause: Boolean = false): Decision {
        if (cellsBlockedByKnownCause) {
            val announce = !knownCauseActive
            knownCauseActive = true
            lastActivityMs = clock()
            retries = 0
            return Decision.KnownCause(announce)
        }
        if (knownCauseActive) {
            // La causa desapareció (p. ej. volvió la ubicación): se empieza de cero.
            knownCauseActive = false
            onStateChanged()
            return Decision.Wait
        }
        if (paused) return Decision.Paused(announce = false)
        val now = clock()
        val silentMs = now - lastActivityMs
        if (silentMs <= currentTimeoutMs()) return Decision.Wait
        retries++
        if (retries < maxRetries) return Decision.Wait
        retries = 0
        lastActivityMs = now
        if (consecutiveRestarts >= maxConsecutiveRestarts) {
            paused = true
            return Decision.Paused(announce = true)
        }
        consecutiveRestarts++
        return Decision.Restart(silentMs)
    }

    companion object {
        const val TIMEOUT_MS = 30_000L
        const val MAX_RETRIES = 4
        /** 3.1.7 — Tope del plazo entre reinicios. */
        const val MAX_TIMEOUT_MS = 30 * 60_000L
        /** 3.1.7 — Reinicios seguidos sin celdas antes de pararse. */
        const val MAX_CONSECUTIVE_RESTARTS = 5
    }
}
