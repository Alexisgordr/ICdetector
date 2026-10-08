package com.alexisgordr.icdetector.core

/**
 * v2.10.10 — "Época" del historial: cambia con cada "Borrar historial".
 *
 * Todo trabajo asíncrono que pueda escribir o publicar algo derivado del historial (escrituras,
 * verificaciones, publicación en pantalla) anota la época al empezar y, al terminar, solo aplica
 * su resultado con [ifCurrent]. La invalidación y esas comprobaciones comparten un cerrojo: o el
 * resultado se aplica entero antes de que se invalide la época (y el borrado posterior lo
 * elimina), o se descarta entero. No queda ningún resultado de antes del borrado aplicado después.
 *
 * El cerrojo solo se mantiene mientras dura el bloque protegido, que debe ser corto: el borrado de
 * la base no se hace bajo el cerrojo, porque basta con invalidar la época antes de empezarlo.
 *
 * Sin Android, para poder probarla en la JVM.
 */
class HistoryEpoch {
    private val lock = Any()
    private var epoch = 0L

    /** Época actual, para anotarla al empezar un trabajo. */
    fun current(): Long = synchronized(lock) { epoch }

    /** Invalida todo el trabajo empezado hasta ahora. Devuelve la nueva época. */
    fun invalidate(): Long = synchronized(lock) { ++epoch }

    /** Ejecuta [block] solo si la época no ha cambiado desde [startedAt]; true si se ejecutó. */
    fun ifCurrent(startedAt: Long, block: () -> Unit): Boolean = synchronized(lock) {
        if (startedAt != epoch) return@synchronized false
        block()
        true
    }
}
