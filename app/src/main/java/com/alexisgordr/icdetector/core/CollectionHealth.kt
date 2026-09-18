package com.alexisgordr.icdetector.core

/**
 * Salud de la recolección: ¿se está escribiendo de verdad en la base de datos?
 *
 * v2.3.3 — El motivo es de campaña, no de código. `SQLiteDatabase.insert()` captura
 * `SQLiteFullException` (disco lleno) y `SQLiteDatabaseLockedException` internamente y devuelve
 * `-1`: la app sigue analizando, el terminal sigue escribiendo líneas y la notificación sigue
 * diciendo "Sondeo activo" mientras el historial no recibe ni una fila. En una recolección de
 * tres meses eso no se detecta hasta el final, cuando el CSV se corta sin explicación.
 *
 * Esta clase es pura y determinista a propósito: recibe el resultado de cada escritura y el
 * reloj, y decide qué hay que enseñar. Así se puede probar sin base de datos ni Android.
 */
class CollectionHealth(
    /** Fallos seguidos antes de declarar la escritura rota. Uno suelto puede ser un bloqueo puntual. */
    private val failuresBeforeAlarm: Int = 3,
    /** Hueco sin escrituras a partir del cual se considera que la recolección se interrumpió. */
    private val interruptionGapMs: Long = 30L * 60_000L
) {
    @Volatile
    var consecutiveFailures: Int = 0
        private set

    /** Última escritura correcta, en milisegundos de reloj de pared. 0 = todavía ninguna. */
    @Volatile
    var lastSuccessMs: Long = 0L
        private set

    /** True cuando la escritura lleva fallando lo suficiente como para avisar al usuario. */
    val isFailing: Boolean get() = consecutiveFailures >= failuresBeforeAlarm

    /** Restaura el estado persistido entre arranques del servicio. */
    @Synchronized
    fun restore(lastSuccessMs: Long) {
        this.lastSuccessMs = lastSuccessMs
    }

    /**
     * Registra el resultado de un `insert`. Devuelve true si el estado visible ha cambiado y la
     * notificación debe refrescarse, para no reescribirla en cada ciclo.
     */
    @Synchronized
    fun noteWrite(rowId: Long, nowMs: Long): Boolean {
        val wasFailing = isFailing
        if (rowId == -1L) {
            consecutiveFailures++
        } else {
            consecutiveFailures = 0
            lastSuccessMs = nowMs
        }
        return wasFailing != isFailing
    }

    /**
     * Hueco desde la última escritura correcta conocida, o null si nunca hubo una (primera
     * instalación) o si el reloj retrocedió (cambio de hora): un valor negativo no es un hueco.
     */
    fun gapSince(nowMs: Long): Long? {
        if (lastSuccessMs <= 0L) return null
        val gap = nowMs - lastSuccessMs
        return if (gap >= 0L) gap else null
    }

    /**
     * ¿Hubo una interrupción de la recolección antes de este arranque? Se consulta una vez al
     * iniciar el servicio: un reinicio del móvil, una OTA o un cierre del proceso dejan un hueco
     * que de otro modo solo se vería al analizar los datos en diciembre.
     */
    fun interruptionBefore(nowMs: Long): Long? = gapSince(nowMs)?.takeIf { it >= interruptionGapMs }
}
