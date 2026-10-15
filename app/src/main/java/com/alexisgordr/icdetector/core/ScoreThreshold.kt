package com.alexisgordr.icdetector.core

/**
 * 3.0 (UI3) — Umbral de puntuación sospechosa, compartido por el motor (ThreatAnalyzer) y las
 * pantallas. Una puntuación por debajo de [SUSPICIOUS_BELOW] marca la celda como sospechosa en ESE
 * ciclo; no es una alarma confirmada (la confirmación temporal y las alarmas tienen sus propias
 * condiciones). Valor y comparación sin cambios: `score < 70`.
 *
 * Otros 70 del proyecto no son este umbral (−70 dBm de RSRP, la banda LTE 70…) y no lo usan.
 */
object ScoreThreshold {
    const val SUSPICIOUS_BELOW = 70

    fun isBelow(score: Int): Boolean = score < SUSPICIOUS_BELOW
}
