package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.TimingAdvanceUnit

/**
 * 3.0 — Saneado del Timing Advance tal como llega de Android, sin Android: funciones puras para
 * probar rangos y extracción de texto sin construir `CellInfo`.
 *
 *  - LTE (getter `CellSignalStrengthLte.getTimingAdvance`): índice 0..1282.
 *  - GSM (getter `CellSignalStrengthGsm.getTimingAdvance`): índice 0..219.
 *  - NR (getter público `CellSignalStrengthNr.getTimingAdvanceMicros`, API 34+): según la
 *    documentación vigente es el índice T_A crudo de 3GPP TS 38.213 §4.2 (no microsegundos, pese al
 *    nombre), rango 0..3846. Queda como [TimingAdvanceUnit.NR_RAW], sin distancia.
 *  - Texto del fabricante (solo NR): unidad desconocida ([TimingAdvanceUnit.UNKNOWN]), con su propio
 *    límite, separado del getter.
 *
 * Centinelas (`Int.MAX_VALUE`, -1), negativos y valores fuera de rango quedan SIN TA utilizable
 * (null). El 0 es un valor válido y se conserva (la detección de "siempre 0" es otra pieza:
 * TimingAdvanceSanity).
 */
object TimingAdvanceInput {

    fun lte(value: Int?): Int? = value?.takeIf { it in TimingAdvanceUnit.LTE_INDEX_RANGE }

    fun gsm(value: Int?): Int? = value?.takeIf { it in TimingAdvanceUnit.GSM_INDEX_RANGE }

    fun nr(value: Int?): Int? = value?.takeIf { it in NR_GETTER_RANGE }

    /** Rango documentado de `getTimingAdvanceMicros` (índice T_A crudo). No implica metros. */
    val NR_GETTER_RANGE = 0..3846

    /**
     * Límite del valor leído de texto. Su unidad es desconocida: solo se acota para descartar
     * basura y desbordamientos; usa el mismo máximo que el getter porque es el mayor TA que Android
     * documenta para NR, no porque el texto tenga esa unidad.
     */
    val NR_TEXT_RANGE = 0..3846

    /**
     * Valor de TA NR leído del `toString()` del fabricante, solo como contexto ([TimingAdvanceUnit.UNKNOWN]).
     * El nombre del campo debe ir delimitado (`ta=` o `timingAdvance=` al principio o tras un carácter
     * que no sea letra, dígito ni `_`, así que `data=0` o `delta=0` no cuentan) y el número debe ser
     * entero y completo: `ta=12.5`, `ta=-1`, `ta=12abc` o un número que desborda `Int` no se aceptan.
     * Fuera de [NR_TEXT_RANGE] tampoco.
     */
    fun scrapedNr(text: String?): Int? {
        if (text == null) return null
        for (m in FIELD.findAll(text)) {
            val v = m.groupValues[1].toIntOrNull() ?: continue
            if (v in NR_TEXT_RANGE) return v
        }
        return null
    }

    private val FIELD = Regex("""(?<![A-Za-z0-9_])(?:ta|timingAdvance)=(\d+)(?![\w.])""")
}
