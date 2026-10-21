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
        /** 3.0 (#28) — Entregas sin vecinas ya vistas / necesarias, mientras H1 está pendiente. */
        val isolationProgress: Pair<Int, Int>? = null,
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
        // 3.0 (corte de campaña) — En una celda CSG, H1/H2/H5/H6/H7 no se evalúan por diseño: ese motivo manda.
        fun local(unavailable: String) = if (r.csgCell) CSG_NA_EXPLANATION else unavailable
        return listOf(
            HeuristicDiagnostic(1, "Celda aislada", r.isolatedCell,
                explanation(r.isolatedCell, when {
                    r.csgCell -> CSG_NA_EXPLANATION
                    i.wifiActive -> WIFI_NA
                    i.neighborCount == 0 && i.isolationProgress != null ->
                        "N/A: sin vecinas, pendiente de confirmar (${i.isolationProgress.first}/${i.isolationProgress.second} entregas)."
                    else -> "N/A: sin lectura celular válida."
                })),
            HeuristicDiagnostic(2, "Estabilidad de potencia", r.powerJump,
                explanation(r.powerJump, local(if (i.wifiActive) WIFI_NA else "N/A: hacen falta celdas vecinas de la misma tecnología con potencia para comparar."))),
            // 3.0 (corte de campaña) — H3 compara la servidora con la SIM (MccConsistencyRule).
            HeuristicDiagnostic(3, "Consistencia MCC", r.mccConsistency, when (r.mccConsistency) {
                HeuristicStatus.PASSED -> H3_PASSED_EXPLANATION
                HeuristicStatus.FAILED -> H3_FAILED_EXPLANATION
                HeuristicStatus.NOT_EVALUATED -> "N/A: ${r.mccAbstention?.text ?: H3_UNKNOWN_ABSTENTION}."
            }),
            HeuristicDiagnostic(4, "Límite MNC", r.mncCount,
                explanation(r.mncCount, "N/A: faltan MNC válidos de celdas vecinas.")),
            HeuristicDiagnostic(5, "Validación regional TAC", r.tacDeviation,
                explanation(r.tacDeviation, local("N/A: no hay TAC válidos de vecinas de la misma tecnología y el mismo operador para contrastar."))),
            HeuristicDiagnostic(6, "Proximidad por Timing Advance", r.taDistance,
                explanation(r.taDistance, local(if (r.taDistanceGsmCoarse) H6_GSM_TA_EXPLANATION
                    else "N/A: el módem no entrega un Timing Advance convertible a metros."))),
            HeuristicDiagnostic(7, "Espectro de vecinos", r.ghostNeighbors,
                explanation(r.ghostNeighbors, local(if (i.wifiActive) WIFI_NA else "N/A: no hay celdas vecinas de la misma tecnología para contrastar el espectro."))),
            // 3.0 (corte de campaña) — Aviso de calidad de dato: su fallo no es la condición de un ataque.
            HeuristicDiagnostic(8, "Calidad de dato ARFCN", r.arfcnSanity,
                if (r.arfcnSanity == HeuristicStatus.FAILED) H8_FAILED_EXPLANATION
                else explanation(r.arfcnSanity, "N/A: el módem no entrega ARFCN/EARFCN para esta tecnología.")),
            HeuristicDiagnostic(9, "Cifrado hardware", r.hardwareCiphering,
                H9_NA_EXPLANATION),
            HeuristicDiagnostic(10, "Anti ping-pong", r.pingPong,
                explanation(r.pingPong, "N/A: hay cambios rápidos de celda, pero sin velocidad GPS no se puede saber si estás parado.")),
            HeuristicDiagnostic(11, "Consistencia geográfica", r.mobileCellId,
                explanation(r.mobileCellId, when {
                    // 3.0 (corte de campaña) — Fix demasiado viejo para H11 (ver MobileCellIdFixAge).
                    r.mobileCellIdFixTooOld -> H11_FIX_TOO_OLD_EXPLANATION
                    r.mobileCellIdFixImprecise -> H11_FIX_IMPRECISE_EXPLANATION
                    !i.locationAvailable -> "N/A: falta una posición GPS reciente."
                    i.historyWithLocation == 0 -> "N/A: aún no hay observaciones previas geolocalizadas de esta celda."
                    else -> "N/A: identidad celular insuficiente para comparar."
                })),
            HeuristicDiagnostic(12, "Correlación latencia + RF", r.latencyCorrelation,
                explanation(r.latencyCorrelation, H12_RETIRED_EXPLANATION)),
            HeuristicDiagnostic(13, "Baseline y huella RF", r.signalBaseline,
                explanation(r.signalBaseline, if (i.wifiActive) WIFI_NA else "N/A: baseline en aprendizaje; se necesitan muestras históricas compatibles.")),
            HeuristicDiagnostic(14, "Downgrade de banda", r.bandDowngrade,
                explanation(r.bandDowngrade, if (!i.previousBandAvailable) "N/A: falta una banda anterior válida (LTE o 5G) de la misma tecnología para comparar." else "N/A: la tecnología o banda actual no permite la comparación.")),
            HeuristicDiagnostic(15, "Estabilidad de identidad RF", r.rfStability,
                explanation(r.rfStability, if (i.rfStabilityReadFailed) "N/A: no se pudo leer el historial RF en este ciclo."
                    else "N/A: historial RF insuficiente (${i.rfStability?.totalObservations ?: 0}/4 observaciones).")),
            HeuristicDiagnostic(16, "Coherencia de transición celular", r.transitionCoherence,
                i.transitionCoherence.explanation),
            HeuristicDiagnostic(17, "Churn de celdas (sombra)", r.cellChurn,
                explanation(r.cellChurn, H17_SHADOW_EXPLANATION))
        )
    }

    /**
     * 3.0 — Motivo de N/A con Wi-Fi activo en H1, H2, H7 y H13 (se abstienen en ThreatAnalyzer). Antes
     * H2, H7 y H13 daban otro motivo aunque la causa fuera el Wi-Fi.
     */
    private const val WIFI_NA = "N/A: Wi-Fi activo; se evita atribuir el contexto de red al enlace celular."

    /** 3.0 (corte de campaña) — H8 es un aviso de calidad de dato (ver ThreatAnalyzer, regla 8). */
    /** 3.0 (corte de campaña) — H11 con un fix de más de 60 s o sin edad conocida. */
    const val H11_FIX_TOO_OLD_EXPLANATION = "N/A: el fix GPS tiene más de 60 s (o no se sabe su edad); en movimiento, la posición ya no sería la de esta lectura."
    /** 3.1.0 (#46) — H11 con un fix sin precisión conocida o peor que 100 m. */
    /** 3.1.0 (#47) — H6 con TA de GSM (HeuristicReport.taDistanceGsmCoarse). */
    const val H6_GSM_TA_EXPLANATION = "N/A: en GSM el Timing Advance va en pasos de 554 m; el índice 0 significa a menos de 554 m, no 0 m, y no permite afirmar que estés a menos de 100 m."
    const val H11_FIX_IMPRECISE_EXPLANATION = "N/A: el fix GPS no tiene precisión conocida o es peor que 100 m; la posición podría estar lejos de donde se vio la celda."
    /** 3.1.0 — H12 retirada: necesitaba sondas de red y la app ya no sale a Internet. */
    const val H12_RETIRED_EXPLANATION = "N/A: regla retirada en 3.1.0 (la correlación de latencia necesitaba conexión a Internet)."
    /** 3.1.0 — H17 en sombra: se calcula y se guarda, no se evalúa. */
    const val H17_SHADOW_EXPLANATION = "N/A: en sombra; se registran las celdas distintas y los cambios de 15 min, sin evaluar hasta calibrarla con datos de campaña."
    /** 3.0 (corte de campaña) — H1, H2, H5, H6 y H7 en una celda CSG. */
    const val CSG_NA_EXPLANATION = "N/A: celda CSG (femtocelda): las reglas de dominancia, proximidad e identidad local no aplican a una celda diseñada para ser aislada."
    /** 3.0 (corte de campaña) — H9 está reservada: nunca se evalúa. */
    const val H9_NA_EXPLANATION = "N/A: Android no expone el estado de cifrado a aplicaciones sin privilegios; regla reservada."
    /** 3.0 (corte de campaña) — Explicaciones de H3 (servidora frente a SIM). */
    const val H3_PASSED_EXPLANATION = "El MCC de la servidora coincide con el de la SIM."
    const val H3_FAILED_EXPLANATION = "El MCC de la servidora no es el de la SIM, sin roaming y sin vecinas con ese MCC."
    const val H3_UNKNOWN_ABSTENTION = "faltan datos de la SIM o de la servidora"
    const val H8_FAILED_EXPLANATION = "Calidad de dato: el módem informa un canal fuera de la tabla 3GPP. No es evidencia de ataque y no resta puntos."
}
