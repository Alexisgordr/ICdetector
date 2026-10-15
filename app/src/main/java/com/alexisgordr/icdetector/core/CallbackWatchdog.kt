package com.alexisgordr.icdetector.core

/**
 * 3.0 — Vigilancia de los callbacks de telefonía, sin Android para poder probarla.
 *
 * Si el módem deja de entregar celdas durante más de [timeoutMs], tras [maxRetries] peticiones
 * de refresco seguidas se reinician los callbacks. Una ENTREGA es cualquier lista de celdas no
 * vacía: la del callback espontáneo, la de DisplayInfo y, desde este cambio, también la respuesta a
 * requestCellInfoUpdate. Antes esa respuesta no contaba: con el móvil quieto y sin cambios de
 * pantalla, a los 30 s el watchdog reiniciaba los callbacks cada pocas peticiones aunque el módem
 * contestara a todas, y cada reinicio disparaba entregas iniciales (ServiceState, DisplayInfo) sin
 * dejar rastro.
 *
 * Reloj monotónico inyectable: un cambio de hora no debe disparar ni retrasar un reinicio.
 */
class CallbackWatchdog(
    private val timeoutMs: Long = TIMEOUT_MS,
    private val maxRetries: Int = MAX_RETRIES,
    private val clock: () -> Long
) {
    /** Empieza contando desde la creación: sin entrega aún, el primer plazo es completo. */
    private var lastDeliveryMs: Long = clock()
    private var retries = 0

    /** Llegó una lista de celdas no vacía, por cualquier vía. */
    @Synchronized
    fun onDelivery() {
        lastDeliveryMs = clock()
        retries = 0
    }

    /**
     * Petición de refresco. Devuelve los ms desde la última entrega si toca reiniciar los callbacks
     * (para dejarlo escrito en el terminal), o null si no. Tras un reinicio el plazo vuelve a empezar.
     */
    @Synchronized
    fun onRefreshRequested(): Long? {
        val now = clock()
        val silentMs = now - lastDeliveryMs
        if (silentMs <= timeoutMs) return null
        retries++
        if (retries < maxRetries) return null
        retries = 0
        lastDeliveryMs = now
        return silentMs
    }

    companion object {
        const val TIMEOUT_MS = 30_000L
        const val MAX_RETRIES = 4
    }
}
