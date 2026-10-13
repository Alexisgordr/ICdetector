package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.VerificationStatus
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * 3.0 (V4) — Ciclo de vida de una consulta a OpenCellID respecto al PENDING de su celda:
 * reclamarlo, fallar, descartarse y liberarlo, siempre en este orden y comprobando a la vez la
 * configuración vigente y la PROPIEDAD de la consulta.
 *
 * Antes la liberación iba en un `finally` interior que se ejecutaba ANTES del `catch` exterior: al
 * gestionar la excepción la consulta ya no era dueña, no podía pasar su PENDING a ERROR y la celda
 * quedaba en PENDING sin reintento. Ahora [run] gestiona el error mientras la consulta sigue siendo
 * dueña y libera en el `finally` exterior, después de los `catch`.
 *
 * Orden de candados (igual que el controlador): configuración → época del historial → [cache] →
 * propiedad. [ifEpoch] ejecuta su bloque solo si el historial no se ha borrado desde el inicio.
 */
class VerificationAttempts(
    private val cache: ConcurrentHashMap<String, VerificationStatus>,
    private val errorTimes: ConcurrentHashMap<String, Long>,
    private val config: VerificationConfigHolder,
    private val now: () -> Long,
    /** Se llama (con los candados tomados) al marcar ERROR, p. ej. para borrar esperas propias. */
    private val onErrorMarked: (String) -> Unit = {}
) {
    private val owners = PendingQueries()

    enum class Failure {
        /** La consulta vigente y dueña pasó de PENDING a ERROR: se reintentará. */
        MARKED_ERROR,
        /** La configuración cambió: se quitó su PENDING para que la celda se consulte con la nueva. */
        DISCARDED_STALE,
        /** Ya no era dueña (u otro estado): no se toca nada. */
        NOT_OWNER
    }

    /** Llamar dentro de `synchronized(cache)`, tras poner PENDING (o al re-comprobar un VERIFIED). */
    fun claim(key: String): Long = owners.claim(key)

    fun owns(key: String, query: Long): Boolean = owners.owns(key, query)

    /**
     * Ejecuta [body] como la consulta [query] de [key]. Una excepción (antes de la respuesta o
     * durante su procesamiento) se gestiona con la consulta aún dueña; la cancelación de la
     * corrutina se propaga. La propiedad se libera SIEMPRE al final, después de los `catch`.
     */
    fun run(
        key: String, query: Long, snapshot: VerificationConfig, ifEpoch: (() -> Unit) -> Boolean,
        body: () -> Unit, onFailure: (Exception, Failure) -> Unit = { _, _ -> }
    ) {
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(e, fail(key, query, snapshot, ifEpoch))
        } finally {
            finish(key, query)
        }
    }

    /**
     * Fallo de la consulta: con la configuración vigente (bajo su candado) y si sigue siendo dueña,
     * PENDING → ERROR. Con la configuración ya cambiada, se descarta. Una consulta antigua nunca
     * toca el PENDING de otra.
     */
    fun fail(key: String, query: Long, snapshot: VerificationConfig, ifEpoch: (() -> Unit) -> Boolean): Failure {
        var outcome = Failure.NOT_OWNER
        val current = config.applyIfCurrent(snapshot) {
            ifEpoch {
                synchronized(cache) {
                    if (owners.owns(key, query) && cache[key] == VerificationStatus.PENDING) {
                        cache[key] = VerificationStatus.ERROR
                        errorTimes[key] = now()
                        onErrorMarked(key)
                        outcome = Failure.MARKED_ERROR
                    }
                }
            }
        }
        return if (current) outcome else discard(key, query, ifEpoch)
    }

    /** Consulta de ajustes anteriores: quita su PENDING solo si sigue siendo suyo (no libera). */
    fun discard(key: String, query: Long, ifEpoch: (() -> Unit) -> Boolean): Failure {
        var outcome = Failure.NOT_OWNER
        ifEpoch {
            synchronized(cache) {
                if (owners.owns(key, query) && cache[key] == VerificationStatus.PENDING) {
                    cache.remove(key)
                    outcome = Failure.DISCARDED_STALE
                }
            }
        }
        return outcome
    }

    /**
     * Liberación definitiva. Red de seguridad: si la consulta termina siendo aún dueña de un
     * PENDING (p. ej. cancelada), lo quita para que la celda no quede bloqueada sin reintento.
     */
    fun finish(key: String, query: Long) {
        synchronized(cache) {
            if (owners.release(key, query) && cache[key] == VerificationStatus.PENDING) cache.remove(key)
        }
    }
}
