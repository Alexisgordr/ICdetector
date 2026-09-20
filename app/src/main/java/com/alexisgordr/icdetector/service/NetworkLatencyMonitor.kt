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
    private val onPersistentAnomaly: () -> Unit
) {
    private val historyByCell = ConcurrentHashMap<String, MutableList<Long>>()
    private var lastCheckMs = 0L
    private var anomalyStreak = 0

    fun reset(state: String = "N/A") {
        publishState(state)
        anomalyStreak = 0
    }

    fun isDue(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs - lastCheckMs >= CHECK_INTERVAL_MS

    suspend fun check(cellId: String, nowMs: Long = System.currentTimeMillis()) {
        if (!isDue(nowMs)) return
        lastCheckMs = nowMs
        val latencies = coroutineScope {
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
        if (latencies.size < 2) {
            log("Latencia: sin suficientes endpoints disponibles")
            return
        }
        val average = latencies.average().toLong()
        val history = historyByCell.getOrPut(cellId) { mutableListOf() }
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

    fun prune(activeCellId: String?, maximumCells: Int) {
        if (historyByCell.size <= maximumCells) return
        historyByCell.keys.filter { it != activeCellId }
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
