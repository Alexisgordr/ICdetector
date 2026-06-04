package com.alexisgordr.icdetector.models

/**
 * Huella RF (fingerprint) de una celda: media y desviación de RSRQ y SINR aprendidas del
 * propio historial. Complementa al baseline de RSRP (H13). Una celda legítima mantiene una
 * "firma" de calidad de señal relativamente estable en un mismo punto; un transmisor distinto
 * suplantando esa celda puede presentar una calidad de señal incoherente con la firma histórica.
 *
 * IMPORTANTE: RSRQ y SINR son intrínsecamente más ruidosos que el RSRP (varían con la carga de
 * red, interferencia y posición). Por eso esta firma se usa de forma MUY conservadora: solo se
 * juzga con muchas muestras y exigiendo desviación grande en AMBAS métricas a la vez. Además,
 * las columnas rsrq/sinr empiezan vacías tras la migración, así que la firma nace "dormida" y
 * solo despierta cuando hay semanas de datos — cero falsos positivos al principio, por diseño.
 *
 * @property sampleCount  nº de muestras con RSRQ/SINR válidos
 * @property rsrqMean/rsrqStd  media y desviación de RSRQ (dB)
 * @property sinrMean/sinrStd  media y desviación de SINR (dB)
 */
data class CellRfFingerprint(
    val sampleCount: Int,
    val rsrqMean: Double,
    val rsrqStd: Double,
    val sinrMean: Double,
    val sinrStd: Double
)
