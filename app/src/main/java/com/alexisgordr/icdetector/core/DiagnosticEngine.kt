package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.*

/**
 * Convierte el resultado del motor en explicaciones legibles. No puntúa, no altera estados y no
 * decide alarmas: su única responsabilidad es explicar qué dato faltó cuando una regla se abstuvo.
 */
object DiagnosticEngine {
    data class Inputs(
        val neighborCount: Int,
        val wifiActive: Boolean,
        val locationAvailable: Boolean,
        val historyWithLocation: Int,
        val latencyAvailable: Boolean,
        /** 3.0 (#28) — Entregas sin vecinas ya vistas / necesarias, mientras H1 está pendiente. */
        val isolationProgress: Pair<Int, Int>? = null,
        /** 3.0 (#26) — La sonda mide, pero aún aprende la referencia de esta celda. */
        val latencyLearning: Boolean = false,
        val cipheringAvailable: Boolean,
        val previousBandAvailable: Boolean,
        val signalBaseline: SignalBaseline?,
        val rfFingerprint: CellRfFingerprint?,
        val rfStability: CellRfStability?,
        /** 3.0 — La lectura del historial RF falló en este ciclo (no es historial insuficiente). */
        val rfStabilityReadFailed: Boolean = false,
        val reputation: CellReputation?,
        val transitionCoherence: TransitionCoherenceResult = TransitionCoherenceResult(),
        val trustGateMet: Boolean? = null
    )

    fun maturity(i: Inputs) = BaselineMaturity(
        signalSamples = i.signalBaseline?.sampleCount ?: 0,
        fingerprintSamples = i.rfFingerprint?.sampleCount ?: 0,
        rfIdentitySamples = i.rfStability?.totalObservations ?: 0,
        reputationSamples = i.reputation?.observations ?: 0,
        trustGateMet = i.trustGateMet
    )

    fun explain(cell: CellData, i: Inputs): List<HeuristicDiagnostic> {
        val r = cell.heuristicReport
        fun explanation(status: HeuristicStatus, unavailable: String): String = when (status) {
            HeuristicStatus.PASSED -> "Datos disponibles; no se observó la condición anómala."
            HeuristicStatus.FAILED -> "La condición anómala de esta regla está presente en el ciclo actual."
            HeuristicStatus.NOT_EVALUATED -> unavailable
        }
        return listOf(
            HeuristicDiagnostic(1, "Celda aislada", r.isolatedCell,
                explanation(r.isolatedCell, when {
                    i.wifiActive -> WIFI_NA
                    i.neighborCount == 0 && i.isolationProgress != null ->
                        "N/A: sin vecinas, pendiente de confirmar (${i.isolationProgress.first}/${i.isolationProgress.second} entregas)."
                    else -> "N/A: sin lectura celular válida."
                })),
            HeuristicDiagnostic(2, "Estabilidad de potencia", r.powerJump,
                explanation(r.powerJump, if (i.wifiActive) WIFI_NA else "N/A: hacen falta celdas vecinas de la misma tecnología con potencia para comparar.")),
            HeuristicDiagnostic(3, "Consistencia MCC", r.mccConsistency,
                explanation(r.mccConsistency, "N/A: falta MCC válido en la celda activa o en sus vecinas.")),
            HeuristicDiagnostic(4, "Límite MNC", r.mncCount,
                explanation(r.mncCount, "N/A: faltan MNC válidos de celdas vecinas.")),
            HeuristicDiagnostic(5, "Validación regional TAC", r.tacDeviation,
                explanation(r.tacDeviation, "N/A: no hay TAC válidos de vecinas de la misma tecnología y el mismo operador para contrastar.")),
            HeuristicDiagnostic(6, "Proximidad por Timing Advance", r.taDistance,
                explanation(r.taDistance, "N/A: el módem no entrega un Timing Advance convertible a metros.")),
            HeuristicDiagnostic(7, "Espectro de vecinos", r.ghostNeighbors,
                explanation(r.ghostNeighbors, if (i.wifiActive) WIFI_NA else "N/A: no hay celdas vecinas de la misma tecnología para contrastar el espectro.")),
            // 3.0.0-beta5 — Aviso de calidad de dato: su fallo no es la condición de un ataque.
            HeuristicDiagnostic(8, "Calidad de dato ARFCN", r.arfcnSanity,
                if (r.arfcnSanity == HeuristicStatus.FAILED) H8_FAILED_EXPLANATION
                else explanation(r.arfcnSanity, "N/A: el módem no entrega ARFCN/EARFCN para esta tecnología.")),
            HeuristicDiagnostic(9, "Cifrado hardware", r.hardwareCiphering,
                explanation(r.hardwareCiphering, if (!i.cipheringAvailable) "N/A: Android o el fabricante no exponen el estado de cifrado a esta app." else "N/A: estado de cifrado no disponible.")),
            HeuristicDiagnostic(10, "Anti ping-pong", r.pingPong,
                explanation(r.pingPong, "N/A: hay cambios rápidos de celda, pero sin velocidad GPS no se puede saber si estás parado.")),
            HeuristicDiagnostic(11, "Consistencia geográfica", r.mobileCellId,
                explanation(r.mobileCellId, when {
                    !i.locationAvailable -> "N/A: falta una posición GPS reciente."
                    i.historyWithLocation == 0 -> "N/A: aún no hay observaciones previas geolocalizadas de esta celda."
                    else -> "N/A: identidad celular insuficiente para comparar."
                })),
            HeuristicDiagnostic(12, "Correlación latencia + RF", r.latencyCorrelation,
                explanation(r.latencyCorrelation, when {
                    i.wifiActive -> "N/A: Wi-Fi/VPN impide atribuir la latencia al enlace celular."
                    i.latencyLearning -> "N/A: la sonda de latencia aún aprende la referencia de esta celda."
                    !i.latencyAvailable -> "N/A: la sonda de latencia todavía no tiene una medición válida."
                    cell.rsrq == null && cell.sinr == null -> "N/A: el módem no entrega RSRQ ni SINR."
                    else -> "N/A: faltan datos cruzados del ciclo."
                })),
            HeuristicDiagnostic(13, "Baseline y huella RF", r.signalBaseline,
                explanation(r.signalBaseline, if (i.wifiActive) WIFI_NA else "N/A: baseline en aprendizaje; se necesitan muestras históricas compatibles.")),
            HeuristicDiagnostic(14, "Downgrade de banda", r.bandDowngrade,
                explanation(r.bandDowngrade, if (!i.previousBandAvailable) "N/A: falta una banda anterior válida (LTE o 5G) de la misma tecnología para comparar." else "N/A: la tecnología o banda actual no permite la comparación.")),
            HeuristicDiagnostic(15, "Estabilidad de identidad RF", r.rfStability,
                explanation(r.rfStability, if (i.rfStabilityReadFailed) "N/A: no se pudo leer el historial RF en este ciclo."
                    else "N/A: historial RF insuficiente (${i.rfStability?.totalObservations ?: 0}/4 observaciones).")),
            HeuristicDiagnostic(16, "Coherencia de transición celular", r.transitionCoherence,
                i.transitionCoherence.explanation)
        )
    }

    /**
     * 3.0 — Motivo de N/A con Wi-Fi activo en H1, H2, H7 y H13 (se abstienen en ThreatAnalyzer). Antes
     * H2, H7 y H13 daban otro motivo aunque la causa fuera el Wi-Fi.
     */
    private const val WIFI_NA = "N/A: Wi-Fi activo; se evita atribuir el contexto de red al enlace celular."

    /** 3.0.0-beta5 — H8 es un aviso de calidad de dato (ver ThreatAnalyzer, regla 8). */
    const val H8_FAILED_EXPLANATION = "Calidad de dato: el módem informa un canal fuera de la tabla 3GPP. No es evidencia de ataque y no resta puntos."
}
