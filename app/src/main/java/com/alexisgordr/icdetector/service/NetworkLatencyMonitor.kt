package com.alexisgordr.icdetector.service

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Measures cellular latency and owns its per-cell baseline and confirmation state.
 *
 * 3.0 (#26) — Estados explícitos, publicados como texto (se guardan tal cual en la caja negra):
 *  - [STATE_NOT_MEASURED] (`N/A`): no hay medición válida para esta celda (sonda desactivada,
 *    Wi-Fi/VPN/Tor, recién cambiada de celda, servidores sin responder o resultado caducado).
 *  - [STATE_LEARNING] (`APRENDIENDO`): hay mediciones, pero aún no las [MIN_BASELINE_SAMPLES]
 *    que hacen falta para tener una referencia. Antes se seguía mostrando "OK".
 *  - [STATE_OK] / [STATE_ANOMALOUS]: medido contra la referencia, válido [MEASUREMENT_TTL_MS].
 * Solo OK y ANOMALA cuentan como medición para H12.
 */
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
    /** 3.0 (#26) — Momento del último resultado medido (OK o ANOMALA). */
    private var lastMeasuredAtMs = 0L

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

    /** Último estado publicado por este monitor. */
    private var lastPublished: String? = null

    private fun publish(state: String) {
        lastPublished = state
        publishState(state)
    }

    @Synchronized
    fun reset(state: String = STATE_NOT_MEASURED) {
        generation++
        publish(state)
        anomalyStreak = 0
        lastMeasuredAtMs = 0L
    }

    /** 3.0 (#26) — Un OK/ANOMALA sin medición nueva en [MEASUREMENT_TTL_MS] pasa a "no medido". */
    @Synchronized
    fun expireIfStale(nowMs: Long = System.currentTimeMillis()) {
        if ((lastPublished == STATE_OK || lastPublished == STATE_ANOMALOUS) &&
            nowMs - lastMeasuredAtMs > MEASUREMENT_TTL_MS) {
            publish(STATE_NOT_MEASURED)
            anomalyStreak = 0
        }
    }

    fun isDue(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs - lastCheckMs >= CHECK_INTERVAL_MS

    /** @param cellKey identidad completa de la celda (MCC-MNC-TAC-CID-radio), no solo el CID. */
    suspend fun check(cellKey: String, nowMs: Long = System.currentTimeMillis()) {
        expireIfStale(nowMs)
        if (!isDue(nowMs)) return
        lastCheckMs = nowMs
        val startedGeneration = synchronized(this) { generation }
        val latencies = measurement?.invoke() ?: measure()
        record(cellKey, latencies, startedGeneration, nowMs)
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
    private fun record(cellKey: String, latencies: List<Long>, startedGeneration: Long, nowMs: Long) {
        if (startedGeneration != generation || cellKey != activeCellKey) return
        if (latencies.size < 2) {
            log("Latencia: sin suficientes endpoints disponibles")
            // v2.10.10 — Sin medición válida no se mantiene un "ANOMALA" anterior ni su racha:
            // antes se quedaban tal cual mientras fallaran los servidores, sin fecha de validez.
            // 3.0 (#26) — Un OK también caduca: pasado el TTL sin medir, "no medido".
            if (lastPublished == STATE_ANOMALOUS) publish(STATE_NOT_MEASURED)
            anomalyStreak = 0
            expireIfStale(nowMs)
            return
        }
        val average = latencies.average().toLong()
        val history = historyByCell.getOrPut(cellKey) { mutableListOf() }
        if (history.size >= MIN_BASELINE_SAMPLES) {
            val baseline = history.average()
            lastMeasuredAtMs = nowMs
            if (average > baseline * ANOMALY_MULTIPLIER) {
                publish(STATE_ANOMALOUS)
                anomalyStreak++
                log("⚠ Latencia anómala [$anomalyStreak/$CONFIRMATION_CYCLES]: ${average}ms (media: ${baseline.toInt()}ms) — 3 endpoints")
                if (anomalyStreak >= CONFIRMATION_CYCLES) {
                    log("🔴 ANOMALÍA DE RED PERSISTENTE — posible interferencia MITM")
                    onPersistentAnomaly()
                    anomalyStreak = 0
                }
            } else {
                publish(STATE_OK)
                if (anomalyStreak > 0) log("✅ Latencia normalizada: ${average}ms")
                anomalyStreak = 0
                log("Latencia OK: ${average}ms (media: ${baseline.toInt()}ms)")
            }
        } else {
            // 3.0 (#26) — Aprendiendo: ni OK ni anómala. H12 queda N/A.
            publish(STATE_LEARNING)
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

    companion object {
        const val STATE_NOT_MEASURED = "N/A"
        const val STATE_LEARNING = "APRENDIENDO"
        const val STATE_OK = "OK"
        const val STATE_ANOMALOUS = "ANOMALA"

        /** ¿El estado es una medición contra la referencia? Solo entonces H12 se evalúa. */
        fun isMeasured(state: String) = state == STATE_OK || state == STATE_ANOMALOUS

        /** 3.0 (#26) — Validez de un resultado medido: tres comprobaciones. */
        const val MEASUREMENT_TTL_MS = 90_000L
        private const val CHECK_INTERVAL_MS = 30_000L
        private const val HISTORY_SIZE = 10
        const val MIN_BASELINE_SAMPLES = 5
        private const val ANOMALY_MULTIPLIER = 2.5
        private const val CONFIRMATION_CYCLES = 3
        private val ENDPOINTS = listOf(
            "https://www.google.com/generate_204",
            "https://one.one.one.one/",
            "https://dns.quad9.net/"
        )
    }
}
