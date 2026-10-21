package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.PingPongRule.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v2.10.9 — H10 sin Android: tiempos de los cambios de celda y velocidad (nullable). */
class PingPongRuleTest {
    private val now = 1_700_000_000_000L
    private val burst = listOf(now - 3_000L, now - 2_000L, now - 1_000L)

    @Test fun `three changes in 10 s while stationary fail`() {
        assertEquals(Outcome.FAILED, PingPongRule.evaluate(burst, now, speedMps = 0f))
        assertEquals(Outcome.FAILED, PingPongRule.evaluate(burst, now, speedMps = 8f))
    }

    @Test fun `fewer than three changes pass`() {
        assertEquals(Outcome.PASSED, PingPongRule.evaluate(burst.take(2), now, speedMps = 0f))
        assertEquals(Outcome.PASSED, PingPongRule.evaluate(emptyList(), now, speedMps = null))
    }

    @Test fun `moving fast passes`() {
        assertEquals(Outcome.PASSED_MOVING, PingPongRule.evaluate(burst, now, speedMps = 8.1f))
        assertEquals(Outcome.PASSED_MOVING, PingPongRule.evaluate(burst, now, speedMps = 25f))
    }

    @Test fun `unknown speed is not evaluated, never assumed stationary`() {
        assertEquals(Outcome.NOT_EVALUATED, PingPongRule.evaluate(burst, now, speedMps = null))
    }

    @Test fun `an old burst is forgotten once 10 s have passed`() {
        assertEquals(Outcome.FAILED, PingPongRule.evaluate(burst, now + 6_000L, speedMps = 0f))
        assertEquals(Outcome.FAILED, PingPongRule.evaluate(burst, now + 7_000L, speedMps = 0f))
        assertEquals(Outcome.PASSED, PingPongRule.evaluate(burst, now + 9_000L, speedMps = 0f))
        assertEquals(Outcome.PASSED, PingPongRule.evaluate(burst, now + 3_600_000L, speedMps = 0f))
    }

    @Test fun `only changes inside the window count`() {
        val mixed = listOf(now - 60_000L, now - 30_000L, now - 2_000L, now - 1_000L)
        assertEquals(Outcome.PASSED, PingPongRule.evaluate(mixed, now, speedMps = 0f))
    }

    @Test fun `future timestamps do not count`() {
        val skewed = listOf(now + 1_000L, now + 2_000L, now + 3_000L)
        assertEquals(Outcome.PASSED, PingPongRule.evaluate(skewed, now, speedMps = 0f))
    }

    // ── 3.0 — La velocidad caduca antes que la posición ─────────────────────────────────────

    @Test fun `an old speed does not decide H10`() {
        val now = 100_000L
        val burst = listOf(now - 9_000, now - 5_000, now - 1_000)
        // Parado hace 90 s (semáforo) y tres handovers ahora (tren arrancando): antes FAILED.
        assertEquals(PingPongRule.Outcome.NOT_EVALUATED, PingPongRule.evaluate(burst, now, PingPongRule.liveSpeed(0f, 90_000)))
        // Con un fix reciente (< 30 s), la velocidad sí decide.
        assertEquals(PingPongRule.Outcome.FAILED, PingPongRule.evaluate(burst, now, PingPongRule.liveSpeed(0f, 2_000)))
        assertEquals(PingPongRule.Outcome.PASSED_MOVING, PingPongRule.evaluate(burst, now, PingPongRule.liveSpeed(20f, 2_000)))
    }

    @Test fun `speed needs a known, non-negative age under the H10 limit`() {
        assertEquals("constante propia de H10, no la de la política de GPS", 30_000L, PingPongRule.SPEED_MAX_AGE_MS)
        assertEquals(10_000L, GpsFixAge.LIVE_MAX_AGE_MS)
        assertEquals(3f, PingPongRule.liveSpeed(3f, 0)!!, 0f)
        assertEquals(3f, PingPongRule.liveSpeed(3f, 20_000)!!, 0f)
        assertEquals(3f, PingPongRule.liveSpeed(3f, PingPongRule.SPEED_MAX_AGE_MS - 1)!!, 0f)
        listOf(null, -1L, PingPongRule.SPEED_MAX_AGE_MS, 90_000L, 120_000L).forEach { age ->
            assertEquals("$age", null, PingPongRule.liveSpeed(3f, age))
        }
        assertEquals(null, PingPongRule.liveSpeed(null, 0))
    }

    private fun src(p: String) = listOf("app/src/main/java/com/alexisgordr/icdetector/$p", "src/main/java/com/alexisgordr/icdetector/$p")
        .map(::File).first { it.exists() }.readText()

    @Test fun `the analyzer and the service pass the fix age`() {
        val analyzer = src("core/ThreatAnalyzer.kt")
        assertTrue(analyzer.contains("""pingPongSpeedMps: Float? = PingPongRule.liveSpeed(
            currentLocation?.takeIf { it.hasSpeed() }?.speed, locationFixAgeMs
        ),"""))
        assertTrue(analyzer.contains("PingPongRule.evaluate(cellChangeHistory.map { it.second }, nowMs, pingPongSpeedMps)"))
        assertTrue("el servicio usa el valor por defecto", !src("service/MiniICService.kt").contains("pingPongSpeedMps"))
        val service = src("service/MiniICService.kt")
        assertTrue(service.contains("locationFixAgeMs = deliveryFixAgeMs,"))
        assertTrue(service.contains("val deliveryFixAgeMs = currentLocation?.elapsedRealtimeNanos?.takeIf { it > 0 }"))
        assertTrue("edad en la entrega", service.contains("?.let { deliveredAtElapsedMs - it / 1_000_000L }"))
    }

    @Test fun `without a failed ping-pong the confirmation key is the serving identity`() {
        val history = listOf("A" to now - 3_000L, "B" to now - 2_000L, "A" to now - 1_000L)
        assertEquals("A", PingPongRule.confirmationKey("A", history, now, pingPongFailed = false))
    }

    @Test fun `a failed ping-pong keys the confirmation on the sorted identity set`() {
        val history = listOf("B" to now - 6_000L, "A" to now - 3_000L, "B" to now - 1_000L)
        val onA = PingPongRule.confirmationKey("A", history, now, pingPongFailed = true)
        val onB = PingPongRule.confirmationKey("B", history + ("A" to now), now + 2_000L, pingPongFailed = true)
        assertEquals("pingpong:A,B", onA)
        assertEquals("alternar no cambia la clave", onA, onB)
    }

    @Test fun `identities outside the ping-pong window are not part of the key`() {
        val history = listOf("OLD" to now - 30_000L, "A" to now - 3_000L, "B" to now - 1_000L)
        assertEquals("pingpong:A,B", PingPongRule.confirmationKey("A", history, now, pingPongFailed = true))
    }

    @Test fun `the service confirms a ping-pong on the identity set and keeps one alarm episode`() {
        val service = src("service/MiniICService.kt")
        val key = service.substringAfter("val confirmationKey = com.alexisgordr.icdetector.core.PingPongRule.confirmationKey(")
            .substringBefore("\n                        )")
        assertTrue(key.contains("history = cellChangeHistory"))
        assertTrue("mismo reloj que el historial", key.contains("nowMs = System.currentTimeMillis()"))
        assertTrue(key.contains("heuristicReport.pingPong == HeuristicStatus.FAILED"))
        assertTrue(service.contains("confirmationKey = confirmationKey\n"))
        assertTrue(service.contains("episodeKey = confirmationKey"))
        assertTrue(service.contains("securityAlerts.evaluate(cell, confirmed, context, episodeKey)"))
        val handover = service.substringAfter("if (identityStep.handover) {").substringBefore("cellChangeHistory.add(")
        assertTrue(handover.contains("securityAlerts.onHandover()"))
        assertTrue("el cambio de celda ya no cierra a ciegas", !handover.contains("resetAlarmEpisode()"))
    }

    // ── 3.0 (corte de campaña) — El cambio entra en el historial antes del análisis del mismo ciclo ──────
    private fun id(cid: String) = ServingIdentityChange.Identity("214", "07", "31601", cid, com.alexisgordr.icdetector.models.RadioTech.LTE)

    /** H10 en el ciclo [t] tras registrar (o no) antes la lectura de ese ciclo. */
    private fun h10AtEachCycle(recordBeforeAnalysis: Boolean): List<Outcome> {
        val history = CellChangeHistory(PingPongRule.WINDOW_MS)
        return listOf("1", "2", "1", "2").mapIndexed { i, cid ->
            val now = 1_000_000L + i * 3_000L
            if (recordBeforeAnalysis) history.observe(id(cid), now)
            val outcome = PingPongRule.evaluate(history.snapshot().map { it.second }, now, speedMps = 0f)
            if (!recordBeforeAnalysis) history.observe(id(cid), now)
            outcome
        }
    }

    @Test fun `the third change fails in the cycle that observes it`() {
        // Primera lectura (cuenta como cambio desde nada, como en el servicio) y dos alternancias:
        // tres cambios en 6 s. Con el registro antes del análisis, el ciclo 2 ya falla.
        assertEquals(listOf(Outcome.PASSED, Outcome.PASSED, Outcome.FAILED, Outcome.FAILED), h10AtEachCycle(true))
        // Con el orden antiguo (guardarlo al publicar) el mismo cambio fallaba un ciclo después.
        assertEquals(listOf(Outcome.PASSED, Outcome.PASSED, Outcome.PASSED, Outcome.FAILED), h10AtEachCycle(false))
    }

    @Test fun `the history ignores repeats and unreadable cell ids, and prunes the window`() {
        val history = CellChangeHistory(PingPongRule.WINDOW_MS)
        assertTrue(history.observe(id("1"), 0L))
        assertEquals(false, history.observe(id("1"), 1_000L))
        assertEquals("CID ilegible: no es cambio", false, history.observe(id("N/A"), 2_000L))
        assertTrue(history.observe(id("2"), 3_000L))
        assertTrue(history.observe(id("3"), 20_000L))
        assertEquals("los de hace más de 10 s se podan", listOf("214-07-31601-3-LTE"), history.snapshot().map { it.first })
        history.clear()
        assertTrue("tras borrar, la siguiente vuelve a contar", history.observe(id("3"), 21_000L))
    }

    @Test fun `the service records the change before analysing, and not at publication`() {
        val service = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val observe = service.indexOf("cellChanges.observe(")
        val launch = service.indexOf("writeGate.launchSource(scope, Dispatchers.IO)", observe)
        val analyze = service.indexOf("ThreatAnalyzer.analyzeThreats(", observe)
        assertTrue("se registra al recibir la lectura", observe in 1 until launch)
        assertTrue("y antes del análisis", observe < analyze)
        assertTrue(service.contains("cellChangeHistory = cellChangeHistory,"))
        assertTrue("checkAlerts ya no lo añade", !service.contains("cellChangeHistory.add("))
    }
}
