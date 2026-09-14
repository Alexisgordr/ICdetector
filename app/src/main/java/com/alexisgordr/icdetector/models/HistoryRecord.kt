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
     * Se persiste y se exporta para poder RECALIBRAR los likelihood ratios con datos reales —
     * antes se calculaba en cada ciclo y se perdía al salir de la UI, así que no había nada que
     * calibrar (roadmap #7). No es una probabilidad medida: ver [CellData.anomalyConfidence].
     */
    val anomalyConfidence: Float = 0f,
    /**
     * Coordenada de la ANTENA según WiGLE/OpenCellID. v2.1: vive en su propia columna y NO
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
    val radio: RadioTech = RadioTech.UNKNOWN
)
