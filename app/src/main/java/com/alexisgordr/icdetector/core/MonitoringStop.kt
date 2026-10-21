package com.alexisgordr.icdetector.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 3.1.0 (#42) — El servicio en primer plano no pudo arrancar.
 *
 * `startForeground()` puede lanzar (ForegroundServiceStartNotAllowedException en Android 12+ si la
 * app no puede arrancar un servicio en primer plano en ese momento, una SecurityException sin
 * permiso de ubicación en Android 14+). Antes el servicio se paraba en silencio: no había notificación,
 * no quedaba rastro y la app no lo decía, así que la recolección podía estar parada días sin que
 * nadie lo supiera. Ahora un fallo deja tres cosas, en este orden:
 *  1. un registro persistente (el evento de collection-health, [Sink.record]) con la hora y la
 *     clase de la excepción, que el siguiente arranque anota en el terminal ([terminalLine]);
 *  2. una notificación normal, no de primer plano, «Monitoreo detenido — abre la app para
 *     reanudar» ([Sink.notifyStopped]);
 *  3. el estado [state], que la pantalla principal enseña en lugar del monitoreo en marcha.
 * Un arranque correcto borra el registro y la notificación ([Sink.cleared]).
 *
 * Puro, sin Android: el servicio y la actividad pasan su [Sink]. Tras un reinicio no se arranca nada
 * desde segundo plano ([BootStart]): el monitoreo vuelve desde la actividad, por esta misma vía.
 */
object MonitoringStop {

    data class Stopped(val atMs: Long, val errorClass: String)

    interface Sink {
        fun record(stopped: Stopped)
        fun notifyStopped()
        fun cleared()
    }

    private val _state = MutableStateFlow<Stopped?>(null)

    /** Monitoreo detenido por un fallo de arranque, o null. Lo observa la pantalla principal. */
    val state: StateFlow<Stopped?> = _state.asStateFlow()

    /**
     * Ejecuta [start] (la llamada a `startForeground`). Si lanza, informa del fallo con [fail] y
     * devuelve false; si no, borra un fallo anterior y devuelve true.
     */
    fun startForeground(sink: Sink, nowMs: Long, start: () -> Unit): Boolean {
        try {
            start()
        } catch (e: Exception) {
            fail(sink, nowMs, e)
            return false
        }
        runCatching { sink.cleared() }
        _state.value = null
        return true
    }

    /** Fallo al arrancar (en el servicio o al pedirlo la actividad). */
    fun fail(sink: Sink, nowMs: Long, error: Throwable): Stopped {
        val stopped = Stopped(nowMs, errorClass(error))
        runCatching { sink.record(stopped) }
        runCatching { sink.notifyStopped() }
        _state.value = stopped
        return stopped
    }

    /** Al abrir la app: el fallo guardado por un proceso anterior, si lo hay. */
    fun restore(stopped: Stopped?) {
        if (stopped != null) _state.value = stopped
    }

    fun errorClass(error: Throwable): String = error.javaClass.simpleName.ifEmpty { error.javaClass.name }

    /** Valores guardados → fallo, o null si no hay ninguno. */
    fun fromStored(atMs: Long, errorClass: String?): Stopped? =
        if (atMs > 0L && !errorClass.isNullOrBlank()) Stopped(atMs, errorClass) else null

    /** Línea del terminal del siguiente arranque (castellano; TerminalLocalization la traduce). */
    fun terminalLine(stopped: Stopped): String =
        "⚠ El monitoreo se detuvo: Android no permitió el servicio en primer plano (${stopped.errorClass}). " +
            "No se recogió nada hasta este arranque."

    const val PREF_STOPPED_AT_MS = "monitoring_stopped_at_ms"
    const val PREF_STOPPED_ERROR = "monitoring_stopped_error"
}
