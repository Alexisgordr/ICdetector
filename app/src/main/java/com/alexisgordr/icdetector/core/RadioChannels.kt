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
 * EARFCN 0..262143, NR-ARFCN 1..3279165, UARFCN 0..16383, ARFCN GSM 0..1023; PCI LTE 0..503,
 * PCI NR 0..1007, PSC UMTS 0..511. GSM sí tiene un identificador físico en la API (BSIC,
 * `CellIdentityGsm.getBsic()`), pero la app no lo lee ni lo guarda en el campo PCI, así que aquí
 * no hay identificador físico GSM que validar.
 *
 * 3.0 — El NR-ARFCN 0 está en el raster pero no pertenece a ninguna banda (NrFrequency no lo
 * clasifica y H8 lo marca). Antes era válido aquí, así que Stable-Site y la confianza local podían
 * aprender una «portadora 0» como huella legítima mientras H8 la marcaba. Un solo criterio: no es
 * válido. El EARFCN 0 sí lo es (Banda 1).
 */
object RadioChannels {
    const val ANDROID_UNAVAILABLE = Int.MAX_VALUE

    fun isValidChannel(radio: RadioTech, value: Int): Boolean =
        value != ANDROID_UNAVAILABLE && when (radio) {
            RadioTech.LTE -> value in 0..262_143
            RadioTech.NR -> value in 1..3_279_165
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

    /**
     * 3.0 — Para el parser: SOLO el centinela "no disponible" de Android pasa a null. Cualquier otro
     * valor medido, aunque esté fuera de rango, se conserva para que H8 pueda seguir juzgándolo.
     * Nunca se convierte en 0.
     */
    fun unavailableToNull(value: Int): Int? = value.takeIf { it != ANDROID_UNAVAILABLE }

    /** La frecuencia si es válida para esa tecnología; null si no. */
    fun channelOrNull(radio: RadioTech, value: Int?): Int? = value?.takeIf { isValidChannel(radio, it) }

    /** El identificador físico si es válido para esa tecnología; null si no. */
    fun physicalIdOrNull(radio: RadioTech, value: Int?): Int? = value?.takeIf { isValidPhysicalId(radio, it) }

    /**
     * 3.0 — ¿Discrepan dos lecturas de RF? Cada lado se valida con SU tecnología. Solo hay discrepancia
     * si ambos valores son válidos, de la misma tecnología (mismo espacio de identificadores) y
     * distintos: desconocido o inválido frente a conocido no es discrepancia.
     */
    data class RfDifference(val physicalId: Boolean, val channel: Boolean)

    fun rfDifference(radioA: RadioTech, pciA: Int?, channelA: Int?, radioB: RadioTech, pciB: Int?, channelB: Int?): RfDifference {
        if (radioA != radioB) return RfDifference(false, false)
        val pa = physicalIdOrNull(radioA, pciA); val pb = physicalIdOrNull(radioB, pciB)
        val ca = channelOrNull(radioA, channelA); val cb = channelOrNull(radioB, channelB)
        return RfDifference(
            physicalId = pa != null && pb != null && pa != pb,
            channel = ca != null && cb != null && ca != cb
        )
    }
}
