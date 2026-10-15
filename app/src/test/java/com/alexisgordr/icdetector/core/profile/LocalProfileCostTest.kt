package com.alexisgordr.icdetector.core.profile

import com.alexisgordr.icdetector.core.profile.LocalProfileLearner.Companion.DAY_MS
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.0 — Coste del perfil local con historiales grandes: proceso por fila constante y almacenamiento
 * de referencias acotado por contextos × métricas, no por la longitud del historial.
 */
class LocalProfileCostTest {
    private val device = "google/pixel 8"
    private val t0 = 1_790_000_000_000L

    /** 90 días, una fila cada 5 minutos (≈26 000 filas) repartidas entre 40 celdas. */
    @Test fun `a campaign-sized history is processed quickly with bounded state`() {
        val shadow = LocalProfileShadow()
        val profiles = HashMap<String, MutableMap<ProfileMetric, MetricProfile>>()
        var evidenceChars = 0L
        val rows = 90 * 24 * 12
        val started = System.nanoTime()
        for (i in 0 until rows) {
            val at = t0 + i * 5 * 60_000L
            val ctx = ProfileContext("214", "07", "1", "${i % 40}", RadioTech.LTE)
            val current = profiles.getOrPut(ctx.key) { mutableMapOf() }
            val step = shadow.process(current, LocalProfileShadow.Observation(i + 1L, ctx, -80 + i % 7, -10 + i % 3, at, device), ProfileMode.SHADOW, at)
            step.changed.forEach { current[it.metric] = it }
            evidenceChars += (step.record.evidence?.length ?: 0) + (step.record.profileRevision?.length ?: 0)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("$rows filas en $elapsedMs ms", elapsedMs < 20_000)
        assertEquals("una referencia por contexto y métrica", 80, profiles.values.sumOf { it.size })
        val perRow = evidenceChars / rows
        assertTrue("texto por evaluación: $perRow caracteres", perRow < 200)
        assertTrue(profiles.values.flatMap { it.values }.all { it.maturity == ProfileMaturity.MATURE || it.maturity == ProfileMaturity.LEARNING })
    }

    @Test fun `bootstrap of a large history keeps only the recent window`() {
        val ctx = ProfileContext("214", "07", "1", "100", RadioTech.LTE)
        val rows = (0 until 50_000).map { ProfileBootstrap.Row(it.toLong(), ctx, -80, -10, t0 + it * 5 * 60_000L) }
        val now = t0 + 50_000L * 5 * 60_000L
        val plan = ProfileBootstrap.plan(rows, now, restoredFromBackup = false)
        val kept = plan.learn.size + plan.holdout.size
        assertTrue("solo los últimos 30 días: $kept", kept <= 30 * DAY_MS / (5 * 60_000L) + 1)
    }
}
