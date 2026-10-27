package com.alexisgordr.icdetector.core

/**
 * 3.1.7 (#49) — Piezas sin Android de la recuperación de los callbacks de telefonía, para poder
 * probarlas en la JVM. TelephonyCollectionController las usa con el TelephonyManager real.
 */

/**
 * El reinicio de los callbacks nunca se ejecuta dentro de la entrega de un callback. Antes,
 * onDisplayInfoChanged → requestFreshCellInfo → reinicio desregistraba el callback mientras el
 * sistema lo estaba entregando. Ahora se publica con [post] (el executor del hilo principal) y corre
 * después; mientras hay uno pendiente, otro no se encola.
 */
class DeferredRestart(private val post: (Runnable) -> Unit) {
    private var pending = false

    /** Encola [action] si no hay otro pendiente. Devuelve si lo encoló. */
    @Synchronized
    fun request(action: () -> Unit): Boolean {
        if (pending) return false
        pending = true
        try {
            post(Runnable {
                try {
                    action()
                } finally {
                    synchronized(this) { pending = false }
                }
            })
        } catch (e: Exception) {
            // Si no se pudo publicar, no queda nada pendiente.
            pending = false
            return false
        }
        return true
    }

    @get:Synchronized
    val isPending: Boolean get() = pending
}

/**
 * Registro de los callbacks de telefonía con un nombre cada uno ("CellInfo", "DisplayInfo",
 * "ServiceState"). Registrar nunca lanza: si [register] falla (IllegalStateException al pasar el
 * tope de listeners por proceso, SecurityException…), se deja una línea en el terminal (una vez por
 * callback hasta que vuelva a registrarse) y se sigue sin ese callback. Lleva la cuenta de los vivos
 * y, si al registrar uno nuevo ya hay [maxLive], desregistra antes los anteriores.
 */
class CallbackRegistrar<T : Any>(
    private val register: (T) -> Unit,
    private val unregister: (T) -> Unit,
    private val log: (String) -> Unit,
    private val maxLive: Int = MAX_LIVE
) {
    private val live = linkedMapOf<String, T>()
    private val reportedFailures = mutableSetOf<String>()

    @get:Synchronized
    val liveCount: Int get() = live.size

    @Synchronized
    fun isRegistered(name: String): Boolean = name in live

    /** Registra [callback] con [name] si no lo está ya. Devuelve si queda registrado. */
    @Synchronized
    fun add(name: String, callback: T): Boolean {
        if (name in live) return true
        if (live.size >= maxLive) {
            log(TelephonyRecoveryLog.LIVE_CAP)
            removeAll()
        }
        return try {
            register(callback)
            live[name] = callback
            reportedFailures.remove(name)
            true
        } catch (e: Exception) {
            if (reportedFailures.add(name)) log(TelephonyRecoveryLog.registerFailed(name, e))
            false
        }
    }

    /** Desregistra todos; un fallo al desregistrar uno no impide desregistrar los demás. */
    @Synchronized
    fun removeAll() {
        live.values.forEach { callback -> try { unregister(callback) } catch (_: Exception) {} }
        live.clear()
    }

    companion object {
        /** CellInfo, DisplayInfo y ServiceState. */
        const val MAX_LIVE = 3
    }
}

/** Líneas de terminal de la recuperación (en castellano; TerminalLocalization las traduce). */
object TelephonyRecoveryLog {
    const val LOCATION_OFF =
        "Sin celdas: la ubicación del sistema está apagada; no se reinician los callbacks de telefonía."
    const val NO_PRECISE_LOCATION =
        "Sin celdas: falta el permiso de ubicación precisa; no se reinician los callbacks de telefonía."
    const val LIVE_CAP =
        "Tope de callbacks de telefonía: se desregistran los anteriores antes de registrar."

    fun restarted(silentMs: Long) =
        "Watchdog: callbacks de telefonía reiniciados; ${silentMs / 1_000} s sin entregas de celdas."

    fun paused(restarts: Int) =
        "Watchdog: $restarts reinicios seguidos sin celdas; no se reinicia más hasta que cambie la ubicación, el permiso o la red."

    fun registerFailed(name: String, error: Exception) =
        "No se pudo registrar $name (${error.javaClass.simpleName}); se sigue sin ese callback."

    fun requestFailed(error: Exception) =
        "No se pudo pedir celdas (${error.javaClass.simpleName}); se reintenta en el siguiente ciclo."
}
