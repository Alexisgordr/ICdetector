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
     * Probabilidad bayesiana de amenaza calculada por BayesianScorer en el momento de la
     * observación (0..95). v2.1: se persiste y se exporta para poder RECALIBRAR los likelihood
     * ratios con datos reales — antes se calculaba en cada ciclo y se perdía al salir de la UI,
     * así que no había nada que calibrar (roadmap #7).
     */
    val threatProbability: Float = 0f,
    /**
     * Coordenada de la ANTENA según WiGLE/OpenCellID. v2.1: vive en su propia columna y NO
     * se mezcla nunca con [lat]/[lon], que son y solo son la posición GPS del dispositivo en
     * el momento de la observación. Mezclar ambas magnitudes en la misma columna era la causa
     * real de las coordenadas imposibles (~1.100 km) que aparecían en el historial.
     */
    val apiLat: Double? = null,
    val apiLon: Double? = null
)
