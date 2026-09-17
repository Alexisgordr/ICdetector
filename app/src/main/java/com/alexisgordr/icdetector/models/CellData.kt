package com.alexisgordr.icdetector.models

data class CellData(
    val isRegistered: Boolean,
    val networkType: String,
    val cellId: String,
    val mnc: String,
    val tac: String,
    val dbm: Int,
    val mcc: String = "N/A",
    val verified: VerificationStatus = VerificationStatus.PENDING,
    val isSuspicious: Boolean = false,
    val suspiciousReason: String? = null,
    val timingAdvance: Int? = null,
    /**
     * Unidad de [timingAdvance]. La fija CellParser, que es el único sitio que sabe de qué clase
     * de CellInfo procede el valor. Por defecto UNKNOWN: sin unidad conocida, H6 no deduce
     * distancia — prefiere abstenerse a inventarla. Ver [TimingAdvanceUnit].
     */
    val timingAdvanceUnit: TimingAdvanceUnit = TimingAdvanceUnit.UNKNOWN,
    /**
     * Tecnología de radio según la CLASE de `CellInfo` de la que se leyó la celda — v2.1.
     *
     * No confundir con [networkType], que viene de `TelephonyDisplayInfo` y describe el icono de la
     * barra de estado (la misma celda alterna ahí entre "4G" y "5G" sin cambiar de identidad). Esto
     * es el dato firme, y es lo que se manda a OpenCellID en el parámetro `radio` y lo que se
     * compara contra el `radio` que devuelve. Ver [RadioTech].
     */
    val radioTech: RadioTech = RadioTech.UNKNOWN,
    val arfcn: Int? = null,
    val pci: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val rsrq: Int? = null,   // ← NUEVO: calidad de señal LTE/NR (-3 a -20 dB)
    val sinr: Int? = null,   // ← NUEVO: relación señal/ruido
    val band: Int? = null,   // ← NUEVO: banda física LTE derivada del EARFCN (heurística 14)
    val heuristicReport: HeuristicReport = HeuristicReport(),
    val securityScore: Int = 100,
    /**
     * Salida del motor bayesiano para esta observación, 0..95.
     *
     * NO es una probabilidad en sentido estadístico: es un posterior calculado con likelihood
     * ratios estimados por criterio experto sobre un prior asumido. Se llamaba
     * `threatProbability` durante el desarrollo; el número no ha cambiado, el nombre ha dejado de
     * prometer una validación que no existe. Ver BayesianScorer.
     */
    val anomalyConfidence: Float = 0f,
    /**
     * Distancia en metros entre tu posición GPS y la posición que las bases públicas
     * (WiGLE/OpenCellID) atribuyen a esta antena — v2.1.
     *
     * Es SOLO PARA MOSTRAR. No entra en ninguna heurística, a propósito: es una estimación de
     * calidad muy distinta a la del Timing Advance (depende de lo buena que sea la coordenada de
     * una base de datos colaborativa, que puede estar a cientos de metros del emplazamiento real,
     * y de que tengas fix GPS). Sirve para responder "¿a qué distancia está la antena?" cuando el
     * módem no reporta TA, que es el caso en buena parte de los teléfonos; no sirve para acusar a
     * nadie. Mezclarla con la geometría del TA sería juntar dos precisiones incomparables.
     */
    val distanceToTowerMeters: Int? = null,
    /** Estado visible y persistible del filtro temporal; no modifica el resultado heurístico. */
    val temporalProgress: TemporalProgress = TemporalProgress(),
    /** Diagnóstico del ciclo actual, incluida la causa concreta de cada N/A. */
    val heuristicDiagnostics: List<HeuristicDiagnostic> = emptyList(),
    /** Cantidad de evidencia histórica disponible para las reglas que aprenden localmente. */
    val baselineMaturity: BaselineMaturity = BaselineMaturity()
)

/**
 * Identidad completa de una celda, como texto. **La única forma de identificar una celda** en
 * cachés, rachas temporales y comparaciones.
 *
 * Incluye la tecnología además de MCC, MNC, área y Cell ID. Sin ella, dos celdas de tecnologías
 * distintas que compartan esos cuatro identificadores serían la misma para la app — y desde que la
 * consulta a OpenCellID distingue por `radio`, dejarlas juntas aquí contradice el motivo por el que
 * se distingue allí: se preguntaría por una y se reutilizaría la respuesta para la otra.
 *
 * Existe sobre todo porque esta cadena estaba escrita a mano en nueve sitios distintos. Nueve
 * copias de una regla de identidad es una forma garantizada de que algún día ocho digan una cosa y
 * la novena otra, sin que nada falle de manera visible.
 */
val CellData.identityKey: String
    get() = "$mcc-$mnc-$tac-$cellId-${radioTech.name}"

/** ¿Son la misma celda, con identidad completa? */
fun CellData.isSameCell(other: CellData): Boolean = identityKey == other.identityKey
