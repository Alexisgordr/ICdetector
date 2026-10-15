package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech

/**
 * 3.0 (#10) — Estación base (eNodeB) de una celda LTE.
 *
 * El Cell ID de LTE (ECI, 28 bits) es el identificador del eNodeB (20 bits) seguido del número
 * de celda dentro de él (8 bits): eNB = CID >> 8. Dos celdas con el mismo eNB en la misma red son
 * sectores o portadoras de la misma estación base.
 *
 * Solo se devuelve con MCC y MNC conocidos: el número de eNodeB solo es único dentro de una red,
 * y comparar dos celdas sin saber su red podría tomar por la misma estación dos de operadores
 * distintos.
 */
internal object LteSite {
    fun key(cell: CellData): String? {
        if (cell.radioTech != RadioTech.LTE) return null
        if (!known(cell.mcc) || !known(cell.mnc)) return null
        val eci = cell.cellId.toLongOrNull()?.takeIf { it in 0..0x0FFF_FFFFL } ?: return null
        return "${cell.mcc}-${cell.mnc}-${eci shr 8}"
    }

    private fun known(value: String) = value.isNotBlank() && value != "N/A"
}
