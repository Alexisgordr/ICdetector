package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.TimingAdvanceUnit

/**
 * ¿El Timing Advance que entrega este módem es una medida, o un campo sin rellenar?
 *
 * EL PROBLEMA. Cuando un módem no implementa el reporte de TA, el contrato de Android dice que
 * debe devolver `CellInfo.UNAVAILABLE`. Bastantes no lo cumplen y devuelven **0**. Y un 0 es un
 * valor perfectamente válido: significa "estás a menos de un paso de la antena" (78 m en LTE).
 * Desde una sola observación las dos cosas son idénticas.
 *
 * LA SEÑAL QUE SÍ LAS DISTINGUE es el comportamiento a lo largo del tiempo. Un TA real cambia al
 * cambiar de celda y al moverte; es imposible estar a menos de 78 m de varias antenas distintas en
 * emplazamientos distintos. Un campo sin rellenar, en cambio, vale 0 siempre y en todas partes.
 *
 * POR QUÉ IMPORTA. Un 0 permanente no es inofensivo: entra en la rama de proximidad de H6
 * (`metros <= 100`), de modo que cualquier celda con señal fuerte y sin verificar se llevaría un
 * -15 indefinidamente. Sería un falso positivo perpetuo cuyo origen no es la red, sino el firmware
 * del propio teléfono. Esta clase existe para que eso no ocurra.
 *
 * LA REGLA, deliberadamente asimétrica:
 *
 *  - **Un solo TA distinto de cero demuestra que el módem SÍ reporta.** Esa conclusión se queda
 *    fijada para siempre: a partir de ahí, un 0 posterior es una medida legítima y se trata como
 *    tal. Demostrar que algo funciona necesita una prueba; nada más.
 *  - **Sospechar de un campo vacío necesita más.** Hacen falta [MIN_DISTINCT_CELLS] identidades de
 *    celda distintas, todas reportando 0, para concluir que es un stub. Con una sola celda no se
 *    puede afirmar: quizá de verdad la tienes encima.
 *
 * ── v2.5: LA EVIDENCIA SE PERSISTE, EL VEREDICTO NO ─────────────────────────────────────────
 *
 * Hasta v2.4 todo el estado vivía solo en memoria, con este argumento: es una conclusión sobre el
 * hardware, barata de rederivar, y más vale recomprobarla que arrastrar en disco un veredicto
 * emitido con datos pobres. El argumento sigue siendo bueno; el efecto medido, no.
 *
 * Lo que se vio en 713 filas de campo: **394 muestras etiquetadas `STUB_ZERO` y 319 etiquetadas
 * `LTE_INDEX`, todas con TA = 0.** Mismo módem, mismo cero, dos etiquetas distintas según si el
 * servicio había reiniciado hacía poco. Esa columna del historial deja de ser autoconsistente, y
 * en una campaña de tres meses eso no se arregla después: no hay forma de saber, mirando una fila
 * vieja, si el `LTE_INDEX` significa "medida real" o "todavía no había pruebas suficientes".
 *
 * La solución conserva el argumento original: se persiste la EVIDENCIA (el latch de que el módem
 * reportó alguna vez, y las identidades vistas con cero), nunca la conclusión. [isStub] se sigue
 * derivando de la evidencia en cada consulta, así que un cambio futuro de [MIN_DISTINCT_CELLS]
 * reevalúa el pasado en lugar de heredar un veredicto congelado.
 */
class TimingAdvanceSanity {

    private val zeroOnlyCells = HashSet<String>()

    /** Latched: en cuanto el módem demuestra que reporta de verdad, deja de estar bajo sospecha. */
    var hasSeenRealValue: Boolean = false
        private set

    /** ¿Concluimos que el TA de este teléfono es un campo sin rellenar? */
    val isStub: Boolean
        get() = !hasSeenRealValue && zeroOnlyCells.size >= MIN_DISTINCT_CELLS

    /** Nº de celdas distintas vistas hasta ahora reportando solo 0 (diagnóstico). */
    val zeroOnlyCellCount: Int
        get() = zeroOnlyCells.size

    /** Evidencia acumulada, para que quien llama la guarde entre arranques. Copia defensiva. */
    val zeroOnlyCellKeys: Set<String>
        get() = zeroOnlyCells.toSet()

    /**
     * Restaura la evidencia de un arranque anterior. Devuelve true si el estado visible cambió,
     * para que quien llama sepa si merece la pena repintar el diagnóstico.
     *
     * No restaura un veredicto: [isStub] se recalcula igual que siempre a partir de lo restaurado.
     * Un latch [hasSeenRealValue] a true descarta la lista de ceros, porque ya no aporta nada.
     */
    fun restore(hasSeenRealValue: Boolean, zeroOnlyCellKeys: Set<String>): Boolean {
        val before = isStub
        if (hasSeenRealValue) {
            this.hasSeenRealValue = true
            zeroOnlyCells.clear()
        } else {
            zeroOnlyCells.addAll(zeroOnlyCellKeys.take(MAX_PERSISTED_CELLS))
        }
        return before != isStub
    }

    /**
     * Registra una observación. [cellKey] debe ser la identidad completa de la celda
     * (`MCC-MNC-TAC-CID`); un TA null (el módem declaró honestamente que no hay dato) no aporta
     * evidencia en ninguna dirección y se ignora.
     *
     * Devuelve true si la EVIDENCIA cambió y conviene volver a guardarla en disco. Así el
     * servicio escribe en preferencias solo cuando hay algo nuevo, y no en cada muestra.
     */
    fun observe(cellKey: String, timingAdvance: Int?): Boolean {
        if (timingAdvance == null) return false
        if (timingAdvance != 0) {
            if (hasSeenRealValue) return false
            hasSeenRealValue = true
            zeroOnlyCells.clear()
            return true
        }
        if (hasSeenRealValue) return false
        if (zeroOnlyCells.size >= MAX_PERSISTED_CELLS) return false
        return zeroOnlyCells.add(cellKey)
    }

    /**
     * Unidad efectiva para esta observación: la declarada por el parser, salvo que hayamos
     * concluido que el módem no reporta de verdad — en cuyo caso pasa a [TimingAdvanceUnit.STUB_ZERO]
     * y deja de producir geometría por el camino de siempre.
     */
    fun effectiveUnit(declared: TimingAdvanceUnit, timingAdvance: Int?): TimingAdvanceUnit =
        if (timingAdvance == 0 && isStub) TimingAdvanceUnit.STUB_ZERO else declared

    fun reset() {
        zeroOnlyCells.clear()
        hasSeenRealValue = false
    }

    companion object {
        /**
         * Celdas distintas que deben reportar 0 antes de concluir que el campo no se rellena.
         * Tres es suficiente: estar a menos de 78 m de tres antenas distintas, en tres
         * emplazamientos distintos, no ocurre. Y es lo bastante bajo como para resolverse en los
         * primeros minutos de uso en movimiento.
         */
        const val MIN_DISTINCT_CELLS = 3

        /**
         * Tope de identidades guardadas en disco. La conclusión queda fijada a las
         * [MIN_DISTINCT_CELLS], así que acumular más no cambia nada: solo hincharía las
         * preferencias durante una campaña larga. Se mantiene holgado para que el diagnóstico
         * ("N celdas distintas comprobadas") siga siendo informativo.
         */
        const val MAX_PERSISTED_CELLS = 64
    }
}
