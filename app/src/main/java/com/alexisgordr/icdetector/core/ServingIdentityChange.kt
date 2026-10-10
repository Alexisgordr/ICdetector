package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech

/**
 * 3.0 (#19) — Qué es un handover para la app: cambia la identidad completa de la celda
 * servidora (MCC, MNC, TAC, Cell ID o tecnología), no solo el número de celda.
 *
 * Antes se comparaba solo el Cell ID. Dos celdas con el mismo número en otro operador, otra área
 * de seguimiento o en LTE/NR se trataban como la misma: no se escribía la fila del handover, no
 * se reiniciaba el estado de la celda anterior y H10 no contaba ese cambio.
 *
 * Esta misma definición la usan la fila de handover, el reinicio de episodios y H10: no hay una
 * versión más estrecha en ningún sitio.
 *
 * Un campo que uno de los dos lados no conoce (`N/A`, vacío, tecnología desconocida) NO cuenta
 * como cambio: algunos módems dejan de rellenar el MCC/MNC durante una lectura, y contarlo como
 * handover inventaría cambios de celda (y fallos de H10) que no ocurrieron. El Cell ID se compara
 * siempre, como antes.
 */
internal object ServingIdentityChange {

    data class Identity(
        val mcc: String,
        val mnc: String,
        val tac: String,
        val cellId: String,
        val radio: RadioTech
    ) {
        /** Clave con la que se guarda el cambio (misma forma que `identityKey`). */
        val key: String get() = "$mcc-$mnc-$tac-$cellId-${radio.name}"
    }

    fun of(cell: CellData) = Identity(cell.mcc, cell.mnc, cell.tac, cell.cellId, cell.radioTech)

    fun isHandover(previous: Identity?, current: Identity): Boolean {
        if (previous == null) return true
        if (previous.cellId != current.cellId) return true
        return knownAndDifferent(previous.mcc, current.mcc) ||
            knownAndDifferent(previous.mnc, current.mnc) ||
            knownAndDifferent(previous.tac, current.tac) ||
            (previous.radio != current.radio &&
                previous.radio != RadioTech.UNKNOWN && current.radio != RadioTech.UNKNOWN)
    }

    private fun known(value: String) = value.isNotBlank() && value != "N/A"

    private fun knownAndDifferent(a: String, b: String) = known(a) && known(b) && a != b
}
