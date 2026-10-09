package com.alexisgordr.icdetector.core

/**
 * 3.0 (#8) — Frecuencia de bajada de un NR-ARFCN (raster global, TS 38.104 Tabla 5.4.2.1-1).
 *
 * En NR, a diferencia de LTE, un NR-ARFCN no identifica una sola banda: las bandas se solapan
 * (632448 está en n77 y en n78; 151600 en n14, n28 y n67). Por eso aquí no se resuelve una banda:
 * para H14 basta con saber si la portadora es alta o baja, y eso lo da la frecuencia, que el
 * NR-ARFCN determina de forma exacta.
 *
 *   0 ≤ N < 600000:        F = 0,005 MHz · N
 *   600000 ≤ N ≤ 2016666:  F = 3000 + 0,015 · (N − 600000)
 *   2016667 ≤ N ≤ 3279165: F = 24250,08 + 0,06 · (N − 2016667)
 *
 * El NR-ARFCN 0 (0 MHz) no pertenece a ninguna banda: no se clasifica.
 */
object NrFrequency {
    fun dlMhz(nrArfcn: Int?): Double? = when (nrArfcn) {
        null, 0 -> null
        in 1..599_999 -> nrArfcn * 0.005
        in 600_000..2_016_666 -> 3000.0 + 0.015 * (nrArfcn - 600_000)
        in 2_016_667..3_279_165 -> 24_250.08 + 0.06 * (nrArfcn - 2_016_667)
        else -> null
    }

    fun isLow(nrArfcn: Int?): Boolean = dlMhz(nrArfcn)?.let { it < BandPlan.LOW_BAND_LIMIT_MHZ } == true

    fun isHigh(nrArfcn: Int?): Boolean = dlMhz(nrArfcn)?.let { it >= BandPlan.LOW_BAND_LIMIT_MHZ } == true
}
