package com.alexisgordr.icdetector.core

/**
 * 3.0 — Qué texto lleva la notificación de monitorización en cada actualización, y cuándo se repinta.
 *
 * Prioridad, de mayor a menor:
 *  1. Escritura fallida: la aplica después CollectionHealthController (la recolección no guarda nada).
 *  2. Falta de señal / modo avión / buscando red ([TextKind.NO_SIGNAL]): es el estado actual y no hay
 *     ningún resultado antiguo a la vista.
 *  3. Análisis no disponible: mientras dure la racha de fallos, sustituye a cualquier otro texto
 *     (resultado, batería, "monitorización activa"), para que no desaparezca con el siguiente cambio.
 *  4. El texto pedido.
 *
 * Límite de repintado: como mucho una vez cada [minIntervalMs] (el callback de telefonía puede
 * llegar muchas veces por segundo); `force` lo salta, y se usa al fallar y al recuperarse, para que
 * el aviso no quede visible por haber caído dentro del límite.
 */
class MonitoringNotificationText(private val minIntervalMs: Long = 2_000L) {

    enum class TextKind { NO_SIGNAL, OTHER }

    private var lastShownAtMs: Long? = null

    /** Texto a mostrar, o null si esta actualización cae dentro del límite y no se fuerza. */
    @Synchronized
    fun next(
        requested: String,
        kind: TextKind,
        force: Boolean,
        analysisUnavailable: Boolean,
        unavailableText: String,
        nowMs: Long
    ): String? {
        val last = lastShownAtMs
        if (!force && last != null && nowMs - last < minIntervalMs) return null
        lastShownAtMs = nowMs
        return if (analysisUnavailable && kind != TextKind.NO_SIGNAL) unavailableText else requested
    }
}
