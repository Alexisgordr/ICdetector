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
        val cipheringAvailable: Boolean,
        val previousBandAvailable: Boolean,
        val signalBaseline: SignalBaseline?,
        val rfFingerprint: CellRfFingerprint?,
        val rfStability: CellRfStability?,
        val reputation: CellReputation?
    )

    fun maturity(i: Inputs) = BaselineMaturity(
        signalSamples = i.signalBaseline?.sampleCount ?: 0,
        fingerprintSamples = i.rfFingerprint?.sampleCount ?: 0,
        rfIdentitySamples = i.rfStability?.totalObservations ?: 0,
        reputationSamples = i.reputation?.observations ?: 0
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
                explanation(r.isolatedCell, if (i.wifiActive) "N/A: Wi-Fi activo; se evita atribuir el contexto de red al enlace celular." else "N/A: sin lectura celular válida.")),
            HeuristicDiagnostic(2, "Estabilidad de potencia", r.powerJump,
                explanation(r.powerJump, "N/A: hacen falta celdas vecinas con potencia para comparar.")),
            HeuristicDiagnostic(3, "Consistencia MCC", r.mccConsistency,
                explanation(r.mccConsistency, "N/A: falta MCC válido en la celda activa o en sus vecinas.")),
            HeuristicDiagnostic(4, "Límite MNC", r.mncCount,
                explanation(r.mncCount, "N/A: faltan MNC válidos de celdas vecinas.")),
            HeuristicDiagnostic(5, "Validación regional TAC", r.tacDeviation,
                explanation(r.tacDeviation, "N/A: no hay TAC válidos de vecinas para contrastar.")),
            HeuristicDiagnostic(6, "Coherencia geométrica (TA)", r.taDistance,
                explanation(r.taDistance, "N/A: el módem no entrega un Timing Advance convertible a metros.")),
            HeuristicDiagnostic(7, "Espectro de vecinos", r.ghostNeighbors,
                explanation(r.ghostNeighbors, "N/A: no hay celdas vecinas disponibles para contrastar el espectro.")),
            HeuristicDiagnostic(8, "Sanidad ARFCN", r.arfcnSanity,
                explanation(r.arfcnSanity, "N/A: el módem no entrega ARFCN/EARFCN para esta tecnología.")),
            HeuristicDiagnostic(9, "Cifrado hardware", r.hardwareCiphering,
                explanation(r.hardwareCiphering, if (!i.cipheringAvailable) "N/A: Android o el fabricante no exponen el estado de cifrado a esta app." else "N/A: estado de cifrado no disponible.")),
            HeuristicDiagnostic(10, "Anti ping-pong", r.pingPong,
                explanation(r.pingPong, "N/A: todavía no existe una secuencia temporal utilizable.")),
            HeuristicDiagnostic(11, "Consistencia geográfica", r.mobileCellId,
                explanation(r.mobileCellId, when {
                    !i.locationAvailable -> "N/A: falta una posición GPS reciente."
                    i.historyWithLocation == 0 -> "N/A: aún no hay observaciones previas geolocalizadas de esta celda."
                    else -> "N/A: identidad celular insuficiente para comparar."
                })),
            HeuristicDiagnostic(12, "Correlación latencia + RF", r.latencyCorrelation,
                explanation(r.latencyCorrelation, when {
                    i.wifiActive -> "N/A: Wi-Fi/VPN impide atribuir la latencia al enlace celular."
                    !i.latencyAvailable -> "N/A: la sonda de latencia todavía no tiene una medición válida."
                    cell.rsrq == null && cell.sinr == null -> "N/A: el módem no entrega RSRQ ni SINR."
                    else -> "N/A: faltan datos cruzados del ciclo."
                })),
            HeuristicDiagnostic(13, "Baseline y huella RF", r.signalBaseline,
                explanation(r.signalBaseline, "N/A: baseline en aprendizaje; se necesitan muestras históricas compatibles.")),
            HeuristicDiagnostic(14, "Downgrade de banda", r.bandDowngrade,
                explanation(r.bandDowngrade, if (!i.previousBandAvailable) "N/A: falta una banda LTE anterior válida para comparar." else "N/A: la tecnología o banda actual no permite la comparación.")),
            HeuristicDiagnostic(15, "Estabilidad de identidad RF", r.rfStability,
                explanation(r.rfStability, "N/A: historial RF insuficiente (${i.rfStability?.totalObservations ?: 0}/4 observaciones)."))
        )
    }
}
