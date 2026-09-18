package com.alexisgordr.icdetector.core

/**
 * Cuándo puede abrirse un caso forense nuevo.
 *
 * v2.3.3 — Un caso se cierra por tiempo a los 30 minutos (`MAX_CASE_MS`) y libera `caseId`, pero
 * la fase temporal de la celda NO vuelve a cero por ello. Sin esta regla, la condición
 * "hay fase y no hay caso abierto" se cumplía otra vez en el ciclo siguiente: una celda que falla
 * una heurística de forma crónica —un PCI inestable, un baseline desviado— encadenaba casos de
 * 30 minutos indefinidamente, volcando el prebúfer entero cada vez. Cada muestra ronda los 6-10 KB
 * porque lleva el terminal y los diagnósticos, así que el crecimiento en disco no estaba acotado,
 * y un disco lleno apaga la recolección en silencio (ver [CollectionHealth]).
 *
 * La regla: tras cerrar un caso por tiempo, esa identidad queda bloqueada hasta que se observe
 * una recuperación real (fase 0) o hasta que el teléfono cambie de celda. Una anomalía que
 * persiste más de 30 minutos ya está documentada por el primer caso; repetirla no añade evidencia.
 */
object ForensicCasePolicy {

    /** ¿Puede abrirse un caso para esta observación? */
    fun shouldOpenCase(phase: Int, hasOpenCase: Boolean, lockedIdentity: String?, identity: String): Boolean =
        phase > 0 && !hasOpenCase && lockedIdentity != identity

    /**
     * Identidad que queda bloqueada tras este ciclo.
     *
     * - Al cerrar un caso por tiempo agotado, se bloquea la identidad observada.
     * - Una fase 0 (la celda se recuperó) o un cambio de celda levantan el bloqueo.
     * - En cualquier otro caso se conserva el bloqueo anterior.
     */
    fun nextLock(previousLock: String?, identity: String, phase: Int, closedByTimeout: Boolean): String? = when {
        closedByTimeout -> identity
        phase == 0 -> null
        previousLock != null && previousLock != identity -> null
        else -> previousLock
    }
}
