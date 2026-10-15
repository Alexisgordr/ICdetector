package com.alexisgordr.icdetector.core

/**
 * 3.0 — Cómo leer, sin suponer nada, los campos de procedencia de una fila del historial.
 * Una columna nula en filas anteriores a 3.0 es «sin dato», nunca «evaluada» ni «sin fallo».
 */
object RowProvenance {

    /** Reglas que se abstuvieron en la observación, según `not_evaluated_heuristics`. */
    sealed interface NotEvaluated {
        /** La fila no guardaba esta columna (anterior a 3.0): desconocido. */
        data object Unknown : NotEvaluated
        /** `NONE`: todas las reglas se evaluaron. */
        data object None : NotEvaluated
        /** Reglas que no se evaluaron, en el orden guardado. El motivo de cada una no se guarda por fila. */
        data class Rules(val ids: List<String>) : NotEvaluated
    }

    fun notEvaluated(stored: String?): NotEvaluated {
        if (stored == null) return NotEvaluated.Unknown
        if (stored.trim() == "NONE") return NotEvaluated.None
        val ids = stored.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        return if (ids.isEmpty()) NotEvaluated.Unknown else NotEvaluated.Rules(ids)
    }

    /** Precisión guardada del fix, en metros enteros; null si no se guardó o no es válida. */
    fun accuracyMeters(stored: Float?): Int? = stored?.takeIf { it.isFinite() && it >= 0f }?.let { Math.round(it) }
}
