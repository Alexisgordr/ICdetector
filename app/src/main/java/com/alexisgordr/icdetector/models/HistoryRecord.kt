package com.alexisgordr.icdetector.models

/**
 * Prefijo con el que se marcan en el historial las observaciones que SÍ fallaron alguna
 * heurística pero no llegaron al umbral de sospecha (score >= 70).
 *
 * v2.1: antes esas observaciones se guardaban con el score real pero con el motivo borrado
 * ("OK"), porque applyTemporalConfidence ponía suspiciousReason a null cuando la celda no era
 * sospechosa. Resultado: 33 de las 34 filas penalizadas de dos meses de campo eran filas del
 * tipo "85 / OK" — una contradicción interna que hacía imposible estudiar falsos positivos o
 * recalibrar nada. Ahora el motivo se conserva SIEMPRE, marcado con este prefijo para que quede
 * claro que NO es una alarma: la separación forense (solo se alerta lo confirmado en 3 ciclos)
 * se mantiene intacta, pero la evidencia deja de tirarse a la basura.
 */
const val SUBTHRESHOLD_PREFIX = "[sub-umbral]"

/**
 * 3.0 (#7, B4) — Prefijo de los motivos de Stable-Site cuando la protección está activa. Como
 * [SUBTHRESHOLD_PREFIX], se guarda en castellano y la interfaz en inglés lo muestra como
 * `[site-unverified]`. Antes se guardaba en inglés y no tenía traducción. Con la protección en
 * modo sombra (STABLE_SITE_ENFORCEMENT_ENABLED = false) nunca se ha llegado a guardar.
 */
const val SITE_UNVERIFIED_PREFIX = "[sitio-sin-verificar]"

data class HistoryRecord(
    val timestamp: String,
    val netType: String,
    val cid: String,
    val mnc: String,
    val tac: String,
    val mcc: String,
    val dbm: Int,
    val verified: VerificationStatus = VerificationStatus.PENDING,
    val score: Int = 100,
    val failedHeuristics: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val pci: Int? = null,
    val arfcn: Int? = null,
    val rsrq: Int? = null,
    val sinr: Int? = null,
    /**
     * Confianza de anomalía calculada por BayesianScorer en el momento de la observación (0..95).
     *
     * Se persiste y se exporta para poder REVISAR los likelihood ratios con datos reales (3.0:
     * con datos solo benignos no basta para recalibrarlos; hacen falta también ataques reales) —
     * antes se calculaba en cada ciclo y se perdía al salir de la UI, así que no había nada que
     * calibrar (roadmap #7). No es una probabilidad medida: ver [CellData.anomalyConfidence].
     */
    val anomalyConfidence: Float = 0f,
    /**
     * Coordenada de la ANTENA según OpenCellID. v2.1: vive en su propia columna y NO
     * se mezcla nunca con [lat]/[lon], que son y solo son la posición GPS del dispositivo en
     * el momento de la observación. Mezclar ambas magnitudes en la misma columna era la causa
     * real de las coordenadas imposibles (~1.100 km) que aparecían en el historial.
     */
    val apiLat: Double? = null,
    val apiLon: Double? = null,
    /**
     * Timing Advance CRUDO tal y como lo entregó el módem, y la unidad en que lo entregó.
     *
     * v2.1: es la señal más cara del motor (H6 penaliza -40) y era la única que NO se exportaba,
     * así que nadie podía comprobar si su teléfono la reporta siquiera. Ahora se guarda el valor
     * sin transformar junto con su procedencia: si algún día hay que decidir empíricamente cómo
     * convertir el TA de NR, la evidencia estará recogida en vez de haber que empezar de cero.
     */
    val timingAdvance: Int? = null,
    val timingAdvanceUnit: TimingAdvanceUnit = TimingAdvanceUnit.UNKNOWN,
    /**
     * Tecnología de radio de la observación, tomada de la clase de `CellInfo` — v2.1.
     *
     * [netType] NO sirve para esto: viene de `TelephonyDisplayInfo` y describe el icono de la barra
     * de estado, que en el historial de campo alterna entre "4G" y "5G" para la misma celda. Quien
     * analice estos datos necesita saber de qué tecnología era cada fila sin tener que fiarse de
     * una etiqueta de presentación. [RadioTech.UNKNOWN] en las filas anteriores a esta versión.
     */
    val radio: RadioTech = RadioTech.UNKNOWN,
    // ── v2.10.4 — Contexto de radio (schema 19). Nulo en filas anteriores: no se recogía. ──
    /** Papel de la celda en la conexión cuando se guardó la fila. */
    val connectionState: CellConnectionState? = null,
    val bandwidthKhz: Int? = null,
    /** Bandas declaradas, separadas por `;`. */
    val bands: String? = null,
    /** PLMN adicionales anunciados, separados por `;`. */
    val additionalPlmns: String? = null,
    val csgIndicator: Boolean? = null,
    val csgIdentity: Int? = null,
    val csgName: String? = null,
    /** Portadoras secundarias en forma compacta (`LTE:6400:200;NR:632448:12`). */
    val secondaryCarriers: String? = null,
    /** Nombre de [ServiceRegistrationState] en el momento de la fila. */
    val serviceState: String? = null,
    val networkOperator: String? = null,
    val simOperator: String? = null,
    val networkRoaming: Boolean? = null,
    /**
     * 3.0 (#23) — Instante de la observación en milisegundos desde epoch (UTC). [timestamp] es
     * texto local sin zona y se repite en el cambio de hora de otoño; este valor no. Null en las
     * filas anteriores a 3.0: su zona nunca se guardó y no se reconstruye.
     */
    val observedAtMs: Long? = null,
    /**
     * 3.0 (#29) — Reglas que se abstuvieron en esta observación (`H1;H6;H9`), o `NONE` si todas se
     * evaluaron. Null en filas anteriores a 3.0: entonces no se guardaba y es desconocido, nunca
     * "evaluada".
     */
    val notEvaluatedHeuristics: String? = null,
    /** 3.0 (#29) — Precisión en metros del fix de [lat]/[lon]. Null sin fix o en filas anteriores. */
    val gpsAccuracyM: Float? = null,
    /**
     * 3.0 (#30) — Versión de la app que hizo la observación. Permite aplicar los cortes de dataset
     * (H10 desde 2.10.9, H8 desde 2.10.10, …) fila a fila. Null en filas anteriores a 3.0.
     */
    val appVersion: String? = null,
    /** 3.0 — Modo de ubicación de la fila (`CONTINUOUS` / `ADAPTIVE`); null = desconocido. */
    val locationMode: String? = null
)

/** Misma identidad completa que [CellData.identityKey], aplicada a una fila histórica. */
val HistoryRecord.identityKey: String
    get() = "$mcc-$mnc-$tac-$cid-${radio.name}"
