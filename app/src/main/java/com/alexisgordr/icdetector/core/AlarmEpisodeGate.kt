package com.alexisgordr.icdetector.core

/**
 * v2.10.10 — Decide cuándo una alarma confirmada es un episodio NUEVO, que merece su registro de
 * "alarma confirmada" y su notificación, y cuándo es el mismo episodio que sigue activo.
 *
 * Antes la guarda era el CID de la última alarma y solo se borraba al cambiar de celda: tras
 * recuperarse, un segundo episodio en la misma celda sonaba pero no se notificaba ni se registraba.
 * Ahora un episodio se cierra cuando la celda lleva [closeAfterMs] sin alarma (el mismo margen
 * que la captura forense tras la recuperación), y la identidad es la completa. Una alarma que
 * parpadea dentro de ese margen sigue siendo el mismo episodio y no repite el aviso.
 *
 * Sin Android, para poder probarla en la JVM.
 */
class AlarmEpisodeGate(
    private val closeAfterMs: Long = DEFAULT_CLOSE_AFTER_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var activeIdentity: String? = null
    private var clearSinceMs: Long? = null

    /** Ciclo con alarma confirmada. Devuelve true si empieza un episodio nuevo. */
    fun onConfirmedAlarm(identity: String): Boolean {
        // El vencimiento se comprueba también aquí: si el último ciclo limpio llegó a los 59 s y la
        // alarma vuelve a los 61 s, la celda ya llevaba 60 s sin alarma y es un episodio nuevo.
        expireIfClearLongEnough(clock())
        clearSinceMs = null
        if (identity == activeIdentity) return false
        activeIdentity = identity
        return true
    }

    /** Ciclo sin alarma confirmada: cierra el episodio cuando dura lo bastante. */
    fun onClear() {
        if (activeIdentity == null) return
        val now = clock()
        if (clearSinceMs == null) clearSinceMs = now
        expireIfClearLongEnough(now)
    }

    private fun expireIfClearLongEnough(now: Long) {
        val since = clearSinceMs ?: return
        if (now - since >= closeAfterMs) reset()
    }

    /** Cambio de celda o historial borrado: el siguiente aviso es siempre un episodio nuevo. */
    fun reset() {
        activeIdentity = null
        clearSinceMs = null
    }

    companion object {
        const val DEFAULT_CLOSE_AFTER_MS = 60_000L
    }
}
