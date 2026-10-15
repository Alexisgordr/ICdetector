package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.VerificationAttempts.Failure
import com.alexisgordr.icdetector.models.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 3.0 (V4) — Orden de limpieza de una consulta: el error se gestiona con la consulta aún dueña
 * (configuración vigente y propiedad), y la propiedad se libera siempre al final, después del catch.
 */
class VerificationAttemptsTest {
    private val key = "214-7-100-1234-LTE"
    private val cache = ConcurrentHashMap<String, VerificationStatus>()
    private val errorTimes = ConcurrentHashMap<String, Long>()
    private val holder = VerificationConfigHolder("key-A", false)
    private val marked = mutableListOf<String>()
    private var clock = 1_000L
    private val attempts = VerificationAttempts(cache, errorTimes, holder, { clock }) { marked += it }
    private val epoch = HistoryEpoch()

    /** Como verify(): PENDING y propiedad, juntos. */
    private fun start(): Pair<Long, VerificationConfig> = synchronized(cache) {
        cache[key] = VerificationStatus.PENDING
        attempts.claim(key) to holder.current()
    }

    private fun run(q: Pair<Long, VerificationConfig>, generation: Long = epoch.current(), body: () -> Unit): Failure? {
        var failure: Failure? = null
        attempts.run(key, q.first, q.second, { b -> epoch.ifCurrent(generation, b) }, body) { _, f -> failure = f }
        return failure
    }

    @Test fun `an exception before any response turns the current query from PENDING to ERROR and allows a retry`() {
        val q = start()
        val f = run(q) { throw IOException("sin red") }
        assertEquals(Failure.MARKED_ERROR, f)
        assertEquals(VerificationStatus.ERROR, cache[key])
        assertEquals(clock, errorTimes[key])
        assertEquals(listOf(key), marked)
        assertFalse("propiedad liberada", attempts.owns(key, q.first))
        // Reintento: una consulta nueva reclama la celda y termina bien.
        clock += 60_000
        val retry = start()
        assertNull(run(retry) { synchronized(cache) { cache[key] = VerificationStatus.VERIFIED } })
        assertEquals(VerificationStatus.VERIFIED, cache[key])
        assertFalse(attempts.owns(key, retry.first))
    }

    @Test fun `an exception while processing the response also marks ERROR and leaks no lock`() {
        val q = start()
        val f = run(q) {
            holder.applyIfCurrent(q.second) {
                // Aplicando la respuesta (pausas, base…) algo falla.
                throw IllegalStateException("fallo al escribir")
            }
        }
        assertEquals(Failure.MARKED_ERROR, f)
        assertEquals(VerificationStatus.ERROR, cache[key])
        assertFalse(attempts.owns(key, q.first))
        // El candado de lectura se soltó: guardar ajustes no se bloquea.
        val ex = Executors.newSingleThreadExecutor()
        assertEquals(VerificationConfigHolder.Change.CREDENTIAL, ex.submit<VerificationConfigHolder.Change> { holder.update("key-B", false) }.get(2, TimeUnit.SECONDS))
        ex.shutdown()
    }

    @Test fun `an old query failing does not touch the pending of a newer query`() {
        val old = start()
        holder.update("key-B", false)              // cambian los ajustes
        val fresh = start()                         // la celda se consulta con los nuevos
        listOf<() -> Unit>({ throw IOException("antes de la respuesta") },
            { holder.applyIfCurrent(old.second) { error("no debería ejecutarse") }; throw IllegalStateException("procesando") })
            .forEach { body ->
                assertEquals(Failure.NOT_OWNER, run(old, body = body))
                assertEquals(VerificationStatus.PENDING, cache[key])
                assertTrue(attempts.owns(key, fresh.first))
            }
        assertTrue(errorTimes.isEmpty())
        // La nueva, si falla, sí pasa a ERROR.
        assertEquals(Failure.MARKED_ERROR, run(fresh) { throw IOException() })
        assertEquals(VerificationStatus.ERROR, cache[key])
    }

    @Test fun `an old query with the same settings but no longer owner changes nothing`() {
        val first = start()
        val second = start()                        // p. ej. tras borrar el historial y volver a pedir
        assertEquals(Failure.NOT_OWNER, run(first) { throw IOException() })
        assertEquals(VerificationStatus.PENDING, cache[key])
        assertTrue(attempts.owns(key, second.first))
    }

    @Test fun `a failing query whose settings changed frees its own PENDING so the cell is retried`() {
        val q = start()
        holder.update("key-B", true)
        assertEquals(Failure.DISCARDED_STALE, run(q) { throw IOException("cancelada por cambio de ruta") })
        assertNull(cache[key])
        assertTrue(errorTimes.isEmpty())
        assertFalse(attempts.owns(key, q.first))
    }

    @Test fun `after a history deletion a late failure writes nothing`() {
        val q = start()
        val generation = epoch.current()
        epoch.invalidate(); cache.clear()
        assertEquals(Failure.NOT_OWNER, run(q, generation) { throw IOException() })
        assertTrue(cache.isEmpty()); assertTrue(errorTimes.isEmpty())
        assertFalse(attempts.owns(key, q.first))
    }

    @Test fun `ownership is always released - success, failure, cancellation`() {
        val ok = start()
        run(ok) { synchronized(cache) { cache[key] = VerificationStatus.NOT_FOUND } }
        assertFalse(attempts.owns(key, ok.first))
        assertEquals(VerificationStatus.NOT_FOUND, cache[key])

        val failed = start()
        run(failed) { throw RuntimeException() }
        assertFalse(attempts.owns(key, failed.first))

        val cancelled = start()
        var propagated = false
        try { run(cancelled) { throw kotlinx.coroutines.CancellationException("parada") } } catch (_: CancellationException) { propagated = true }
        assertTrue("la cancelación se propaga", propagated)
        assertFalse(attempts.owns(key, cancelled.first))
        assertNull("sin PENDING huérfano tras cancelar", cache[key])
    }

    @Test fun `the controller uses the lifecycle and releases only in the outer finally`() {
        val src = listOf("app/src/main/java/com/alexisgordr/icdetector/service/ExternalVerificationController.kt",
            "src/main/java/com/alexisgordr/icdetector/service/ExternalVerificationController.kt").map(::File).first { it.exists() }.readText()
        assertTrue(src.contains("attempts.run(key, query, snapshot, { block -> ifCurrent(generation, block) },"))
        assertFalse("ningún finally interior libera antes del catch", src.contains("finally {"))
        assertFalse(src.contains(".release("))
        val run = File(listOf("app/src/main/java/com/alexisgordr/icdetector/core/VerificationAttempts.kt",
            "src/main/java/com/alexisgordr/icdetector/core/VerificationAttempts.kt").first { File(it).exists() }).readText()
            .substringAfter("fun run(").substringBefore("fun fail(")
        assertTrue(run.indexOf("catch (e: Exception)") < run.indexOf("finally {"))
        assertTrue(run.substringAfter("finally {").contains("finish(key, query)"))
    }
}
