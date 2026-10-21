package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech
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
 * LA REGLA (3.1.0, #43): una ventana de las últimas [WINDOW] observaciones de cada unidad.
 *
 *  - **REAL** si más del [REAL_MIN_SHARE] de la ventana son valores distintos de cero, repartidos
 *    en al menos [MIN_REAL_CELLS] celdas distintas: el módem mide de verdad.
 *  - **STUB** si la ventana tiene [MIN_DISTINCT_CELLS] o más celdas distintas y ningún valor
 *    distinto de cero: estar a menos de un paso de TA de tres emplazamientos no ocurre.
 *  - Si no se cumple ninguna, se mantiene el veredicto anterior (al principio, ninguno: el cero se
 *    trata como medida, igual que antes). Así un tramo largo en una sola celda no cambia nada.
 *
 * Hasta 3.0 era un latch de un sentido: el primer TA distinto de cero fijaba «reporta de verdad»
 * para siempre, se guardaba, y desde ahí ningún cero volvía a ser sospechoso. Un solo valor espurio
 * (un módem que publica un TA basura una vez, o una celda vecina mal atribuida) desactivaba la
 * detección del stub de forma permanente. Con la ventana, un valor aislado es menos del 5 % y de
 * una sola celda: no basta. Y las dos transiciones son posibles (STUB→REAL→STUB) si los datos lo
 * justifican, por ejemplo tras una actualización del firmware del módem.
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
 * La solución conserva el argumento original: se persiste la EVIDENCIA. Desde 3.1.0 (#43) es el
 * resumen de la ventana ([summary]: tramos de celda + cero/no cero + recuento) y, solo para el caso
 * indeciso, el último veredicto; al restaurar, si la ventana decide por sí misma, manda la ventana,
 * así que un cambio futuro de los umbrales reevalúa el pasado. La evidencia de 3.0 (latch y celdas a
 * cero) se migra sin el latch ([restoreLegacy]): un latch antiguo no fuerza REAL.
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
 *
 * ── 3.0: QUÉ SE CUENTA COMO «DISTINTO» ([evidenceKey]) ──────────────────────────────────────
 *
 * Antes la clave era la identidad completa de la celda (MCC-MNC-TAC-CID-tecnología), así que
 * contaban como tres: tres sectores o portadoras del MISMO eNodeB (justo el caso de quien vive a
 * menos de 78 m de un emplazamiento), una lectura con CID «N/A» y la misma celda con el TAC
 * primero en «N/A» y luego rellenado. Ahora: con el CID ilegible no se observa ningún cero; en LTE
 * cuenta el eNodeB ([LteSite.key]); en las demás tecnologías, la tecnología y el CID, sin TAC.
 * Las claves guardadas por versiones anteriores se convierten al restaurar ([normalizeStoredKey]).
 */
class TimingAdvanceSanity {

    /** 3.1.0 (#43) — Veredicto de una unidad. UNKNOWN: todavía sin datos que decidan. */
    enum class Verdict { UNKNOWN, REAL, STUB }

    /** Una observación de la ventana: la celda ([evidenceKey], null si ilegible) y si el TA no era 0. */
    private data class Obs(val key: String?, val nonZero: Boolean)

    /** Ventana de una unidad y el veredicto vigente. */
    private class Evidence {
        val window = ArrayDeque<Obs>()
        var verdict = Verdict.UNKNOWN
        var sincePersist = 0

        /** Lo que dice la ventana por sí sola, o null si no decide. */
        fun decide(): Verdict? {
            if (window.isEmpty()) return null
            val nonZero = window.count { it.nonZero }
            if (nonZero == 0) {
                val cells = window.mapNotNullTo(HashSet()) { it.key }
                return if (cells.size >= MIN_DISTINCT_CELLS) Verdict.STUB else null
            }
            val realCells = window.filter { it.nonZero }.mapNotNullTo(HashSet()) { it.key }
            return if (nonZero.toDouble() / window.size > REAL_MIN_SHARE && realCells.size >= MIN_REAL_CELLS) Verdict.REAL else null
        }

        fun add(obs: Obs) {
            window.addLast(obs)
            while (window.size > WINDOW) window.removeFirst()
        }
    }

    private val evidence = HashMap<TimingAdvanceUnit, Evidence>()

    private fun of(unit: TimingAdvanceUnit): Evidence = evidence.getOrPut(unit) { Evidence() }

    /** Veredicto vigente de la unidad. */
    fun verdict(unit: TimingAdvanceUnit): Verdict = evidence[unit]?.verdict ?: Verdict.UNKNOWN

    /** ¿Concluimos que el TA de esta unidad es un campo sin rellenar? */
    fun isStub(unit: TimingAdvanceUnit): Boolean = verdict(unit) == Verdict.STUB

    /** ¿Alguna unidad es un campo sin rellenar? (diagnóstico) */
    val anyStub: Boolean get() = evidence.values.any { it.verdict == Verdict.STUB }

    /** Nº de celdas distintas de la ventana que solo han dado 0 en esta unidad (diagnóstico). */
    fun zeroOnlyCellCount(unit: TimingAdvanceUnit): Int = evidence[unit]?.let(::zeroOnlyCells)?.size ?: 0

    /** El mayor recuento entre unidades, para el diagnóstico general del terminal. */
    val zeroOnlyCellCount: Int get() = evidence.values.maxOfOrNull { zeroOnlyCells(it).size } ?: 0

    private fun zeroOnlyCells(e: Evidence): Set<String> {
        val nonZero = e.window.filter { it.nonZero }.mapNotNullTo(HashSet()) { it.key }
        return e.window.mapNotNullTo(HashSet()) { it.key } - nonZero
    }

    /** Observaciones en la ventana de la unidad (diagnóstico y pruebas). */
    fun windowSize(unit: TimingAdvanceUnit): Int = evidence[unit]?.window?.size ?: 0

    /**
     * Registra una observación con la unidad que declaró el parser. [cellKey] es [evidenceKey] de la
     * celda; null (CID ilegible, LTE sin red conocida) cuenta en los porcentajes pero no como celda.
     * Un TA null no aporta evidencia y se ignora.
     *
     * Devuelve true si conviene volver a guardar la evidencia: al cambiar el veredicto y, si no, cada
     * [PERSIST_EVERY] observaciones (la ventana cambia en cada una; guardarla siempre sería escribir
     * en disco cada ciclo).
     */
    fun observe(unit: TimingAdvanceUnit, cellKey: String?, timingAdvance: Int?): Boolean {
        if (timingAdvance == null || unit !in EVIDENCE_UNITS) return false
        val e = of(unit)
        e.add(Obs(cellKey, timingAdvance != 0))
        val before = e.verdict
        e.verdict = e.decide() ?: e.verdict
        e.sincePersist++
        if (e.verdict != before || e.sincePersist >= PERSIST_EVERY) {
            e.sincePersist = 0
            return true
        }
        return false
    }

    /** Observa una celda con su clave de emplazamiento ([evidenceKey]); ver [observe]. */
    fun observe(cell: CellData): Boolean = observe(cell.timingAdvanceUnit, evidenceKey(cell), cell.timingAdvance)

    /**
     * Unidad efectiva para esta observación: la declarada por el parser, salvo que esa misma
     * unidad se haya concluido como campo sin rellenar, en cuyo caso pasa a
     * [TimingAdvanceUnit.STUB_ZERO] y deja de producir geometría.
     */
    fun effectiveUnit(declared: TimingAdvanceUnit, timingAdvance: Int?): TimingAdvanceUnit =
        if (timingAdvance == 0 && isStub(declared)) TimingAdvanceUnit.STUB_ZERO else declared

    /**
     * 3.1.0 (#43) — La celda con su unidad efectiva ([effectiveUnit]) y marcada como
     * [CellData.timingAdvanceUnverified] si trae TA y su unidad aún no tiene veredicto: mientras no
     * se sepa si el módem mide o devuelve un 0 de relleno, H6 no juzga la proximidad (`ta_sin_verificar`).
     */
    fun annotate(cell: CellData): CellData {
        val declared = cell.timingAdvanceUnit
        val effective = effectiveUnit(declared, cell.timingAdvance)
        val pending = cell.timingAdvance != null && declared in EVIDENCE_UNITS && verdict(declared) == Verdict.UNKNOWN
        return if (effective == declared && pending == cell.timingAdvanceUnverified) cell
        else cell.copy(timingAdvanceUnit = effective, timingAdvanceUnverified = pending)
    }

    /**
     * Resumen de la ventana de [unit] para guardarlo entre arranques: `v2|VEREDICTO|tramos`, con
     * cada tramo `recuento,0|1,clave` (observaciones seguidas de la misma celda y del mismo tipo)
     * separados por `;`. Cabe en pocos bytes: en uso normal la ventana son unos pocos tramos largos.
     */
    fun summary(unit: TimingAdvanceUnit): String {
        val e = evidence[unit] ?: return "$SUMMARY_VERSION|${Verdict.UNKNOWN}|"
        val runs = ArrayList<Triple<Int, Boolean, String?>>()
        e.window.forEach { o ->
            val last = runs.lastOrNull()
            if (last != null && last.second == o.nonZero && last.third == o.key) runs[runs.size - 1] = last.copy(first = last.first + 1)
            else runs += Triple(1, o.nonZero, o.key)
        }
        return "$SUMMARY_VERSION|${e.verdict}|" + runs.joinToString(";") { (n, nz, key) -> "$n,${if (nz) 1 else 0},${key.orEmpty()}" }
    }

    /**
     * Restaura la ventana guardada con [summary]. Si la ventana decide por sí sola, manda ella; si
     * no, se conserva el veredicto guardado. Un resumen ilegible se ignora (se empieza de cero).
     * Devuelve true si el estado visible ([isStub]) cambió.
     */
    fun restore(unit: TimingAdvanceUnit, summary: String): Boolean {
        if (unit !in EVIDENCE_UNITS) return false
        val parts = summary.split("|", limit = 3)
        if (parts.size != 3 || parts[0] != SUMMARY_VERSION) return false
        val stored = runCatching { Verdict.valueOf(parts[1]) }.getOrNull() ?: return false
        val obs = ArrayList<Obs>()
        if (parts[2].isNotEmpty()) for (run in parts[2].split(";")) {
            val f = run.split(",", limit = 3)
            val n = f.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..WINDOW } ?: return false
            val nonZero = when (f.getOrNull(1)) { "1" -> true; "0" -> false; else -> return false }
            val key = f.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { normalizeStoredKey(it) ?: return false }
            repeat(n) { obs += Obs(key, nonZero) }
        }
        val e = of(unit)
        val before = e.verdict
        e.window.clear()
        obs.takeLast(WINDOW).forEach(e::add)
        e.verdict = e.decide() ?: stored
        e.sincePersist = 0
        return (before == Verdict.STUB) != (e.verdict == Verdict.STUB)
    }

    /**
     * Migración de la evidencia de 3.0 (#33): las celdas guardadas como «solo a 0» entran en la
     * ventana como un cero cada una. El latch antiguo («reportó alguna vez») NO se migra: era
     * justo lo que un valor espurio fijaba para siempre. Devuelve true si [isStub] cambió.
     */
    fun restoreLegacy(unit: TimingAdvanceUnit, zeroOnlyCellKeys: Set<String>): Boolean {
        if (unit !in EVIDENCE_UNITS) return false
        val e = of(unit)
        val before = e.verdict
        zeroOnlyCellKeys.mapNotNull(::normalizeStoredKey).distinct().take(WINDOW).forEach { e.add(Obs(it, false)) }
        e.verdict = e.decide() ?: e.verdict
        return (before == Verdict.STUB) != (e.verdict == Verdict.STUB)
    }

    fun reset() {
        evidence.clear()
    }

    companion object {
        /** 3.1.0 (#43) — Observaciones de la ventana por unidad. A 10 s por lectura, unos 33 min. */
        const val WINDOW = 200

        /** Proporción de valores distintos de cero que hace falta superar para concluir REAL. */
        const val REAL_MIN_SHARE = 0.05

        /** Celdas distintas en las que deben aparecer esos valores: uno solo de una celda no basta. */
        const val MIN_REAL_CELLS = 2

        /**
         * Claves de evidencia distintas ([evidenceKey]) que deben reportar 0, sin ningún valor
         * distinto de cero en la ventana, antes de concluir que el campo no se rellena. En LTE son
         * tres eNodeB: estar a menos de 78 m de tres emplazamientos distintos no ocurre. En las demás
         * tecnologías son tres CID (no se puede deducir el emplazamiento), lo que puede incluir
         * sectores de uno mismo.
         */
        const val MIN_DISTINCT_CELLS = 3

        /** Cada cuántas observaciones sin cambio de veredicto se vuelve a guardar la ventana. */
        const val PERSIST_EVERY = 30

        const val SUMMARY_VERSION = "v2"

        /**
         * 3.0 — Qué identifica un emplazamiento «distinto» para la evidencia del stub: el eNodeB en
         * LTE (sectores y portadoras del mismo eNodeB cuentan una vez; sin MCC/MNC conocidos no se
         * cuenta), tecnología + CID en las demás. Null si el CID es ilegible.
         */
        fun evidenceKey(cell: CellData): String? {
            if (!known(cell.cellId)) return null
            if (cell.radioTech == RadioTech.LTE) return LteSite.key(cell)?.let { "site:LTE:$it" }
            return "cell:${cell.radioTech.name}:${cell.cellId}"
        }

        /**
         * Clave guardada → clave actual. Las anteriores eran la identidad completa
         * (`mcc-mnc-tac-cid-RADIO`); se convierten para no contar dos veces el mismo emplazamiento.
         * Una clave que no se reconoce o con CID ilegible se descarta.
         */
        fun normalizeStoredKey(key: String): String? {
            if (key.startsWith("site:") || key.startsWith("cell:")) return key
            val p = key.split("-")
            if (p.size != 5) return null
            val radio = runCatching { RadioTech.valueOf(p[4]) }.getOrNull() ?: return null
            return evidenceKey(CellData(isRegistered = true, networkType = radio.name, cellId = p[3], mnc = p[1],
                tac = p[2], dbm = 0, mcc = p[0], radioTech = radio))
        }

        private fun known(value: String) = value.isNotBlank() && value != "N/A"

        /** 3.0 (#33) — Unidades con evidencia propia. STUB_ZERO es un resultado, no una unidad declarada. */
        val EVIDENCE_UNITS: List<TimingAdvanceUnit> = TimingAdvanceUnit.values().filter { it != TimingAdvanceUnit.STUB_ZERO }
    }
}
