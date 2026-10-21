package com.alexisgordr.icdetector.core

import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 3.1.0 — Borrado del historial a través de la puerta de escrituras ([WriteGate]) y su resultado.
 *
 * Antes, cualquier fallo de la base durante el borrado se informaba como «restauración en curso»:
 * la excepción terminaba la escritura sin marcar el borrado y el servicio solo sabía que no se había
 * hecho. Ahora se distinguen los dos casos: la puerta cerrada (hay una restauración en curso, no se
 * intenta) y un error al borrar (se intentó y falló, con la clase del error).
 */
internal object HistoryClear {

    sealed interface Outcome {
        data object Cleared : Outcome
        /** La puerta estaba cerrada: no se intentó. */
        data object RestoreInProgress : Outcome
        /** Se intentó y [clear] lanzó [errorClass]. */
        data class Failed(val errorClass: String) : Outcome
    }

    /** Borra con [clear] dentro de la puerta y espera a que termine. La cancelación se relanza. */
    suspend fun run(gate: WriteGate, scope: CoroutineScope, context: CoroutineContext, clear: () -> Unit): Outcome {
        var outcome: Outcome = Outcome.RestoreInProgress
        val job = gate.launch(scope, context) {
            outcome = try {
                clear()
                Outcome.Cleared
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed(e.javaClass.simpleName.ifEmpty { e.javaClass.name })
            }
        } ?: return Outcome.RestoreInProgress
        job.join()
        return outcome
    }

    /** Línea del terminal (en castellano; TerminalLocalization la traduce). */
    fun logLine(outcome: Outcome): String = when (outcome) {
        Outcome.Cleared -> "Historial borrado: captura forense, episodios y cachés reiniciados."
        Outcome.RestoreInProgress -> "Historial NO borrado: hay una restauración en curso."
        is Outcome.Failed -> "Historial NO borrado: error ${outcome.errorClass}."
    }
}
