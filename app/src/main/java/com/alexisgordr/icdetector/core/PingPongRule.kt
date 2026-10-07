package com.alexisgordr.icdetector.core

/**
 * v2.10.9 — Decisión de H10 (Ping-Pong) sin Android, para poder probarla en la JVM. El analizador
 * solo lee la velocidad de la ubicación y llama aquí.
 *
 * Regla: tres o más cambios de celda en los últimos 10 s mientras el móvil no se mueve rápido.
 */
internal object PingPongRule {
    const val WINDOW_MS = 10_000L
    const val MIN_CHANGES = 3
    /** Por encima de esta velocidad los cambios rápidos son normales (desplazamiento). */
    const val MOVING_FAST_MPS = 8f

    enum class Outcome {
        /** Menos de [MIN_CHANGES] cambios en la ventana: H10 superada. */
        PASSED,
        /** Cambios rápidos, pero el móvil se mueve deprisa: H10 superada. */
        PASSED_MOVING,
        /** Cambios rápidos y velocidad desconocida: no se sabe si está parado, H10 N/A. */
        NOT_EVALUATED,
        /** Cambios rápidos en parado: H10 fallida. */
        FAILED
    }

    /**
     * @param changeTimesMs momento de cada cambio de celda guardado.
     * @param speedMps velocidad GPS, o null si no hay posición o no trae velocidad.
     */
    fun evaluate(changeTimesMs: List<Long>, nowMs: Long, speedMps: Float?): Outcome {
        // Solo cuentan los cambios de la ventana: el servicio poda el historial únicamente cuando
        // llega otro cambio, así que una ráfaga antigua puede seguir guardada.
        val recent = changeTimesMs.count { nowMs - it in 0..WINDOW_MS }
        return when {
            recent < MIN_CHANGES -> Outcome.PASSED
            speedMps == null -> Outcome.NOT_EVALUATED
            speedMps > MOVING_FAST_MPS -> Outcome.PASSED_MOVING
            else -> Outcome.FAILED
        }
    }
}
