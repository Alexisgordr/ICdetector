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
 * El estado vive en memoria y se reconstruye en minutos tras un reinicio. Es a propósito: es una
 * conclusión sobre el hardware, barata de rederivar, y prefiero que se vuelva a comprobar sola
 * antes que arrastrar en disco un veredicto que quizá se emitió con datos pobres.
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

    /**
     * Registra una observación. [cellKey] debe ser la identidad completa de la celda
     * (`MCC-MNC-TAC-CID`); un TA null (el módem declaró honestamente que no hay dato) no aporta
     * evidencia en ninguna dirección y se ignora.
     */
    fun observe(cellKey: String, timingAdvance: Int?) {
        if (timingAdvance == null) return
        if (timingAdvance != 0) {
            hasSeenRealValue = true
            zeroOnlyCells.clear()
            return
        }
        if (!hasSeenRealValue) zeroOnlyCells.add(cellKey)
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
    }
}
