package com.alexisgordr.icdetector.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.10.9 — Secuencias reales del monitor de latencia, con la medición sustituida por valores
 * controlados (sin red).
 */
class NetworkLatencyMonitorTest {
    private val published = mutableListOf<String>()
    private var next: suspend () -> List<Long> = { listOf(50L, 50L, 50L) }
    private val monitor = NetworkLatencyMonitor(
        client = OkHttpClient(),
        log = {},
        publishState = { published += it },
        onPersistentAnomaly = {},
        measurement = { next() }
    )

    /** Cinco mediciones normales: a partir de ahí el monitor publica OK o ANOMALA. */
    private fun learnBaseline(key: String, startMs: Long): Long {
        monitor.onCellChanged(key, "IDLE")
        var now = startMs
        runBlocking { repeat(5) { monitor.check(key, now); now += 30_000L } }
        // Comprobación del propio test: con el baseline aprendido, una medición normal publica OK.
        published.clear()
        runBlocking { monitor.check(key, now) }
        assertEquals(listOf("OK"), published)
        return now + 30_000L
    }

    @Test fun `a result measured before a cell change is not published`() = runBlocking {
        val now = learnBaseline("214-07-100-1-LTE", 30_000L)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<List<Long>>()
        next = { started.complete(Unit); gate.await() }
        val running = async(Dispatchers.Default) { monitor.check("214-07-100-1-LTE", now) }
        started.await()                 // la medición ya está en curso
        monitor.onCellChanged("214-07-100-2-LTE", "IDLE")  // cambio de celda durante la medición
        published.clear()
        gate.complete(listOf(500L, 500L, 500L))
        running.await()
        assertTrue("stale result published: $published", published.isEmpty())
    }

    // Misma celda, pero la sonda se desactiva (Wi-Fi, ajuste) mientras mide: tampoco se publica.
    @Test fun `a result measured before the probe was reset is not published`() = runBlocking {
        val now = learnBaseline("214-07-100-1-LTE", 30_000L)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<List<Long>>()
        next = { started.complete(Unit); gate.await() }
        val running = async(Dispatchers.Default) { monitor.check("214-07-100-1-LTE", now) }
        started.await()
        monitor.reset("N/A")
        published.clear()
        gate.complete(listOf(500L, 500L, 500L))
        running.await()
        assertTrue("stale result published: $published", published.isEmpty())
    }

    @Test fun `a result is published when the cell did not change`() = runBlocking {
        val now = learnBaseline("214-07-100-1-LTE", 30_000L)
        published.clear()
        next = { listOf(500L, 500L, 500L) }
        monitor.check("214-07-100-1-LTE", now)
        assertEquals(listOf("ANOMALA"), published)
    }

    @Test fun `cells sharing a Cell ID do not share a latency baseline`() = runBlocking {
        val now = learnBaseline("214-07-100-1-LTE", 30_000L)
        monitor.onCellChanged("214-01-200-1-LTE", "IDLE")   // mismo CID, otra red
        published.clear()
        // Sin baseline propio todavía, no puede declarar nada.
        next = { listOf(500L, 500L, 500L) }
        monitor.check("214-01-200-1-LTE", now)
        // 3.0 (#26) — Solo puede decir que está aprendiendo, nunca OK ni ANOMALA.
        assertEquals("baseline shared across identities: $published",
            listOf(NetworkLatencyMonitor.STATE_LEARNING), published)
    }

    // La tarea se creó para la celda anterior pero arranca después del cambio: adopta la
    // generación nueva, así que solo la identidad puede rechazarla.
    @Test fun `a check queued for the previous cell is not published after a change`() = runBlocking {
        val now = learnBaseline("214-07-100-1-LTE", 30_000L)
        monitor.onCellChanged("214-07-100-2-LTE", "IDLE")
        published.clear()
        next = { listOf(500L, 500L, 500L) }
        monitor.check("214-07-100-1-LTE", now)
        assertTrue("result for the previous cell published: $published", published.isEmpty())
    }

    // ---------- 3.0 (#26): no medido / aprendiendo / medido ----------

    @Test fun `before five samples the state is learning, never OK`() = runBlocking {
        monitor.onCellChanged("214-07-100-9-LTE", NetworkLatencyMonitor.STATE_NOT_MEASURED)
        var now = 30_000L
        repeat(4) { monitor.check("214-07-100-9-LTE", now); now += 30_000L }
        assertTrue(published.drop(1).all { it == NetworkLatencyMonitor.STATE_LEARNING })
        assertTrue(NetworkLatencyMonitor.STATE_OK !in published)
        assertTrue(!NetworkLatencyMonitor.isMeasured(NetworkLatencyMonitor.STATE_LEARNING))
    }

    @Test fun `after an anomaly, failing endpoints leave the state not measured`() = runBlocking {
        var now = learnBaseline("214-07-100-1-LTE", 30_000L)
        next = { listOf(500L, 500L, 500L) }
        monitor.check("214-07-100-1-LTE", now); now += 30_000L
        assertEquals(NetworkLatencyMonitor.STATE_ANOMALOUS, published.last())
        next = { emptyList() }
        repeat(4) { monitor.check("214-07-100-1-LTE", now); now += 30_000L }
        assertEquals(NetworkLatencyMonitor.STATE_NOT_MEASURED, published.last())
    }

    @Test fun `an OK result expires when nothing is measured for longer than its validity`() = runBlocking {
        var now = learnBaseline("214-07-100-1-LTE", 30_000L)
        published.clear()
        next = { emptyList() }
        monitor.check("214-07-100-1-LTE", now)          // un fallo aislado: el OK sigue valiendo
        assertTrue("an isolated failure must not flicker the state: $published", published.isEmpty())
        now += NetworkLatencyMonitor.MEASUREMENT_TTL_MS + 30_000L
        monitor.check("214-07-100-1-LTE", now)
        assertEquals(listOf(NetworkLatencyMonitor.STATE_NOT_MEASURED), published)
    }

    @Test fun `only OK and ANOMALA count as a measurement for H12`() {
        assertTrue(NetworkLatencyMonitor.isMeasured("OK"))
        assertTrue(NetworkLatencyMonitor.isMeasured("ANOMALA"))
        assertTrue(!NetworkLatencyMonitor.isMeasured("APRENDIENDO"))
        assertTrue(!NetworkLatencyMonitor.isMeasured("N/A"))
    }
}

