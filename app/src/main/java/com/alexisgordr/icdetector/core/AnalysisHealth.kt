package com.alexisgordr.icdetector.core

import kotlinx.coroutines.CancellationException

/**
 * 3.0 — Salud del ciclo de análisis del servicio.
 *
 * Antes, una excepción dentro del ciclo terminaba en el manejador genérico de corrutinas: la pantalla
 * y la notificación seguían enseñando el último resultado como si fuera actual. Ahora un fallo
 * interno se distingue de la falta de señal y de una anomalía de red:
 *  - una línea en el terminal al EMPEZAR una racha de fallos y otra al recuperarse, no una por ciclo;
 *  - «Análisis temporalmente no disponible» en la pantalla y en la notificación de monitorización;
 *  - el último resultado válido se presenta como anterior, no actualizado.
 * Un fallo interno nunca genera alarmas de seguridad ni publica un ciclo incompleto.
 *
 * Generaciones ([CollectionGeneration]): solo cuenta el fallo de un ciclo que todavía podía
 * publicar, y nunca el de un ciclo más antiguo que el último que ya informó (éxito o fallo). Así un
 * ciclo descartado no cambia el estado de una generación nueva. La recuperación solo se marca
 * cuando un ciclo válido ha terminado de publicarse.
 *
 * No es seguro entre hilos a propósito: el servicio lo usa solo desde el hilo principal, donde
 * también se publica cada ciclo.
 */
class AnalysisHealth {

    data class State(
        /** Racha de fallos en curso: el resultado visible no se ha actualizado. */
        val unavailable: Boolean = false,
        /** Instante (ms de reloj) del último ciclo válido publicado; null si aún no hay ninguno. */
        val lastValidAtMs: Long? = null
    )

    enum class Change { NONE, BECAME_UNAVAILABLE, RECOVERED }

    var state = State()
        private set

    private var lastReportedGeneration = Long.MIN_VALUE

    /**
     * Un ciclo falló. [mayPublish]: si, en el momento del fallo, ese ciclo todavía habría podido
     * publicar (ninguna interrupción desde su entrega). Un ciclo ya descartado no cuenta.
     */
    fun onCycleFailed(generation: Long, mayPublish: Boolean): Change {
        if (!mayPublish || generation < lastReportedGeneration) return Change.NONE
        lastReportedGeneration = generation
        if (state.unavailable) return Change.NONE
        state = state.copy(unavailable = true)
        return Change.BECAME_UNAVAILABLE
    }

    /** Un ciclo válido terminó de publicarse. Solo aquí se marca la recuperación. */
    fun onCyclePublished(generation: Long, nowMs: Long): Change {
        if (generation < lastReportedGeneration) return Change.NONE
        lastReportedGeneration = generation
        val wasUnavailable = state.unavailable
        state = State(unavailable = false, lastValidAtMs = nowMs)
        return if (wasUnavailable) Change.RECOVERED else Change.NONE
    }

    companion object {
        const val FAILURE_LOG = "Análisis temporalmente no disponible por un fallo interno (no es falta de señal ni una anomalía de red): se mantiene el último resultado válido, no actualizado."
        const val RECOVERY_LOG = "Análisis restablecido: resultado actualizado."

        /**
         * El límite del ciclo: ejecuta [block] UNA vez, sin reintentos. Una cancelación se relanza y
         * no cuenta como fallo; cualquier otro Throwable va a [onFailure] (solo su tipo, nunca el
         * mensaje, que puede llevar identificadores). 3.0 — También los Error (NoClassDefFoundError
         * de una API de fabricante, StackOverflowError, OutOfMemoryError): antes salían al manejador
         * del scope y la pantalla seguía enseñando el último resultado como actual. Lo que [block] ya hizo antes del fallo se
         * queda hecho: no se repite ni se deshace. `inline` para que el ciclo pueda suspender y
         * salir con `return@...` desde dentro, como antes.
         */
        inline fun guard(onFailure: (errorType: String) -> Unit, block: () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onFailure(e.javaClass.simpleName)
            }
        }
    }
}
