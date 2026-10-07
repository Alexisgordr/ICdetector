package com.alexisgordr.icdetector.service

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Measures cellular latency and owns its per-cell baseline and confirmation state. */
internal class NetworkLatencyMonitor(
    private val client: OkHttpClient,
    private val log: (String) -> Unit,
    private val publishState: (String) -> Unit,
    private val onPersistentAnomaly: () -> Unit,
    /** Solo para tests: sustituye las peticiones HTTP por una medición controlada. */
    private val measurement: (suspend () -> List<Long>)? = null
) {
    private val historyByCell = ConcurrentHashMap<String, MutableList<Long>>()
    private var lastCheckMs = 0L
    private var anomalyStreak = 0

    /**
     * v2.10.9 — Cada reset (cambio de celda, Wi-Fi, sonda desactivada) abre una generación nueva.
     * Una medición que empezó antes del reset ya no publica nada: antes podía terminar después y
     * volver a escribir "OK" o "ANOMALA" de la celda anterior sobre la nueva.
     */
    private var generation = 0L

    /**
     * Celda (identidad completa) para la que vale el estado publicado. El servicio la fija en cada
     * cambio de celda. Un resultado de otra celda no se publica aunque la tarea haya arrancado
     * después del cambio (y por tanto con la generación nueva).
     */
    private var activeCellKey: String? = null

    /** Cambio de celda: abre una generación nueva para [cellKey] y limpia el estado. */
    @Synchronized
    fun onCellChanged(cellKey: String, state: String) {
        activeCellKey = cellKey
        reset(state)
    }

    @Synchronized
    fun reset(state: String = "N/A") {
        generation++
        publishState(state)
        anomalyStreak = 0
    }

    fun isDue(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs - lastCheckMs >= CHECK_INTERVAL_MS

    /** @param cellKey identidad completa de la celda (MCC-MNC-TAC-CID-radio), no solo el CID. */
    suspend fun check(cellKey: String, nowMs: Long = System.currentTimeMillis()) {
        if (!isDue(nowMs)) return
        lastCheckMs = nowMs
        val startedGeneration = synchronized(this) { generation }
        val latencies = measurement?.invoke() ?: measure()
        record(cellKey, latencies, startedGeneration)
    }

    private suspend fun measure(): List<Long> = coroutineScope {
        ENDPOINTS.map { endpoint ->
            async {
                try {
                    val start = System.currentTimeMillis()
                    client.newCall(Request.Builder().url(endpoint).head().build()).execute().use { }
                    System.currentTimeMillis() - start
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** Publica el resultado solo si no hubo un reset (cambio de celda) durante la medición. */
    @Synchronized
    private fun record(cellKey: String, latencies: List<Long>, startedGeneration: Long) {
        if (startedGeneration != generation || cellKey != activeCellKey) return
        if (latencies.size < 2) {
            log("Latencia: sin suficientes endpoints disponibles")
            return
        }
        val average = latencies.average().toLong()
        val history = historyByCell.getOrPut(cellKey) { mutableListOf() }
        if (history.size >= MIN_BASELINE_SAMPLES) {
            val baseline = history.average()
            if (average > baseline * ANOMALY_MULTIPLIER) {
                publishState("ANOMALA")
                anomalyStreak++
                log("⚠ Latencia anómala [$anomalyStreak/$CONFIRMATION_CYCLES]: ${average}ms (media: ${baseline.toInt()}ms) — 3 endpoints")
                if (anomalyStreak >= CONFIRMATION_CYCLES) {
                    log("🔴 ANOMALÍA DE RED PERSISTENTE — posible interferencia MITM")
                    onPersistentAnomaly()
                    anomalyStreak = 0
                }
            } else {
                publishState("OK")
                if (anomalyStreak > 0) log("✅ Latencia normalizada: ${average}ms")
                anomalyStreak = 0
                log("Latencia OK: ${average}ms (media: ${baseline.toInt()}ms)")
            }
        } else {
            log("Latencia: ${average}ms (aprendiendo baseline ${history.size + 1}/$HISTORY_SIZE...)")
        }
        history.add(average)
        if (history.size > HISTORY_SIZE) history.removeAt(0)
    }

    fun prune(activeCellKey: String?, maximumCells: Int) {
        if (historyByCell.size <= maximumCells) return
        historyByCell.keys.filter { it != activeCellKey }
            .take(historyByCell.size - maximumCells)
            .forEach(historyByCell::remove)
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 30_000L
        const val HISTORY_SIZE = 10
        const val MIN_BASELINE_SAMPLES = 5
        const val ANOMALY_MULTIPLIER = 2.5
        const val CONFIRMATION_CYCLES = 3
        val ENDPOINTS = listOf(
            "https://www.google.com/generate_204",
            "https://one.one.one.one/",
            "https://dns.quad9.net/"
        )
    }
}
