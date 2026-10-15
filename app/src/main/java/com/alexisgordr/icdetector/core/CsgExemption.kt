package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellReputation

/**
 * 3.0 (corte de campaña) — Cuándo una celda CSG (femtocelda) deja H1, H2, H5, H6 y H7 sin evaluar.
 *
 * El indicador CSG llega en el SIB1: lo emite la propia celda, así que con él solo, un emisor hostil
 * apagaba cinco reglas con un bit. Ahora la exención exige que esa identidad completa ya tenga
 * historial local PREVIO a esta observación. Una celda CSG nueva se evalúa como cualquier otra.
 *
 * Fuente: [CellReputation] (CellDbHelper.getCellReputation), por tres razones:
 *  - va por identidad completa (CID, MNC, TAC, MCC y tecnología) y mira 90 días;
 *  - se lee antes de guardar la fila de este ciclo, así que es historial previo;
 *  - cuenta TODAS las filas guardadas, limpias o no. El historial de H11 y Stable-Site solo
 *    aprenden de filas limpias, y una femtocelda nueva falla H2/H5/H7 sus primeros días: con esas
 *    fuentes nunca llegaría a calificar.
 * Umbral: el mismo con el que la reputación considera conocida una celda (10 observaciones en 2 días
 * distintos o más): una tarde sola no basta.
 *
 * Coste asumido: una femtocelda legítima se evalúa como una celda normal hasta tener ese historial
 * (puede alarmar el primer día), y un emisor CSG que insista dos días queda exento a partir de
 * entonces, aunque durante esos días se ha evaluado entero.
 */
internal object CsgExemption {
    const val MIN_OBSERVATIONS = 10
    const val MIN_DISTINCT_DAYS = 2

    fun applies(csgIndicator: Boolean, reputation: CellReputation?): Boolean =
        csgIndicator && reputation != null &&
            reputation.observations >= MIN_OBSERVATIONS && reputation.distinctDays >= MIN_DISTINCT_DAYS
}
