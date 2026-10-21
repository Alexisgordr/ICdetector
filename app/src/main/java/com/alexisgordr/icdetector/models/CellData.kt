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
     * 3.1.0 (#43) — El TA de esta lectura viene de una unidad sin veredicto todavía (ni REAL ni
     * STUB, ver core/TimingAdvanceSanity.annotate): H6 no juzga la proximidad con él.
     */
    val timingAdvanceUnverified: Boolean = false,
    /**
     * Tecnología de radio según la CLASE de `CellInfo` de la que se leyó la celda — v2.1.
     *
     * No confundir con [networkType], que viene de `TelephonyDisplayInfo` y describe el icono de la
     * barra de estado (la misma celda alterna ahí entre "4G" y "5G" sin cambiar de identidad). Esto
     * es el dato firme, y forma parte de la identidad de la celda. Ver [RadioTech].
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
    /** Estado visible y persistible del filtro temporal; no modifica el resultado heurístico. */
    val temporalProgress: TemporalProgress = TemporalProgress(),
    /** Diagnóstico del ciclo actual, incluida la causa concreta de cada N/A. */
    val heuristicDiagnostics: List<HeuristicDiagnostic> = emptyList(),
    /** Cantidad de evidencia histórica disponible para las reglas que aprenden localmente. */
    val baselineMaturity: BaselineMaturity = BaselineMaturity(),
    /** Resultado detallado del último handover; alimenta H16 y su tarjeta visible. */
    val transitionCoherence: TransitionCoherenceResult = TransitionCoherenceResult(),
    /** Revocable confidence learned only from this device's clean local history. */
    val localCellTrust: LocalCellTrust = LocalCellTrust(),
    val stableSiteDecision: com.alexisgordr.icdetector.core.StableSiteDecision = com.alexisgordr.icdetector.core.StableSiteDecision(),
    /** 3.1.0 — Churn de celdas de 15 min (H17, en sombra): solo se guarda; null = no calculado. */
    val churn: com.alexisgordr.icdetector.core.ChurnSnapshot? = null,
    // ── v2.10.4 — Contexto de radio. Solo recolección: ninguna heurística lee estos campos. ──
    /** Papel de la celda en la conexión (`CellInfo.getCellConnectionStatus()`). */
    val connectionState: CellConnectionState = CellConnectionState.UNKNOWN,
    /** Ancho de banda de la portadora en kHz (LTE, API 28). Nulo si el módem no lo da. */
    val bandwidthKhz: Int? = null,
    /** Bandas que declara la celda (LTE/NR, API 30). Vacío si no se declaran. */
    val bands: List<Int> = emptyList(),
    /** PLMN adicionales que anuncia la celda, p. ej. en redes compartidas (API 30). */
    val additionalPlmns: List<String> = emptyList(),
    /** Grupo cerrado de abonados (femtocelda), si la celda lo declara (LTE/WCDMA, API 30). */
    val csg: CsgInfo? = null,
    /** Solo en la celda analizada: portadoras secundarias y entradas registradas extra. */
    val secondaryCarriers: List<SecondaryCarrier> = emptyList(),
    /**
     * 3.0 (corte de campaña) — Aviso de calidad de dato de esta observación (H8), o null. No es un motivo de
     * sospecha: no va en [suspiciousReason] (pantalla, notificaciones, incidentes), pero sí se
     * guarda en la fila del historial ([com.alexisgordr.icdetector.models.StoredReasons.forRow]).
     */
    val dataQualityReason: String? = null
)

/**
 * Identidad completa de una celda, como texto. **La única forma de identificar una celda** en
 * cachés, rachas temporales y comparaciones.
 *
 * Incluye la tecnología además de MCC, MNC, área y Cell ID. Sin ella, dos celdas de tecnologías
 * distintas que compartan esos cuatro identificadores serían la misma para la app, y se mezclarían
 * sus rachas, sus perfiles y sus rutas.
 *
 * Existe sobre todo porque esta cadena estaba escrita a mano en nueve sitios distintos. Nueve
 * copias de una regla de identidad es una forma garantizada de que algún día ocho digan una cosa y
 * la novena otra, sin que nada falle de manera visible.
 */
val CellData.identityKey: String
    get() = "$mcc-$mnc-$tac-$cellId-${radioTech.name}"

/** ¿Son la misma celda, con identidad completa? */
fun CellData.isSameCell(other: CellData): Boolean = identityKey == other.identityKey
