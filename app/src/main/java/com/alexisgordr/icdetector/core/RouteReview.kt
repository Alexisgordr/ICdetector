package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellTransitionSummary
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RouteFailureHistory

/**
 * 3.0 (UI2) — Cómo se presenta una ruta de handover. Solo presentación: no cambia el aprendizaje
 * (trusted_observations), H16 ni las alarmas.
 *
 * La ÚLTIMA OBSERVACIÓN y el ÚLTIMO RESULTADO EVALUADO son cosas distintas: si el último handover
 * fue N/A, la ruta se muestra SIN EVALUAR (con su motivo si se guardó), y el último PASSED/FAILED se
 * puede mostrar aparte, con su fecha, nunca como resultado de la lectura actual.
 *
 * REVISAR = la ruta tiene algún FAILED registrado: contado desde el esquema 25 ([CellTransitionSummary.failedCount])
 * o conservado de antes ([RouteFailureHistory.LEGACY_FAILED]). N/A por sí solo no la incluye.
 */
object RouteReview {
    enum class Current { COHERENT, FAILED, NOT_EVALUATED, NO_HANDOVER_OBSERVED }

    /** Estado de la última observación. Sin ningún handover observado (solo viajes), no hay lectura. */
    fun current(route: CellTransitionSummary): Current = when {
        route.observations <= 0 -> Current.NO_HANDOVER_OBSERVED
        route.lastStatus == HeuristicStatus.PASSED -> Current.COHERENT
        route.lastStatus == HeuristicStatus.FAILED -> Current.FAILED
        else -> Current.NOT_EVALUATED
    }

    /** `last_evaluated_status` guardado → solo PASSED o FAILED; cualquier otro valor, desconocido. */
    fun evaluatedFrom(stored: String?): HeuristicStatus? = when (stored) {
        HeuristicStatus.PASSED.name -> HeuristicStatus.PASSED
        HeuristicStatus.FAILED.name -> HeuristicStatus.FAILED
        else -> null
    }

    fun needsReview(route: CellTransitionSummary): Boolean =
        route.failedCount > 0 || route.failureHistory == RouteFailureHistory.LEGACY_FAILED

    /**
     * El último resultado evaluado se muestra APARTE solo cuando no es la lectura actual (la
     * última observación fue N/A). Null si no se conoce o si coincide con la actual.
     */
    fun previousEvaluated(route: CellTransitionSummary): Pair<HeuristicStatus, Long>? {
        if (current(route) != Current.NOT_EVALUATED) return null
        val status = route.lastEvaluatedStatus ?: return null
        val at = route.lastEvaluatedMs ?: return null
        return status to at
    }

    /** Qué se sabe de los fallos, sin inventar el pasado. */
    sealed interface Failures {
        /** Seguida desde el principio: [count] es el total. */
        data class Complete(val count: Int) : Failures
        /** Antes del esquema 25 hubo al menos un fallo (cuántos, no se sabe) + [since] después. */
        data class AtLeast(val since: Int) : Failures
        /** No se sabe si falló antes del esquema 25; [since] desde entonces. */
        data class UnknownBefore(val since: Int) : Failures
    }

    fun failures(route: CellTransitionSummary): Failures = when (route.failureHistory) {
        RouteFailureHistory.COMPLETE -> Failures.Complete(route.failedCount)
        RouteFailureHistory.LEGACY_FAILED -> Failures.AtLeast(route.failedCount)
        RouteFailureHistory.LEGACY_UNKNOWN -> Failures.UnknownBefore(route.failedCount)
    }
}
