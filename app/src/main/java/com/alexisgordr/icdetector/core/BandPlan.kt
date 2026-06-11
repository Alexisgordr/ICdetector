package com.alexisgordr.icdetector.core

/**
 * Mapeo EARFCN (LTE) -> banda física 3GPP y clasificación alta/baja frecuencia.
 *
 * Rangos de EARFCN de bajada (downlink) según 3GPP TS 36.101, Tabla 5.7.3-1.
 * Solo LTE: el NR usa NR-ARFCN con otra tabla totalmente distinta, así que
 * NO se mezcla aquí a propósito (mapearlo con esta tabla daría bandas falsas).
 *
 * Objetivo defensivo: poder razonar sobre un "band downgrade" intra-LTE, es decir,
 * un salto forzado desde una banda alta urbana (1800/2100/2600 MHz) a una banda
 * baja sub-GHz (800/900/700 MHz). Las bandas sub-GHz penetran paredes y cubren
 * un radio enorme con poca potencia, por lo que son las favoritas de un equipo
 * táctico que quiere barrer una zona desde una furgoneta. Detectar ese salto
 * (cuando NO está justificado por una degradación física progresiva de la señal)
 * es una señal defensiva útil.
 *
 *
 */
object BandPlan {

    /** band -> Triple(earfcnDlMin, earfcnDlMax, frecuenciaAproxMHz) */
    private val LTE_BANDS: Map<Int, Triple<Int, Int, Int>> = mapOf(
        1  to Triple(0,     599,   2110), // 2100 MHz
        2  to Triple(600,   1199,  1930), // 1900 MHz
        3  to Triple(1200,  1949,  1805), // 1800 MHz
        4  to Triple(1950,  2399,  2110), // AWS
        5  to Triple(2400,  2649,  869),  // 850 MHz  (sub-GHz)
        7  to Triple(2750,  3449,  2620), // 2600 MHz
        8  to Triple(3450,  3799,  925),  // 900 MHz  (sub-GHz)
        12 to Triple(5010,  5179,  729),  // 700 MHz  (sub-GHz)
        13 to Triple(5180,  5279,  746),  // 700 MHz  (sub-GHz)
        20 to Triple(6150,  6449,  791),  // 800 MHz  (sub-GHz) — banda táctica clásica en Europa
        26 to Triple(8690,  9039,  859),  // 850 ext. (sub-GHz)
        28 to Triple(9210,  9659,  758),  // 700 MHz  (sub-GHz)
        32 to Triple(9920,  10359, 1474), // 1500 MHz SDL
        38 to Triple(37750, 38249, 2595), // 2600 TDD
        40 to Triple(38650, 39649, 2350), // 2300 TDD
        41 to Triple(39650, 41589, 2593), // 2500 TDD
        71 to Triple(68586, 69465, 617)   // 600 MHz  (sub-GHz)
    )

    /** Devuelve el número de banda LTE para un EARFCN, o null si está fuera de tabla. */
    fun earfcnToBandLte(earfcn: Int): Int? {
        if (earfcn < 0) return null  // EARFCN 0 es válido (Banda 1, rango 0..599); solo el negativo es imposible
        return LTE_BANDS.entries.firstOrNull { (_, range) ->
            earfcn in range.first..range.second
        }?.key
    }

    /** Frecuencia aproximada (MHz) de una banda, o null si desconocida. */
    fun approxFreqMhz(band: Int): Int? = LTE_BANDS[band]?.third

    /**
     * Banda "baja" = sub-GHz (< 1000 MHz). Son las de gran alcance y penetración:
     * 5, 8, 12, 13, 20, 26, 28, 71. Las que un catcher táctico prefiere para barrer.
     */
    fun isLowBand(band: Int?): Boolean {
        val f = band?.let { approxFreqMhz(it) } ?: return false
        return f < 1000
    }

    /** Banda "alta" = >= 1000 MHz. Típica de microceldas urbanas legítimas. */
    fun isHighBand(band: Int?): Boolean {
        val f = band?.let { approxFreqMhz(it) } ?: return false
        return f >= 1000
    }
}
