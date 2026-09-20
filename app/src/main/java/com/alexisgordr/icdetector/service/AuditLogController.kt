package com.alexisgordr.icdetector.service

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import com.alexisgordr.icdetector.models.VerificationStatus
import com.alexisgordr.icdetector.models.identityKey

/** Formats the stable 16-rule audit trail without owning service lifecycle or detector state. */
internal class AuditLogController(
    private val log: (String, String) -> Unit,
    private val publishStatus: (String) -> Unit,
    private val zeroOnlyCellCount: () -> Int
) {
    private var auditedIdentity: String? = null
    private var auditedVerification: VerificationStatus? = null

    fun logVerificationOutcome(cell: CellData) {
        if (cell.identityKey != auditedIdentity || cell.verified == auditedVerification) return
        auditedVerification = cell.verified
        val detail = when (cell.verified) {
            VerificationStatus.VERIFIED -> "registrada en bases públicas"
            VerificationStatus.NOT_FOUND -> "sin registro en OpenCellID; no resta puntos porque esa base es incompleta"
            VerificationStatus.REJECTED -> "respuesta descartada o no creíble; no concluye nada sobre la antena"
            VerificationStatus.ERROR -> "las bases públicas no han contestado"
            VerificationStatus.PENDING -> "pendiente de verificar"
        }
        val report = cell.heuristicReport
        log("[AUDIT]", "Celda ${cell.cellId} — índice heurístico: ${cell.securityScore}% " +
            "(${report.evaluatedCount}/${report.totalCount} reglas evaluadas; " +
            "${report.totalCount - report.evaluatedCount} sin datos), $detail")
    }

    fun generate(cell: CellData) {
        publishStatus("Auditoría en curso...")
        auditedIdentity = cell.identityKey
        auditedVerification = cell.verified
        log("[AUDIT]", "--- CICLO DE AUDITORÍA (16 REGLAS) · Celda ${cell.cellId} (${cell.mcc}-${cell.mnc}-${cell.tac}) ---")
        val report = cell.heuristicReport
        linkedMapOf(
            "1. Celda Aislada" to report.isolatedCell,
            "2. Estabilidad Potencia" to report.powerJump,
            "3. Consistencia MCC" to report.mccConsistency,
            "4. Límite MNC" to report.mncCount,
            "5. Validación Regional TAC" to report.tacDeviation,
            "6. Geometría (TA)" to report.taDistance,
            "7. Espectro Fantasma" to report.ghostNeighbors,
            "8. Sanidad ARFCN" to report.arfcnSanity,
            "9. Cifrado Hardware" to report.hardwareCiphering,
            "10. Anti Ping-Pong" to report.pingPong,
            "11. Consistencia Geográfica (Cell ID móvil)" to report.mobileCellId,
            "12. Correlación Latencia + RF" to report.latencyCorrelation,
            "13. Potencia vs Histórico (Baseline + huella RSRQ/SINR)" to report.signalBaseline,
            "14. Downgrade de Banda (Intra-LTE)" to report.bandDowngrade,
            "15. Estabilidad de Identidad RF (PCI)" to report.rfStability,
            "16. Coherencia de Transición" to report.transitionCoherence
        ).forEach { (rule, result) ->
            val status = when (result) {
                HeuristicStatus.PASSED -> "PASSED"
                HeuristicStatus.FAILED -> "FAILED"
                HeuristicStatus.NOT_EVALUATED -> "N/A"
            }
            log("[HEUR]", "$rule: $status")
        }
        logTimingAdvance(cell)

        val pending = cell.verified == VerificationStatus.PENDING
        val title = if (pending) "Resultado provisional (falta la verificación)" else "Resultado Global"
        log("[AUDIT]", "$title: índice heurístico ${cell.securityScore}% sobre " +
            "${report.evaluatedCount}/${report.totalCount} reglas evaluadas; " +
            "${report.totalCount - report.evaluatedCount} sin datos.")
        when {
            cell.isSuspicious -> log("[SEC]", "🚨 CRÍTICO: Antena sospechosa detectada: ${cell.suspiciousReason}")
            cell.suspiciousReason != null -> log("[SYS]", "Sin alarma, pero con observaciones: ${cell.suspiciousReason}")
            pending -> log("[SYS]", "${report.evaluatedCount}/${report.totalCount} reglas evaluadas sin fallos; " +
                "${report.totalCount - report.evaluatedCount} sin datos. Falta la respuesta de las bases públicas.")
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
