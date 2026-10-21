package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.AnalysisHealth.Change
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** 3.0 — Salud del ciclo de análisis: fallo interno visible, sin alarmas ni reintentos. */
class AnalysisHealthTest {

    /** Simula el límite del ciclo del servicio: un ciclo, su ticket, y el registro de avisos. */
    private class Harness {
        val generation = CollectionGeneration()
        val health = AnalysisHealth()
        val logs = mutableListOf<String>()
        var published = 0

        fun runCycle(ticket: CollectionGeneration.Ticket, fails: Boolean) = runBlocking {
            AnalysisHealth.guard(onFailure = { type ->
                if (health.onCycleFailed(ticket.generation, generation.mayPublish(ticket)) == Change.BECAME_UNAVAILABLE) logs += "FAIL:$type"
            }) {
                if (fails) throw IllegalStateException("lectura fallida de 214-07-31601-100")
                if (!generation.mayPublish(ticket)) return@guard
                published++
                if (health.onCyclePublished(ticket.generation, 1_000L * ticket.generation) == Change.RECOVERED) logs += "RECOVERED"
            }
        }
    }

    @Test fun `a transient read failure is announced once and recovers after a valid cycle`() {
        val h = Harness()
        h.runCycle(h.generation.deliver("A"), fails = false)
        h.runCycle(h.generation.deliver("A"), fails = true)
        assertTrue(h.health.state.unavailable)
        assertEquals("el último resultado válido sigue identificado", 1_000L, h.health.state.lastValidAtMs)
        h.runCycle(h.generation.deliver("A"), fails = false)
        assertFalse(h.health.state.unavailable)
        assertEquals(listOf("FAIL:IllegalStateException", "RECOVERED"), h.logs)
    }

    @Test fun `a persistent failure gives one notice per streak`() {
        val h = Harness()
        repeat(5) { h.runCycle(h.generation.deliver("A"), fails = true) }
        assertEquals(listOf("FAIL:IllegalStateException"), h.logs)
        assertTrue(h.health.state.unavailable)
        assertNull(h.health.state.lastValidAtMs)
        h.runCycle(h.generation.deliver("A"), fails = false)
        repeat(2) { h.runCycle(h.generation.deliver("A"), fails = true) }
        assertEquals("una racha nueva tras recuperarse avisa otra vez",
            listOf("FAIL:IllegalStateException", "RECOVERED", "FAIL:IllegalStateException"), h.logs)
    }

    @Test fun `the error type is reported, never the message`() {
        val h = Harness()
        h.runCycle(h.generation.deliver("A"), fails = true)
        assertFalse(h.logs.single().contains("214-07"))
        assertFalse(AnalysisHealth.FAILURE_LOG.contains("%"))
    }

    @Test fun `an Error is a cycle failure too, reported by type`() {
        listOf<Throwable>(NoClassDefFoundError("vendor/Api"), StackOverflowError(), OutOfMemoryError()).forEach { error ->
            var reported: String? = null
            runBlocking { AnalysisHealth.guard(onFailure = { reported = it }) { throw error } }
            assertEquals(error.javaClass.simpleName, reported)
        }
        val h = Harness()
        val ticket = h.generation.deliver("A")
        runBlocking {
            AnalysisHealth.guard(onFailure = { type ->
                if (h.health.onCycleFailed(ticket.generation, h.generation.mayPublish(ticket)) == Change.BECAME_UNAVAILABLE) h.logs += "FAIL:$type"
            }) { throw NoClassDefFoundError("vendor/Api") }
        }
        assertTrue(h.health.state.unavailable)
        assertEquals(listOf("FAIL:NoClassDefFoundError"), h.logs)
    }

    @Test fun `cancellation is rethrown and never reported as a failure`() {
        var reported = false
        try {
            runBlocking {
                AnalysisHealth.guard(onFailure = { reported = true }) { throw CancellationException("servicio parado") }
            }
            fail("la cancelación debe propagarse")
        } catch (_: CancellationException) { }
        assertFalse(reported)
    }

    @Test fun `old results and errors are discarded after a generation change`() {
        val h = Harness()
        val old = h.generation.deliver("A")
        h.generation.lose()                                   // modo avión / pérdida
        h.runCycle(old, fails = true)
        assertFalse("un ciclo descartado no cambia la salud", h.health.state.unavailable)
        h.runCycle(old, fails = false)
        assertEquals("tampoco publica", 0, h.published)

        // Un fallo de un ciclo más antiguo que el último que ya informó no cuenta, y al revés.
        val health = AnalysisHealth()
        assertEquals(Change.NONE, health.onCyclePublished(6, 6_000))
        assertEquals(Change.NONE, health.onCycleFailed(5, mayPublish = true))
        assertFalse(health.state.unavailable)
        assertEquals(Change.BECAME_UNAVAILABLE, health.onCycleFailed(8, mayPublish = true))
        assertEquals(Change.NONE, health.onCyclePublished(7, 7_000))
        assertTrue("una publicación más antigua no marca recuperación", health.state.unavailable)
    }

    @Test fun `recovery only after a cycle is complete and published`() {
        val health = AnalysisHealth()
        health.onCycleFailed(1, mayPublish = true)
        repeat(3) { assertTrue(health.state.unavailable) }
        assertEquals(Change.RECOVERED, health.onCyclePublished(2, 2_000))
    }

    @Test fun `the boundary never retries, so writes and alarms are not duplicated`() {
        var runs = 0
        var failures = 0
        val writes = mutableListOf<String>()
        runBlocking {
            AnalysisHealth.guard(onFailure = { failures++ }) {
                runs++
                writes += "history"                 // efecto ya hecho antes del fallo
                withContext(Dispatchers.Default) { throw java.io.IOException("disco") }
                @Suppress("UNREACHABLE_CODE") writes += "alert"
            }
        }
        assertEquals(1, runs)
        assertEquals(1, failures)
        assertEquals("ni se repite ni se deshace lo ya hecho, y no hay alerta", listOf("history"), writes)
    }

    @Test fun `a delivery that fails before having a cycle is announced once and recovers`() {
        val h = Harness()
        assertEquals(Change.BECAME_UNAVAILABLE, h.health.onDeliveryFailed())
        assertTrue(h.health.state.unavailable)
        assertEquals("una sola línea por racha", Change.NONE, h.health.onDeliveryFailed())
        h.runCycle(h.generation.deliver("A"), fails = false)
        assertFalse(h.health.state.unavailable)
        assertEquals(listOf("RECOVERED"), h.logs)
    }

    @Test fun `the service reports a failed delivery instead of swallowing it`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val service = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val process = service.substringAfter("private fun processCellInfo(").substringBefore("private fun onDeliveryFailed(")
        assertFalse("ya no se traga el fallo", process.contains("e.printStackTrace()"))
        assertTrue(process.contains("deliveredTicket = cycleTicket"))
        assertTrue(process.contains("onDeliveryFailed(deliveredTicket, e.javaClass.simpleName)"))
        val delivery = service.substringAfter("private fun onDeliveryFailed(").substringBefore("private fun onAnalysisFailed(")
        assertTrue(delivery.contains("onAnalysisFailed(ticket, errorType)"))
        assertTrue(delivery.contains("analysisHealthTracker.onDeliveryFailed()"))
        assertTrue(delivery.contains("AnalysisHealth.FAILURE_LOG} [\$errorType]"))
        listOf("checkAlerts", "securityAlerts", "dbHelper", "_cellFlow").forEach { assertFalse(it, delivery.contains(it)) }
    }

    @Test fun `the screen and health are published before the cycle's alerts and writes`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val service = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val publish = service.substringAfter("if (!collectionGeneration.mayPublish(cycleTicket)) return@withContext")
            .substringBefore("} else {\n                        stableSiteIntensiveActive = false")
        fun at(s: String) = publish.indexOf(s).also { assertTrue("falta: $s", it >= 0) }
        // 3.1.0 — Sin verificación externa: nada se consulta fuera antes de publicar.
        assertFalse(publish.contains("verificationController"))
        val screen = at("_cellFlow.value = sorted.mapIndexed")
        val health = at("onAnalysisPublished(cycleTicket)")
        val tail = at("publicationTail {")
        assertTrue(screen < health && health < tail)
        listOf("dbHelper.recordIncidentPhase(", "forensicRecorder.observe(", "checkAlerts(").forEach {
            assertTrue("$it va después de publicar", at(it) > tail)
        }
        val tailFn = service.substringAfter("private inline fun publicationTail(").substringBefore("\n    }\n")
        assertTrue(tailFn.contains("catch (e: CancellationException)"))
        assertTrue(tailFn.contains("AnalysisHealth.TAIL_FAILURE_LOG"))
        assertFalse("un fallo de la cola no marca «no disponible»", tailFn.contains("analysisHealthTracker"))
    }

    @Test fun `analysis-time effects are written down where they happen`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val tracker = main.resolve("com/alexisgordr/icdetector/service/ServingTransitionTracker.kt").readText()
        assertTrue(tracker.contains("It is the only database write of the cycle that does not go through\n * publication."))
        val failed = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
            .substringBefore("private fun onAnalysisFailed(")
        assertTrue(failed.contains("la transición de H16 (cell_transitions)"))
    }

    @Test fun `the service wires the boundary, recovery and presentation`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }
        val service = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val cycle = service.substringAfter("cellProcessingMutex.withLock {")
        assertTrue(cycle.indexOf("collectionGeneration.isLatest(cycleTicket)") < cycle.indexOf("AnalysisHealth.guard("))
        assertTrue(cycle.contains("withContext(Dispatchers.Main) { onAnalysisFailed(cycleTicket, errorType) }"))
        val publish = cycle.substringAfter("if (!collectionGeneration.mayPublish(cycleTicket)) return@withContext")
        assertEquals("recuperación en las dos ramas de publicación", 2, Regex("""onAnalysisPublished\(cycleTicket\)""").findAll(publish).count())
        val failed = service.substringAfter("private fun onAnalysisFailed(").substringBefore("private fun onAnalysisPublished(")
        listOf("checkAlerts", "securityAlerts", "dbHelper", "_cellFlow", "temporalConfidence", "threatEpisodeTracker", "forensicRecorder")
            .forEach { assertFalse("el fallo no toca $it", failed.contains(it)) }
        assertTrue(failed.contains("publishNotification { updateNotificationText(getString(R.string.analysis_unavailable), force = true) }"))
        assertTrue(failed.contains("collectionGeneration.mayPublish(ticket)"))
        listOf("values", "values-es").forEach { dir ->
            val strings = res.resolve("$dir/strings.xml").readText()
            listOf("analysis_unavailable", "analysis_previous_result_format", "analysis_no_previous_result")
                .forEach { assertTrue("$dir $it", strings.contains("name=\"$it\"")) }
        }
        assertTrue(res.resolve("values-es/strings.xml").readText().contains(">Análisis temporalmente no disponible<"))
        val screen = main.resolve("com/alexisgordr/icdetector/ui/MainScreen.kt").readText()
        assertTrue(screen.contains("AnalysisUnavailableNotice(service)"))
    }
}
