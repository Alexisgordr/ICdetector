package com.alexisgordr.icdetector.service

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.TimingAdvanceUnit

/** Formats the stable rule audit trail (H1–H17) without owning service lifecycle or detector state. */
internal class AuditLogController(
    private val log: (String, String) -> Unit,
    private val publishStatus: (String) -> Unit,
    private val zeroOnlyCellCount: () -> Int
) {
    fun generate(cell: CellData) {
        publishStatus("Auditoría en curso...")
        log("[AUDIT]", "--- CICLO DE AUDITORÍA (17 REGLAS) · Celda ${cell.cellId} (${cell.mcc}-${cell.mnc}-${cell.tac}) ---")
        val report = cell.heuristicReport
        linkedMapOf(
            "1. Celda Aislada" to report.isolatedCell,
            "2. Estabilidad Potencia" to report.powerJump,
            "3. Consistencia MCC" to report.mccConsistency,
            "4. Límite MNC" to report.mncCount,
            "5. Validación Regional TAC" to report.tacDeviation,
            "6. Proximidad por Timing Advance" to report.taDistance,
            "7. Espectro Fantasma" to report.ghostNeighbors,
            "8. Calidad de dato ARFCN" to report.arfcnSanity,
            "9. Cifrado Hardware" to report.hardwareCiphering,
            "10. Anti Ping-Pong" to report.pingPong,
            "11. Consistencia Geográfica (Cell ID móvil)" to report.mobileCellId,
            "12. Correlación Latencia + RF" to report.latencyCorrelation,
            "13. Potencia vs Histórico (Baseline + huella RSRQ/SINR)" to report.signalBaseline,
            "14. Downgrade de Banda (Intra-LTE)" to report.bandDowngrade,
            "15. Estabilidad de Identidad RF (PCI)" to report.rfStability,
            "16. Coherencia de Transición" to report.transitionCoherence,
            "17. Churn de celdas (sombra)" to report.cellChurn
        ).forEach { (rule, result) ->
            val status = when (result) {
                HeuristicStatus.PASSED -> "PASSED"
                HeuristicStatus.FAILED -> "FAILED"
                HeuristicStatus.NOT_EVALUATED -> "N/A"
            }
            // 3.0 (corte de campaña) — H3 dice por qué se abstuvo (roaming, frontera, falta de datos), y
            // H1/H2/H5/H6/H7 si la servidora es CSG.
            val note = if (result == HeuristicStatus.NOT_EVALUATED) {
                report.abstentionText("H" + rule.substringBefore("."))?.let { " ($it)" }.orEmpty()
            } else ""
            log("[HEUR]", "$rule: $status$note")
        }
        logTimingAdvance(cell)

        log("[AUDIT]", "Resultado Global: índice heurístico ${cell.securityScore}% sobre " +
            "${report.evaluatedCount}/${report.totalCount} reglas evaluadas; " +
            "${report.totalCount - report.evaluatedCount} sin datos.")
        when {
            cell.isSuspicious -> log("[SEC]", "🚨 CRÍTICO: Antena sospechosa detectada: ${cell.suspiciousReason}")
            cell.suspiciousReason != null -> log("[SYS]", "Sin alarma, pero con observaciones: ${cell.suspiciousReason}")
            else -> log("[SYS]", "✅ Sin anomalías en las reglas que pudieron evaluarse.")
        }
        publishStatus("Auditoría completada")
    }

    private fun logTimingAdvance(cell: CellData) {
        val ta = cell.timingAdvance
        when {
            ta == null -> log("[TA]", "El módem no reporta Timing Advance en esta celda (H6 no juzga geometría).")
            cell.timingAdvanceUnit == TimingAdvanceUnit.STUB_ZERO -> log(
                "[TA]", "El módem devuelve 0 en todas las celdas (${zeroOnlyCellCount()} distintas comprobadas): " +
                    "no es una medida, es un campo sin rellenar. No se deduce distancia."
            )
            ta == 0 && cell.timingAdvanceUnit.isUsableForGeometry -> {
                val step = cell.timingAdvanceUnit.toMeters(1)
                log("[TA]", "TA=0 (${cell.timingAdvanceUnit.name}) → a menos de un paso de TA de la antena (< $step m). " +
                    "No es una medida de 0 m: es el escalón mínimo.")
            }
            else -> {
                val meters = cell.timingAdvanceUnit.toMeters(ta)
                if (meters != null) log("[TA]", "TA=$ta (${cell.timingAdvanceUnit.name}) → ~$meters m de la antena.")
                else log("[TA]", "TA=$ta (${cell.timingAdvanceUnit.name}) — sin conversión defendible; no se usa para geometría.")
            }
        }
    }
}
