package com.alexisgordr.icdetector.core

/**
 * 3.0 (corte de campaña) — Cuándo toca el mantenimiento de la base (poda y tope forense).
 *
 * La primera petición de cada servicio siempre ejecuta; después, una vez cada [intervalMs]. Antes
 * el último run empezaba en 0 y se comparaba con el reloj monotónico desde el ARRANQUE del móvil:
 * con el teléfono encendido hace menos de 24 h, la primera poda no llegaba hasta pasadas 24 h de
 * servicio continuo.
 */
class MaintenanceSchedule(
    private val intervalMs: Long = INTERVAL_MS,
    private val clock: () -> Long
) {
    private var lastRunMs: Long? = null

    /** true si toca ejecutar ahora, y lo anota; [force] ejecuta siempre. */
    @Synchronized
    fun claim(force: Boolean = false): Boolean {
        val now = clock()
        val last = lastRunMs
        if (!force && last != null && now - last < intervalMs) return false
        lastRunMs = now
        return true
    }

    companion object {
        const val INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
