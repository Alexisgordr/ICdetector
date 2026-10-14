package com.alexisgordr.icdetector.core

/**
 * 3.0 — Qué dice el terminal tras el mantenimiento diario, sin Android. Un fallo de la poda nunca se
 * presenta como mantenimiento correcto ni como "0 filas": se dice que falló (con el tipo de error,
 * sin su mensaje, que puede contener rutas o datos) y que se reintentará en el siguiente ciclo.
 */
object MaintenanceReport {
    sealed interface Prune {
        data class Done(val historyRowsDeleted: Int) : Prune
        data class Failed(val errorType: String) : Prune
        /** 3.0 — No se podó: la guardia del reloj ([RetentionClockGuard]) lo impidió. */
        data class Skipped(val reason: RetentionClockGuard.Decision) : Prune
    }

    fun pruneLine(outcome: Prune, retentionDays: Int): String? = when (outcome) {
        is Prune.Done -> if (outcome.historyRowsDeleted > 0)
            "Poda de histórico: ${outcome.historyRowsDeleted} registros de más de $retentionDays días eliminados." else null
        is Prune.Failed ->
            "⚠️ Poda de histórico fallida (${outcome.errorType}): no se ha borrado nada; se reintentará en el próximo mantenimiento."
        is Prune.Skipped -> when (val reason = outcome.reason) {
            is RetentionClockGuard.Decision.CutoffAfterNewest ->
                "⚠️ Poda de histórico omitida: el reloj del teléfono va por delante de la última observación guardada; no se ha borrado nada."
            is RetentionClockGuard.Decision.TooManyRows ->
                "⚠️ Poda de histórico omitida: borraría ${reason.due} registros (de ${reason.total}; más del " +
                    "${(RetentionClockGuard.MAX_SHARE * 100).toInt()} %); ¿reloj adelantado? No se ha borrado nada."
            RetentionClockGuard.Decision.Prune -> null
        }
    }
}
