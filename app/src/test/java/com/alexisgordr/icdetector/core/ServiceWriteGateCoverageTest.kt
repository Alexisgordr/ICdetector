package com.alexisgordr.icdetector.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 3.1.0 (#41) — Ninguna escritura del servicio esquiva la puerta ([WriteGate]). La verificación
 * externa escribía el estado de verificación desde su propio `scope.launch`, fuera de la puerta, y
 * podía tocar la base durante una restauración; se retiró con la verificación (3.1.0). Aquí se
 * comprueba, sobre el código, que cada llamada a un método de CellDbHelper que escribe (los que
 * abren `writableDatabase`) y a los ayudantes que escriben está dentro de un bloque
 * `writeGate.launch` / `writeGate.launchSource`; y, ejecutándolo, que tras el drenaje de
 * `quiesceForRestore` no queda ninguna escritura en vuelo ni entra ninguna nueva.
 */
class ServiceWriteGateCoverageTest {
    private val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        .resolve("com/alexisgordr/icdetector")

    /** Métodos de CellDbHelper que abren la base en escritura. */
    private fun dbWriters(): Set<String> {
        val src = main.resolve("storage/CellDbHelper.kt").readText()
        val funs = Regex("""\n    (?:internal |override |public )?fun (\w+)\(""").findAll(src).toList()
        return funs.mapIndexedNotNull { i, m ->
            val end = funs.getOrNull(i + 1)?.range?.first ?: src.length
            m.groupValues[1].takeIf { src.substring(m.range.last, end).contains("writableDatabase") }
        }.toSet() - setOf("onOpen")
    }

    /** ¿Está [index] dentro de un bloque abierto por `writeGate.launch(`, `writeGate.launchSource(` o `HistoryClear.run(writeGate, …)`? */
    private fun insideGate(source: String, index: Int): Boolean {
        var depth = 0
        var i = index
        while (i > 0) {
            i--
            when (source[i]) {
                '}' -> depth++
                '{' -> if (depth == 0) {
                    val lineStart = source.lastIndexOf('\n', i) + 1
                    val opener = source.substring(lineStart, i)
                    // HistoryClear.run lanza el bloque con gate.launch (HistoryClearTest).
                    if ("writeGate.launch(" in opener || "writeGate.launchSource(" in opener ||
                        "HistoryClear.run(writeGate," in opener) return true
                } else depth--
            }
        }
        return false
    }

    @Test fun `every database writer called by the service runs inside the write gate`() {
        val writers = dbWriters()
        assertTrue("se detectan los escritores", writers.containsAll(listOf("logConnection", "updateNullCoordinates", "recordIncidentPhase", "clear")))
        val service = main.resolve("service/MiniICService.kt").readText()
        val calls = (writers.map { Regex("""dbHelper(?:\.|::)$it\b""") } + listOf(
            Regex("""databaseMaintenance\.run\("""), Regex("""mobilityFamiliarity\.(?:recover|observe)\("""),
            Regex("""transitionTracker\.evaluate\("""), Regex("""forensicRecorder\.observe\("""),
            Regex("""serviceStatePersistence\.persist\(""")
        )).flatMap { it.findAll(service).toList() }
        assertTrue("hay llamadas que comprobar", calls.size >= 10)
        val outside = calls.filterNot { insideGate(service, it.range.first) }
            .map { "${it.value} (línea ${service.substring(0, it.range.first).count { c -> c == '\n' } + 1})" }
        assertEquals(emptyList<String>(), outside)
    }

    @Test fun `no other service file writes to the database on its own`() {
        val writers = dbWriters()
        // Los ayudantes que escriben solo lo hacen cuando el servicio los llama desde la puerta (arriba).
        val allowed = setOf("MiniICService.kt", "DatabaseMaintenance.kt", "ServingTransitionTracker.kt")
        main.resolve("service").listFiles()!!.filter { it.extension == "kt" && it.name !in allowed }.forEach { f ->
            val text = f.readText()
            writers.forEach { w -> assertTrue("${f.name} llama a $w", !Regex("""\b(?:dbHelper|db)\.$w\(""").containsMatchIn(text)) }
        }
        // La persistencia del historial usa la puerta del servicio.
        assertTrue(main.resolve("service/ObservationPersistenceController.kt").readText().contains("writeGate.launch(scope, writeDispatcher)"))
        assertTrue(main.resolve("service/MiniICService.kt").readText()
            .contains("suspend fun quiesceForRestore(timeoutMs: Long = 30_000L): Boolean = writeGate.closeAndDrain(timeoutMs)"))
    }

    @Test fun `after quiesceForRestore drains there are no writes in flight and none can start`() = runBlocking {
        val gate = WriteGate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inFlight = AtomicInteger()
        val done = AtomicInteger()
        suspend fun write() { inFlight.incrementAndGet(); delay(5); done.incrementAndGet(); inFlight.decrementAndGet() }
        // Ciclos de análisis que generan escrituras (historial, incidentes, forense) y escrituras sueltas.
        repeat(20) {
            gate.launchSource(scope, Dispatchers.Default) {
                delay(2)
                repeat(3) { gate.launch(scope, Dispatchers.IO) { write() } }
            }
            gate.launch(scope, Dispatchers.IO) { write() }
        }
        assertTrue(gate.closeAndDrain(5_000))
        assertEquals("nada en vuelo tras el drenaje", 0, inFlight.get())
        assertEquals("todas las escrituras aceptadas terminaron", 20 * 3 + 20, done.get())
        assertNull("una escritura nueva se rechaza", gate.launch(scope, Dispatchers.IO) { write() })
        assertNull("y un análisis nuevo también", gate.launchSource(scope, Dispatchers.Default) { write() })
        delay(50)
        assertEquals(0, inFlight.get())
        assertEquals(80, done.get())
    }
}
