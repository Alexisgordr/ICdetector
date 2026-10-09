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
 *  - Al publicar, se descarta si después de él hubo una pérdida de señal / abstención, o una
 *    entrega con OTRA celda servidora. Sus efectos ya no describen el presente.
 *  - Si lo más reciente es otra entrega de la MISMA celda, se publica: describe la misma celda
 *    unos instantes antes y la nueva la sustituye enseguida. Descartarlo podría dejar la app sin
 *    publicar nada mientras sigan llegando entregas más deprisa de lo que se analizan.
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

    private data class Latest(val generation: Long, val servingIdentity: String?)

    @Volatile private var latest = Latest(0L, null)

    /** Nueva entrega con trabajo real. */
    @Synchronized
    fun deliver(servingIdentity: String): Ticket {
        val next = Latest(latest.generation + 1, servingIdentity)
        latest = next
        return Ticket(next.generation, servingIdentity)
    }

    /** Lista vacía, abstención o pérdida de la servidora: invalida los ciclos en curso. */
    @Synchronized
    fun lose() {
        latest = Latest(latest.generation + 1, null)
    }

    /** Al empezar: solo corre la entrega más reciente. */
    fun isLatest(ticket: Ticket): Boolean = latest.generation == ticket.generation

    /** Al publicar: ver las reglas de la clase. */
    fun mayPublish(ticket: Ticket): Boolean {
        val now = latest
        if (now.generation == ticket.generation) return true
        return now.servingIdentity != null && now.servingIdentity == ticket.servingIdentity
    }
}
