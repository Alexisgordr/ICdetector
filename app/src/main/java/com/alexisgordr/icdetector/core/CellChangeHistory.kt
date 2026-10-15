package com.alexisgordr.icdetector.core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 3.0 (corte de campaña) — Historial de cambios de servidora que lee H10 ([PingPongRule]).
 *
 * Antes el cambio se guardaba al PUBLICAR el ciclo (checkAlerts), después de ThreatAnalyzer: el
 * cambio observado en un ciclo no entraba en el historial que ese mismo ciclo analizaba, y el
 * tercer cambio se detectaba un ciclo tarde. Ahora el servicio registra cada lectura al recibirla
 * ([observe]), antes de lanzar su análisis, y el análisis lee una copia ([snapshot]).
 *
 * Un handover es el de [ServingIdentityChange]: identidad completa, y un campo desconocido o un CID
 * ilegible no cuentan como cambio. Este historial lleva su propia identidad anterior, separada de la
 * que usa la publicación, porque registra también las lecturas cuyo análisis se descarta después.
 *
 * Reloj: el de [observe] y el `nowMs` del análisis deben ser el mismo (el servicio usa la hora de
 * pared en ambos, como PingPongRule.confirmationKey).
 */
internal class CellChangeHistory(private val windowMs: Long = PingPongRule.WINDOW_MS) {
    private val changes = CopyOnWriteArrayList<Pair<String, Long>>()
    private var previous: ServingIdentityChange.Identity? = null

    /** Lectura de la servidora. Devuelve true si es un cambio de identidad (queda guardado). */
    @Synchronized
    fun observe(current: ServingIdentityChange.Identity, nowMs: Long): Boolean {
        val step = ServingIdentityChange.step(previous, current)
        previous = step.next
        if (step.handover) {
            changes.add(current.key to nowMs)
            changes.removeAll { nowMs - it.second > windowMs }
        }
        return step.handover
    }

    /** Copia para un análisis: no cambia aunque llegue otra lectura mientras se analiza. */
    fun snapshot(): List<Pair<String, Long>> = changes.toList()

    val size: Int get() = changes.size

    /** Historial borrado o servicio reiniciado. */
    @Synchronized
    fun clear() {
        changes.clear()
        previous = null
    }
}
