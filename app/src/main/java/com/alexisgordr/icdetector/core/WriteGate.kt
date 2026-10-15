package com.alexisgordr.icdetector.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.CoroutineContext

/**
 * 3.0 — Puerta para el trabajo del servicio que escribe en la base. Antes de guardar la copia de los
 * datos actuales y restaurar hay que TERMINAR lo que ya estaba aceptado: parar el servicio cancela
 * su scope y descartaría lo no empezado.
 *
 * Dos tipos de trabajo:
 *  - [launchSource]: un ciclo de análisis. Lee una observación y después genera escrituras
 *    (historial, incidentes, forense).
 *  - [launch]: una escritura.
 *
 * [closeAndDrain] actúa por fases, para que las escrituras que genera un análisis ya aceptado no se
 * rechacen (bug found during testing: se cerraba todo a la vez y esas escrituras se perdían):
 *  1. No se aceptan análisis nuevos; se espera a que terminen los aceptados. Sus escrituras entran.
 *  2. No se aceptan escrituras nuevas de fuera; se espera a que terminen las aceptadas. Una
 *     escritura lanzada DESDE un trabajo aceptado (análisis o escritura) sigue entrando y también
 *     se espera, hasta que no queda ninguna.
 * Abrir trabajo y cerrar una fase se excluyen con un cerrojo: nada se cuela entre el cierre y la
 * lista de pendientes.
 */
class WriteGate {
    private val lock = ReentrantReadWriteLock()
    private var sourcesClosed = false
    private var writesClosed = false
    private val sources: MutableSet<Job> = Collections.synchronizedSet(mutableSetOf())
    private val writes: MutableSet<Job> = Collections.synchronizedSet(mutableSetOf())

    /** True mientras el hilo ejecuta un trabajo aceptado por esta puerta. */
    private val insideAccepted = ThreadLocal<Boolean>()

    val isClosed: Boolean get() = lock.read { writesClosed }

    /** Un ciclo de análisis. Null si ya no se aceptan. */
    fun launchSource(scope: CoroutineScope, context: CoroutineContext, block: suspend CoroutineScope.() -> Unit): Job? =
        lock.read {
            if (sourcesClosed && insideAccepted.get() != true) return null
            register(scope, context, sources, block)
        }

    /** Una escritura. Null si ya no se aceptan (salvo que la lance un trabajo aceptado). */
    fun launch(scope: CoroutineScope, context: CoroutineContext, block: suspend CoroutineScope.() -> Unit): Job? =
        lock.read {
            if (writesClosed && insideAccepted.get() != true) return null
            register(scope, context, writes, block)
        }

    private fun register(
        scope: CoroutineScope,
        context: CoroutineContext,
        into: MutableSet<Job>,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        val job = scope.launch(context + insideAccepted.asContextElement(true), start = CoroutineStart.LAZY, block = block)
        into += job
        job.invokeOnCompletion { into -= job }
        job.start()
        return job
    }

    /** Fase 1 y fase 2 (ver la clase). False si no terminaron en [timeoutMs]. */
    suspend fun closeAndDrain(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        lock.write { sourcesClosed = true }
        drain(sources)
        lock.write { writesClosed = true }
        drain(sources)
        drain(writes)
        true
    } ?: false

    /** Espera hasta que el conjunto queda vacío (un trabajo puede añadir otro mientras se espera). */
    private suspend fun drain(set: MutableSet<Job>) {
        while (true) {
            val pending = synchronized(set) { set.toList() }
            if (pending.isEmpty()) return
            pending.joinAll()
        }
    }

    /** Vuelve a abrir (la restauración se canceló antes de reemplazar nada). */
    fun reopen() = lock.write { sourcesClosed = false; writesClosed = false }
}
