package com.alexisgordr.icdetector.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 3.0 — Parar no basta: antes de restaurar hay que terminar las escrituras que ya estaban en cola.
 * Bug found during testing: parar el servicio cancelaba su scope y descartaba las no empezadas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WriteGateTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val single = Dispatchers.IO.limitedParallelism(1)

    @Test fun `closing waits for every queued write, then refuses new ones`() = runBlocking {
        val gate = WriteGate()
        val done = AtomicInteger()
        repeat(20) { gate.launch(scope, single) { Thread.sleep(10); done.incrementAndGet() } }
        assertTrue(gate.closeAndDrain(10_000))
        assertEquals("todas las escrituras en cola terminaron", 20, done.get())
        assertNull(gate.launch(scope, single) { done.incrementAndGet() })
        Thread.sleep(50)
        assertEquals("ninguna después de cerrar", 20, done.get())
        scope.cancel()
    }

    /** Caso reproducido: un análisis aceptado escribe DESPUÉS de que empiece el cierre. */
    @Test fun `writes produced by an accepted analysis are kept, not rejected`() = runBlocking {
        val gate = WriteGate()
        val saved = AtomicInteger()
        val analysisStarted = CountDownLatch(1)
        gate.launchSource(scope, Dispatchers.IO) {
            analysisStarted.countDown()
            Thread.sleep(200)                       // el análisis sigue cuando ya se está cerrando
            gate.launch(scope, single) { saved.incrementAndGet() }       // historial
            gate.launch(scope, single) { saved.incrementAndGet() }       // incidente / forense
        }
        analysisStarted.await()
        assertTrue(gate.closeAndDrain(10_000))
        assertEquals("las escrituras del análisis aceptado se guardaron", 2, saved.get())
        scope.cancel()
    }

    @Test fun `a write launched by an accepted write is kept too`() = runBlocking {
        val gate = WriteGate()
        val saved = AtomicInteger()
        gate.launch(scope, single) {
            Thread.sleep(100)
            saved.incrementAndGet()
            gate.launch(scope, single) { Thread.sleep(50); saved.incrementAndGet() }
        }
        assertTrue(gate.closeAndDrain(10_000))
        assertEquals(2, saved.get())
        scope.cancel()
    }

    @Test fun `new analyses and outside writes are refused once their phase is closed`() = runBlocking {
        val gate = WriteGate()
        assertTrue(gate.closeAndDrain(1_000))
        assertNull(gate.launchSource(scope, Dispatchers.IO) {})
        assertNull(gate.launch(scope, Dispatchers.IO) {})
        gate.reopen()
        assertTrue(gate.launchSource(scope, Dispatchers.IO) {} != null)
        scope.cancel()
    }

    @Test fun `the analysis cycle of the service is a source`() {
        val service = listOf(
            File("src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt")
        ).first { it.exists() }.readText()
        assertTrue(service.contains("writeGate.launchSource(scope, Dispatchers.IO) {\n                cellProcessingMutex.withLock {"))
    }

    @Test fun `no write slips in between closing and draining`() = runBlocking {
        val gate = WriteGate()
        val accepted = AtomicInteger()
        val finished = AtomicInteger()
        val start = CountDownLatch(1)
        val writers = (1..8).map {
            thread {
                start.await()
                repeat(200) {
                    if (gate.launch(scope, Dispatchers.IO) { Thread.sleep(1); finished.incrementAndGet() } != null) {
                        accepted.incrementAndGet()
                    }
                }
            }
        }
        start.countDown()
        Thread.sleep(20)
        assertTrue(gate.closeAndDrain(30_000))
        val finishedAtDrain = finished.get()
        writers.forEach { it.join() }
        // Todo lo aceptado antes del cierre había terminado al volver closeAndDrain, y nada más entró.
        assertEquals(accepted.get(), finishedAtDrain)
        Thread.sleep(50)
        assertEquals(accepted.get(), finished.get())
        scope.cancel()
    }

    @Test fun `a write that does not finish in time makes the drain fail`() = runBlocking {
        val gate = WriteGate()
        val release = CountDownLatch(1)
        gate.launch(scope, Dispatchers.IO) { release.await() }
        assertFalse(gate.closeAndDrain(200))
        release.countDown()
        gate.reopen()
        assertFalse(gate.isClosed)
        scope.cancel()
    }

    /** Toda escritura del servicio pasa por la puerta: ningún `scope.launch` toca la base. */
    @Test fun `every database write of the service goes through the gate`() {
        val service = listOf(
            File("src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt")
        ).first { it.exists() }.readText()
        val writers = listOf("dbHelper.", "databaseMaintenance.", "mobilityFamiliarity.", "forensicRecorder.",
            "serviceStatePersistence.", "observationPersistence.", "cellProcessingMutex")
        Regex("scope\\.launch\\(").findAll(service).forEach { m ->
            val open = service.indexOf('{', m.range.last)
            var depth = 0
            var end = open
            while (end < service.length) {
                if (service[end] == '{') depth++ else if (service[end] == '}') { depth--; if (depth == 0) break }
                end++
            }
            val body = service.substring(open, end)
            writers.forEach { w -> assertFalse("scope.launch con $w sin la puerta", body.contains(w)) }
        }
    }
}
