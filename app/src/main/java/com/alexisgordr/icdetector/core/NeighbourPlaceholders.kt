package com.alexisgordr.icdetector.core

/**
 * 3.0 (#11) — Valores de relleno en las celdas VECINAS LTE.
 *
 * Algunos módems (visto en Xiaomi/Redmi/POCO con MediaTek) rellenan todas las vecinas con
 * valores inventados en lugar de dejarlas vacías: TAC 65535 (`0xFFFF`) y Cell ID 268435455
 * (`0x0FFFFFFF`, el máximo de 28 bits). Tratados como reales, H5 comparaba el TAC de la servidora
 * con 65535, nunca coincidía y fallaba en cada ciclo; y Stable-Site contaba esas vecinas como
 * identidades completas. En un caso de campo eso llegó a confirmar una falsa alarma.
 *
 * Aquí se leen como "no disponible" (`N/A`), igual que v2.10.5 hizo con el MCC/MNC de las
 * vecinas. Solo en vecinas LTE: la servidora conserva lo que diga el módem, y en NR el TAC es de
 * 24 bits, así que 65535 es un valor válido.
 */
internal object NeighbourPlaceholders {
    const val LTE_TAC_PLACEHOLDER = 65_535
    const val LTE_CELL_ID_PLACEHOLDER = 268_435_455

    fun lteTac(raw: Int, registered: Boolean): String =
        if (!registered && raw == LTE_TAC_PLACEHOLDER) NOT_AVAILABLE else raw.orNotAvailable()

    fun lteCellId(raw: Int, registered: Boolean): String =
        if (!registered && raw == LTE_CELL_ID_PLACEHOLDER) NOT_AVAILABLE else raw.orNotAvailable()

    private const val NOT_AVAILABLE = "N/A"

    /** Igual que el parser: `CellInfo.UNAVAILABLE` (Int.MAX_VALUE) y -1 son "no disponible". */
    private fun Int.orNotAvailable() = if (this == Int.MAX_VALUE || this == -1) NOT_AVAILABLE else toString()
}
