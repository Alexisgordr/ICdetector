package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.RadioTech

/**
 * 3.0 (#22) — Un único validador de frecuencia (ARFCN) e identificador físico (PCI/PSC) por
 * tecnología, para todo el código que aprende o juzga con ellos.
 *
 * Antes había tres criterios distintos: H8 aceptaba el EARFCN 0 (Banda 1, válido), Stable-Site
 * tenía sus rangos por tecnología, y el aprendizaje del historial (confianza local, H15) usaba
 * `> 0` para la frecuencia y `0..1007` para cualquier PCI. Ese `> 0` tiraba el EARFCN 0: una
 * portadora real de Banda 1 no podía aprender sus PCI conocidos y H15 la mezclaba con las filas
 * que de verdad no tienen frecuencia.
 *
 * `CellInfo.UNAVAILABLE` (Int.MAX_VALUE) nunca es válido. Los rangos son los de 3GPP:
 * EARFCN 0..262143, NR-ARFCN 0..3279165, UARFCN 0..16383, ARFCN GSM 0..1023; PCI LTE 0..503,
 * PCI NR 0..1007, PSC UMTS 0..511. GSM no tiene identificador físico en la API.
 */
object RadioChannels {
    const val ANDROID_UNAVAILABLE = Int.MAX_VALUE

    fun isValidChannel(radio: RadioTech, value: Int): Boolean =
        value != ANDROID_UNAVAILABLE && when (radio) {
            RadioTech.LTE -> value in 0..262_143
            RadioTech.NR -> value in 0..3_279_165
            RadioTech.UMTS -> value in 0..16_383
            RadioTech.GSM -> value in 0..1_023
            RadioTech.UNKNOWN -> false
        }

    fun isValidPhysicalId(radio: RadioTech, value: Int): Boolean =
        value != ANDROID_UNAVAILABLE && when (radio) {
            RadioTech.LTE -> value in 0..503
            RadioTech.NR -> value in 0..1_007
            RadioTech.UMTS -> value in 0..511
            RadioTech.GSM -> false
            RadioTech.UNKNOWN -> false
        }

    /** La frecuencia si es válida para esa tecnología; null si no. */
    fun channelOrNull(radio: RadioTech, value: Int?): Int? = value?.takeIf { isValidChannel(radio, it) }

    /** El identificador físico si es válido para esa tecnología; null si no. */
    fun physicalIdOrNull(radio: RadioTech, value: Int?): Int? = value?.takeIf { isValidPhysicalId(radio, it) }
}
