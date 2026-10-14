package com.alexisgordr.icdetector.core

/**
 * 3.0 — Guardia de la poda por antigüedad contra un reloj civil adelantado, sin Android.
 *
 * La retención se calcula con la hora de pared. Si el teléfono arranca con la fecha adelantada y el
 * servicio se inicia, la poda borraba en una sola transacción todo lo anterior a "fecha errónea −
 * 120 días", sin ningún error: la campaña entera. Antes de podar se comprueba:
 *
 *  1. Que el corte no sea posterior a la observación más reciente guardada. Si lo es, el reloj va
 *     por delante de los datos (al menos [com.alexisgordr.icdetector.storage.CellDbHelper]
 *     .DEFAULT_RETENTION_DAYS días) y la poda lo borraría todo.
 *  2. Que no borre más de [MAX_SHARE] de las filas del historial. Una poda diaria normal borra un
 *     día de 120 (≈ 1 %); tras una pausa de P días, ≈ P/120. Con un 25 % pasan las pausas de hasta
 *     30 días, y un reloj adelantado no puede borrar de una vez más de una cuarta parte. Si el
 *     servicio estuvo parado más de 30 días, la poda se omite hasta que las filas nuevas bajen la
 *     proporción: el historial puede llegar a unas cuatro veces la retención, pero no se pierde
 *     nada que no debiera perderse, y cada omisión queda en el terminal.
 *     Solo se aplica desde [MIN_ROWS_FOR_SHARE] filas a borrar (≈ un día de recolección: una fila
 *     periódica cada 5–15 min más los handovers), para que una base pequeña o recién empezada
 *     pode con normalidad.
 *
 * Si la guardia salta no se poda ninguna tabla por antigüedad: todas usan la misma hora de pared.
 * El tope forense, que va por número de muestras, se aplica igual.
 */
object RetentionClockGuard {
    const val MAX_SHARE = 0.25
    const val MIN_ROWS_FOR_SHARE = 200

    sealed interface Decision {
        data object Prune : Decision
        /** El corte es posterior a la observación más reciente: el reloj va por delante. */
        data class CutoffAfterNewest(val cutoffMs: Long, val newestObservedMs: Long) : Decision
        /** La poda borraría [due] de [total] filas, más de [MAX_SHARE]. */
        data class TooManyRows(val due: Int, val total: Int) : Decision
    }

    /**
     * [newestObservedMs] es MAX(observed_at_ms) del historial (null si ninguna fila lo tiene);
     * [rowsDue], las filas que la poda borraría con [cutoffMs]; [totalRows], todas.
     */
    fun decide(cutoffMs: Long, newestObservedMs: Long?, rowsDue: Int, totalRows: Int): Decision {
        if (newestObservedMs != null && cutoffMs > newestObservedMs) return Decision.CutoffAfterNewest(cutoffMs, newestObservedMs)
        if (rowsDue >= MIN_ROWS_FOR_SHARE && totalRows > 0 && rowsDue > MAX_SHARE * totalRows) {
            return Decision.TooManyRows(rowsDue, totalRows)
        }
        return Decision.Prune
    }
}
