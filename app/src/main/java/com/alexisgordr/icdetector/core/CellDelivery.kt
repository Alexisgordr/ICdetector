package com.alexisgordr.icdetector.core

/**
 * 3.1.6 (#49) — Entregas de celdas que el sistema puede mandar nulas.
 *
 * Android declara las listas de celdas como no nulas, pero algunas capas de fabricante entregan
 * null (por ejemplo, con la ubicación apagada; en AOSP/Pixel llega una lista vacía). Con el
 * parámetro no nulo en Kotlin, la comprobación de nulidad lanzaba una NullPointerException en el
 * hilo principal antes de llegar a la app, y la app se cerraba en segundo plano. Los callbacks
 * aceptan ahora null y pasan por aquí: null se trata como una lista vacía (sin señal).
 */
object CellDelivery {
    /** Lista que sigue el flujo normal: null es una lista vacía. */
    fun <T> orEmpty(raw: List<T>?): List<T> = raw ?: emptyList()

    /** ¿Demuestra la entrega que el módem contesta (CallbackWatchdog)? Ni null ni una lista vacía. */
    fun countsForWatchdog(raw: List<*>?): Boolean = !raw.isNullOrEmpty()
}

/**
 * 3.1.6 (#49) — Una línea de terminal cuando el sistema entrega una lista nula, sin repetirla en
 * cada entrega: vuelve a avisar solo después de una entrega no nula. Mismo criterio que
 * `lastChurnLog` en el servicio.
 */
class NullCellDeliveryNotice {
    private var announced = false

    /** La línea que hay que escribir, o null si no toca. */
    fun onDelivery(rawWasNull: Boolean): String? {
        if (!rawWasNull) {
            announced = false
            return null
        }
        if (announced) return null
        announced = true
        return MESSAGE
    }

    companion object {
        const val MESSAGE = "El sistema no entrega celdas (lista nula); ¿ubicación apagada?"
    }
}
