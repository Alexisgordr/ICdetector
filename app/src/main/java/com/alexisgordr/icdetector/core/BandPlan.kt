package com.alexisgordr.icdetector.core

/**
 * Mapeo EARFCN (LTE) -> banda física 3GPP y clasificación alta/baja frecuencia.
 *
 * Rangos de EARFCN de bajada (downlink) según 3GPP TS 36.101, Tabla 5.7.3-1, y frecuencia
 * inferior de bajada (F_DL_low) de la Tabla 5.5-1. Solo LTE: el NR usa NR-ARFCN con otra tabla
 * totalmente distinta, así que NO se mezcla aquí a propósito (mapearlo con esta tabla daría
 * bandas falsas).
 *
 * Objetivo defensivo: poder razonar sobre un "band downgrade" intra-LTE, es decir, un salto
 * forzado desde una banda alta urbana (1800/2100/2600 MHz) a una banda baja sub-GHz
 * (800/900/700 MHz). Las bandas sub-GHz penetran paredes y cubren un radio enorme con poca
 * potencia, por lo que son las favoritas de un equipo táctico que quiere barrer una zona desde
 * una furgoneta. Detectar ese salto (cuando NO está justificado por una degradación física
 * progresiva de la señal) es una señal defensiva útil.
 *
 * 3.0 (#32) — Tabla completa y única.
 *  - Antes tenía 17 bandas. Una celda en una banda omitida dejaba H14 en N/A, y la banda que el
 *    módem declarase tampoco se podía clasificar, porque alta/baja salía de la misma tabla.
 *  - El tope de la B71 era 69465 (el de la B74): EARFCN de las bandas 72-74 se daban por B71
 *    (600 MHz, baja) aunque la B74 es de 1475 MHz. Corregido a 68935.
 *  - Una sola fuente para banda y clasificación: [BANDS]. Las bandas LAA 252/255 solo llevan
 *    frecuencia: sirven para clasificar la banda que declare el módem (getBands).
 *  - Contrastada con la Tabla 5.7.3-1 de TS 36.104 V19.2.0 (rangos EARFCN y F_DL_low de todas
 *    las bandas resolubles); ver BandPlanTest.
 */
object BandPlan {

    /** Una banda LTE: frecuencia inferior de bajada y, si se resuelve por tabla, su rango EARFCN. */
    data class LteBand(val number: Int, val dlLowMhz: Double, val earfcnMin: Int? = null, val earfcnMax: Int? = null)

    /** Bandas baja = sub-GHz (< 1000 MHz): gran alcance y penetración. */
    const val LOW_BAND_LIMIT_MHZ = 1000.0

    val BANDS: List<LteBand> = listOf(
        // FDD
        LteBand(1, 2110.0, 0, 599),
        LteBand(2, 1930.0, 600, 1199),
        LteBand(3, 1805.0, 1200, 1949),
        LteBand(4, 2110.0, 1950, 2399),
        LteBand(5, 869.0, 2400, 2649),
        LteBand(7, 2620.0, 2750, 3449),
        LteBand(8, 925.0, 3450, 3799),
        LteBand(9, 1844.9, 3800, 4149),
        LteBand(10, 2110.0, 4150, 4749),
        LteBand(11, 1475.9, 4750, 4949),
        LteBand(12, 729.0, 5010, 5179),
        LteBand(13, 746.0, 5180, 5279),
        LteBand(14, 758.0, 5280, 5379),
        LteBand(17, 734.0, 5730, 5849),
        LteBand(18, 860.0, 5850, 5999),
        LteBand(19, 875.0, 6000, 6149),
        LteBand(20, 791.0, 6150, 6449),   // 800 MHz — banda táctica clásica en Europa
        LteBand(21, 1495.9, 6450, 6599),
        LteBand(22, 3510.0, 6600, 7399),
        LteBand(24, 1525.0, 7700, 8039),
        LteBand(25, 1930.0, 8040, 8689),
        LteBand(26, 859.0, 8690, 9039),
        LteBand(27, 852.0, 9040, 9209),
        LteBand(28, 758.0, 9210, 9659),
        LteBand(29, 717.0, 9660, 9769),   // SDL
        LteBand(30, 2350.0, 9770, 9869),
        LteBand(31, 462.5, 9870, 9919),
        LteBand(32, 1452.0, 9920, 10359), // SDL
        // TDD
        LteBand(33, 1900.0, 36000, 36199),
        LteBand(34, 2010.0, 36200, 36349),
        LteBand(35, 1850.0, 36350, 36949),
        LteBand(36, 1930.0, 36950, 37549),
        LteBand(37, 1910.0, 37550, 37749),
        LteBand(38, 2570.0, 37750, 38249),
        LteBand(39, 1880.0, 38250, 38649),
        LteBand(40, 2300.0, 38650, 39649),
        LteBand(41, 2496.0, 39650, 41589),
        LteBand(42, 3400.0, 41590, 43589),
        LteBand(43, 3600.0, 43590, 45589),
        LteBand(44, 703.0, 45590, 46589),
        LteBand(45, 1447.0, 46590, 46789),
        LteBand(46, 5150.0, 46790, 54539),
        LteBand(47, 5855.0, 54540, 55239),
        LteBand(48, 3550.0, 55240, 56739),
        LteBand(49, 3550.0, 56740, 58239),
        LteBand(50, 1432.0, 58240, 59089),
        LteBand(51, 1427.0, 59090, 59139),
        LteBand(52, 3300.0, 59140, 60139),
        LteBand(53, 2483.5, 60140, 60254),
        LteBand(54, 1670.0, 60255, 60304),
        // FDD/SDL de numeración extendida
        LteBand(65, 2110.0, 65536, 66435),
        LteBand(66, 2110.0, 66436, 67335),
        LteBand(67, 738.0, 67336, 67535), // SDL
        LteBand(68, 753.0, 67536, 67835),
        LteBand(69, 2570.0, 67836, 68335), // SDL
        LteBand(70, 1995.0, 68336, 68585),
        LteBand(71, 617.0, 68586, 68935),
        LteBand(72, 461.0, 68936, 68985),
        LteBand(73, 460.0, 68986, 69035),
        LteBand(74, 1475.0, 69036, 69465),
        LteBand(75, 1432.0, 69466, 70315), // SDL
        LteBand(76, 1427.0, 70316, 70365), // SDL
        LteBand(85, 728.0, 70366, 70545),
        LteBand(87, 420.0, 70546, 70595),
        LteBand(88, 422.0, 70596, 70645),
        LteBand(103, 757.0, 70646, 70655),
        LteBand(106, 935.0, 70656, 70705),
        LteBand(107, 612.0, 70706, 71105),
        LteBand(108, 470.0, 71106, 73385),
        LteBand(111, 1820.0, 73386, 73485),
        LteBand(112, 470.0, 73486, 74865),
        LteBand(113, 606.0, 74866, 75785),
        // Solo clasificación (la banda llega por getBands; sus EARFCN no se resuelven por tabla)
        LteBand(252, 5150.0),
        LteBand(255, 5725.0),
    )

    private val BY_NUMBER: Map<Int, LteBand> = BANDS.associateBy { it.number }
    private val RESOLVABLE: List<LteBand> = BANDS.filter { it.earfcnMin != null && it.earfcnMax != null }

    /** Devuelve el número de banda LTE para un EARFCN, o null si está fuera de tabla. */
    fun earfcnToBandLte(earfcn: Int): Int? {
        if (earfcn < 0) return null  // EARFCN 0 es válido (Banda 1, rango 0..599); solo el negativo es imposible
        return RESOLVABLE.firstOrNull { earfcn in it.earfcnMin!!..it.earfcnMax!! }?.number
    }

    /** Frecuencia inferior de bajada (MHz) de una banda, o null si desconocida. */
    fun approxFreqMhz(band: Int): Int? = BY_NUMBER[band]?.dlLowMhz?.toInt()

    /** Banda "baja" = sub-GHz (< 1000 MHz). Las que un catcher táctico prefiere para barrer. */
    fun isLowBand(band: Int?): Boolean {
        val f = band?.let { BY_NUMBER[it]?.dlLowMhz } ?: return false
        return f < LOW_BAND_LIMIT_MHZ
    }

    /** Banda "alta" = >= 1000 MHz. Típica de microceldas urbanas legítimas. */
    fun isHighBand(band: Int?): Boolean {
        val f = band?.let { BY_NUMBER[it]?.dlLowMhz } ?: return false
        return f >= LOW_BAND_LIMIT_MHZ
    }

    /**
     * 3.0 (#32) — Banda de una celda LTE a partir de su EARFCN y de las bandas que declare el
     * módem (`CellIdentityLte.getBands()`, API 30+; vacío en API 29).
     *
     *  - El EARFCN manda cuando está en la tabla: cada EARFCN pertenece a una sola banda en
     *    36.101, así que es la medida más fiable. Si el módem declara otra, se ignora lo declarado.
     *  - Fuera de tabla, una sola banda declarada se usa tal cual.
     *  - Fuera de tabla y varias declaradas: se usa la de número menor solo si todas son de la
     *    misma clase (alta o baja) y conocidas; si no, null (H14 queda N/A en vez de adivinar).
     *  - Sin nada utilizable, null.
     */
    fun resolveLteBand(earfcn: Int?, reportedBands: List<Int>): Int? {
        val fromTable = earfcn?.let { earfcnToBandLte(it) }
        if (fromTable != null) return fromTable
        val reported = reportedBands.filter { it > 0 }.distinct().sorted()
        return when {
            reported.isEmpty() -> null
            reported.size == 1 -> reported.single()
            reported.all { isLowBand(it) } || reported.all { isHighBand(it) } -> reported.first()
            else -> null
        }
    }
}
