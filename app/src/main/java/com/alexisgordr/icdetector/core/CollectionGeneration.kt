package com.alexisgordr.icdetector.core

/**
 * 3.0 (#24) — ¿Sigue siendo válido publicar el resultado de un ciclo de análisis?
 *
 * Cada entrega de celdas con trabajo real lanza un ciclo que analiza en segundo plano y después
 * publica en el hilo principal: pantalla, notificación, alertas, historial, incidentes y caja
 * negra. Mientras analiza puede llegar algo más nuevo. Antes solo se comprobaba al EMPEZAR (al
 * tomar el mutex), y una lista vacía o una abstención ni siquiera contaban como "algo más
 * nuevo": un ciclo lento podía pintar una celda y alertar después de que la señal se hubiera
 * perdido, o sobre una celda que ya no era la servidora.
 *
 * Reglas:
 *  - Al empezar, un ciclo que ya no es la entrega más reciente se descarta, como antes: la más
 *    reciente correrá después.
 *  - Una INTERRUPCIÓN invalida para siempre todos los ciclos anteriores a ella. Es interrupción
 *    una pérdida (lista vacía, abstención, modo avión, refresco forzado) y una entrega cuya
 *    celda servidora es distinta de la anterior. Que después vuelva la misma celda no los
 *    rehabilita: A → pérdida → A y A → B → A dejan inválido el primer ciclo de A.
 *  - Sin interrupción, varias entregas seguidas de la MISMA celda no se invalidan entre sí: el
 *    ciclo anterior describe la misma celda unos instantes antes y el nuevo lo sustituye
 *    enseguida. Descartarlo podría dejar la app sin publicar nada mientras sigan llegando
 *    entregas más deprisa de lo que se analizan.
 *
 * Lo que el ciclo ya registró antes de publicar (evidencia del TA, contexto agregado de
 * Stable-Site por minuto) se conserva: son observaciones reales de ese momento.
 *
 * Las entregas, pérdidas y publicaciones ocurren en el hilo principal; los ciclos leen desde
 * otros hilos, de ahí el @Volatile.
 */
class CollectionGeneration {

    /** Lo que identifica a un ciclo: su número y la celda servidora que analiza. */
    data class Ticket(val generation: Long, val servingIdentity: String)

    private data class State(
        val generation: Long,
        val servingIdentity: String?,
        /** Número de la última interrupción. Los tickets anteriores a ella ya no publican. */
        val lastInterruption: Long
    )

    @Volatile private var state = State(0L, null, 0L)

    /** Nueva entrega con trabajo real. Un cambio de celda servidora es una interrupción. */
    @Synchronized
    fun deliver(servingIdentity: String): Ticket {
        val current = state
        val generation = current.generation + 1
        val interrupted = current.servingIdentity != servingIdentity
        state = State(
            generation = generation,
            servingIdentity = servingIdentity,
            lastInterruption = if (interrupted) generation else current.lastInterruption
        )
        return Ticket(generation, servingIdentity)
    }

    /** Lista vacía, abstención, modo avión o pantalla vaciada: invalida los ciclos en curso. */
    @Synchronized
    fun lose() {
        val generation = state.generation + 1
        state = State(generation = generation, servingIdentity = null, lastInterruption = generation)
    }

    /** Al empezar: solo corre la entrega más reciente. */
    fun isLatest(ticket: Ticket): Boolean = state.generation == ticket.generation

    /** Al publicar: ninguna interrupción desde que se entregó este ciclo. */
    fun mayPublish(ticket: Ticket): Boolean = state.lastInterruption <= ticket.generation
}
