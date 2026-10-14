package com.alexisgordr.icdetector.ui

/**
 * Tramo de color de la confianza de anomalía en el panel: 2 = rojo, 1 = ámbar, 0 = gris.
 * 3.0.0-beta5: umbrales 30/10 (antes 50/20) porque el prior bajó de 0,02 a 0,005. Es solo
 * presentación: no decide alarmas (las decide el score con la confirmación temporal).
 */
internal fun anomalyConfidenceTier(confidence: Float): Int = when {
    confidence >= 30f -> 2
    confidence >= 10f -> 1
    else -> 0
}
