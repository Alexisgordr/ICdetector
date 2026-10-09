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
 *
 * ── 3.0 (#33): LA EVIDENCIA ES POR TECNOLOGÍA / UNIDAD ─────────────────────────────────────
 *
 * Hasta 2.10.x el latch era uno para todo el teléfono: el primer TA distinto de cero de CUALQUIER
 * tecnología lo fijaba para siempre. Un LTE que reporta de verdad impedía detectar que el camino
 * GSM (o NR) del mismo módem devuelve 0 siempre, y ese 0 entraba en la rama de proximidad de H6
 * como una medida real. Ahora cada unidad declarada por el parser (LTE_INDEX, GSM_INDEX, NR_RAW,
 * UNKNOWN) tiene su propio latch y su propia lista de celdas a cero.
 *
 * La evidencia guardada por versiones anteriores no dice de qué tecnología vino, así que NO se
 * atribuye a ninguna: cada unidad empieza vacía y se rederiva en unos minutos de uso. Igual que
 * antes, un TA 0 aislado sigue siendo legítimo, un TA nulo no aporta evidencia y distintas
 * identidades no se dan por emplazamientos distintos más allá del umbral [MIN_DISTINCT_CELLS].
 */
class TimingAdvanceSanity {

    /** Evidencia de una unidad: el latch de "reporta de verdad" y las celdas vistas solo a 0. */
    private class Evidence {
        var hasSeenRealValue = false
        val zeroOnlyCells = HashSet<String>()
        val isStub: Boolean get() = !hasSeenRealValue && zeroOnlyCells.size >= MIN_DISTINCT_CELLS
    }

    private val evidence = HashMap<TimingAdvanceUnit, Evidence>()

    private fun of(unit: TimingAdvanceUnit): Evidence = evidence.getOrPut(unit) { Evidence() }

    /** Latched por unidad: en cuanto esa unidad demuestra que reporta de verdad, deja de estar bajo sospecha. */
    fun hasSeenRealValue(unit: TimingAdvanceUnit): Boolean = evidence[unit]?.hasSeenRealValue == true

    /** ¿Concluimos que el TA de esta unidad es un campo sin rellenar? */
    fun isStub(unit: TimingAdvanceUnit): Boolean = evidence[unit]?.isStub == true

    /** ¿Alguna unidad es un campo sin rellenar? (diagnóstico) */
    val anyStub: Boolean get() = evidence.values.any { it.isStub }

    /** Nº de celdas distintas vistas reportando solo 0 en esta unidad (diagnóstico). */
    fun zeroOnlyCellCount(unit: TimingAdvanceUnit): Int = evidence[unit]?.zeroOnlyCells?.size ?: 0

    /** El mayor recuento entre unidades, para el diagnóstico general del terminal. */
    val zeroOnlyCellCount: Int get() = evidence.values.maxOfOrNull { it.zeroOnlyCells.size } ?: 0

    /** Evidencia de una unidad, para que quien llama la guarde entre arranques. Copia defensiva. */
    fun zeroOnlyCellKeys(unit: TimingAdvanceUnit): Set<String> = evidence[unit]?.zeroOnlyCells?.toSet().orEmpty()

    /**
     * Restaura la evidencia de una unidad de un arranque anterior. Devuelve true si su estado
     * visible cambió. No restaura un veredicto: [isStub] se recalcula a partir de lo restaurado.
     */
    fun restore(unit: TimingAdvanceUnit, hasSeenRealValue: Boolean, zeroOnlyCellKeys: Set<String>): Boolean {
        if (unit !in EVIDENCE_UNITS) return false
        val e = of(unit)
        val before = e.isStub
        if (hasSeenRealValue) {
            e.hasSeenRealValue = true
            e.zeroOnlyCells.clear()
        } else {
            e.zeroOnlyCells.addAll(zeroOnlyCellKeys.take(MAX_PERSISTED_CELLS))
        }
        return before != e.isStub
    }

    /**
     * Registra una observación con la unidad que declaró el parser. [cellKey] debe ser la
     * identidad completa de la celda; un TA null no aporta evidencia y se ignora.
     *
     * Devuelve true si la EVIDENCIA cambió y conviene volver a guardarla en disco.
     */
    fun observe(unit: TimingAdvanceUnit, cellKey: String, timingAdvance: Int?): Boolean {
        if (timingAdvance == null || unit !in EVIDENCE_UNITS) return false
        val e = of(unit)
        if (timingAdvance != 0) {
            if (e.hasSeenRealValue) return false
            e.hasSeenRealValue = true
            e.zeroOnlyCells.clear()
            return true
        }
        if (e.hasSeenRealValue) return false
        if (e.zeroOnlyCells.size >= MAX_PERSISTED_CELLS) return false
        return e.zeroOnlyCells.add(cellKey)
    }

    /**
     * Unidad efectiva para esta observación: la declarada por el parser, salvo que esa misma
     * unidad se haya concluido como campo sin rellenar, en cuyo caso pasa a
     * [TimingAdvanceUnit.STUB_ZERO] y deja de producir geometría.
     */
    fun effectiveUnit(declared: TimingAdvanceUnit, timingAdvance: Int?): TimingAdvanceUnit =
        if (timingAdvance == 0 && isStub(declared)) TimingAdvanceUnit.STUB_ZERO else declared

    fun reset() {
        evidence.clear()
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
         * Tope de identidades guardadas en disco por unidad. La conclusión queda fijada a las
         * [MIN_DISTINCT_CELLS], así que acumular más no cambia nada: solo hincharía las
         * preferencias durante una campaña larga.
         */
        const val MAX_PERSISTED_CELLS = 64

        /** 3.0 (#33) — Unidades con evidencia propia. STUB_ZERO es un resultado, no una unidad declarada. */
        val EVIDENCE_UNITS: List<TimingAdvanceUnit> = TimingAdvanceUnit.values().filter { it != TimingAdvanceUnit.STUB_ZERO }
    }
}
