package com.alexisgordr.icdetector.core.profile

import com.alexisgordr.icdetector.core.ObservationContext
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.0 (L2) — Una evaluación del perfil por observación aunque se guarde en varias filas. El almacén
 * de prueba tiene las mismas reglas que SQLite (evaluación única por fila y por observación,
 * transacción que se deshace entera si algo lanza) y se conserva al «reiniciar» o «restaurar».
 */
class ProfileObservationProcessorTest {
    private val ctx = ProfileContext("214", "07", "31601", "100", RadioTech.LTE)
    private val day = LocalProfileLearner.DAY_MS
    private val t0 = 1_760_000_000_000L

    /** Almacén con transacciones: si el bloque lanza, vuelve al estado anterior. */
    private class TxStore : ProfileObservationProcessor.Store {
        var profiles = mutableMapOf<String, Map<ProfileMetric, MetricProfile>>()
        var evaluations = mutableListOf<ProfileEvaluationRecord>()
        var failNextEvaluation = false

        override fun hasEvaluation(observationId: String) = evaluations.any { it.observationId == observationId }
        override fun profiles(contextKey: String) = profiles[contextKey].orEmpty()
        override fun upsert(profile: MetricProfile) {
            profiles[profile.contextKey] = profiles[profile.contextKey].orEmpty() + (profile.metric to profile)
        }
        override fun insertEvaluation(record: ProfileEvaluationRecord): Long {
            if (failNextEvaluation) { failNextEvaluation = false; return -1 }
            // UNIQUE(history_id) y UNIQUE(observation_id), como en el esquema 24.
            if (evaluations.any { it.historyId == record.historyId || (record.observationId != null && it.observationId == record.observationId) }) return -1
            evaluations += record; return evaluations.size.toLong()
        }
        fun <T> tx(block: () -> T): Result<T> {
            val p = profiles.toMutableMap(); val e = evaluations.toMutableList()
            return runCatching(block).onFailure { profiles = p; evaluations = e }
        }
        fun copy() = TxStore().also { it.profiles = profiles.toMutableMap(); it.evaluations = evaluations.toMutableList() }
        fun profile() = profiles[ProfileContext("214", "07", "31601", "100", RadioTech.LTE).key]?.get(ProfileMetric.SIGNAL_DBM)
        /** Estadística por nivel: lo que cuenta la pantalla (una fila de evaluación = una). */
        fun levelCounts() = evaluations.groupingBy { it.level }.eachCount()
    }

    private var nextHistoryId = 1L
    private fun row(obsId: String?, at: Long, dbm: Int = -80) = LocalProfileShadow.Observation(
        nextHistoryId++, ctx, dbm, -10, at, "Pixel", obsId)

    private fun process(p: ProfileObservationProcessor, s: TxStore, o: LocalProfileShadow.Observation, now: Long = o.observedAtMs) =
        s.tx { p.onRow(s, o, ProfileMode.SHADOW, now) }

    /** Primer instante (≥ start, paso de 2 min) que NO cae en la reserva. */
    private fun liveAt(start: Long): Long = generateSequence(start) { it + 120_000L }.first { !ProfileBootstrap.isLiveHoldout(ctx.key, it) }
    private fun holdoutAt(start: Long): Long = generateSequence(start) { it + 1_000L }.first { ProfileBootstrap.isLiveHoldout(ctx.key, it) }

    /** Referencia madura en torno a -80 dBm. */
    private fun matureStore(p: ProfileObservationProcessor): Pair<TxStore, Long> {
        val s = TxStore(); var at = t0
        repeat(4) { d -> at = t0 + d * day; repeat(10) { at = liveAt(at + 120_000L); process(p, s, row("m-$d-$it-$at", at, -80 + (it % 3) - 1)).getOrThrow() } }
        assertEquals(ProfileMaturity.MATURE, s.profile()!!.maturity)
        return s to at
    }

    @Test fun `one observation saved as two rows is evaluated, learned and counted once`() {
        val p = ProfileObservationProcessor(); val (s, last) = matureStore(p)
        val before = s.profile()!!
        val at = liveAt(last + 600_000L)
        val periodic = row("obs-1", at, -82); val alarm = row("obs-1", at, -82)
        assertEquals(ProfileObservationProcessor.Outcome.EVALUATED, process(p, s, periodic).getOrThrow())
        val afterFirst = s.profile()!!
        assertEquals(ProfileObservationProcessor.Outcome.ALREADY_EVALUATED, process(p, s, alarm).getOrThrow())
        assertEquals("aprendida una vez", before.liveSamples + 1, s.profile()!!.liveSamples)
        assertEquals("la segunda fila no toca la referencia", afterFirst, s.profile())
        assertEquals("una evaluación, enlazada a la primera fila", listOf(periodic.historyId), s.evaluations.filter { it.observationId == "obs-1" }.map { it.historyId })
        val first = s.evaluations.single { it.observationId == "obs-1" }
        assertEquals("evaluada contra la referencia ANTERIOR", before.generation + ":" + before.revision,
            first.profileRevision!!.substringAfter("SIGNAL_DBM@").substringBefore(";").substringBefore(","))
    }

    @Test fun `two different observations with identical values are both evaluated`() {
        val p = ProfileObservationProcessor(); val (s, last) = matureStore(p)
        val a = liveAt(last + 600_000L); val b = liveAt(a + 120_000L)
        process(p, s, row("obs-a", a, -81)).getOrThrow(); process(p, s, row("obs-b", b, -81)).getOrThrow()
        assertEquals(2, s.evaluations.count { it.observationId in setOf("obs-a", "obs-b") })
        // Incluso en el mismo milisegundo: la identidad no se deduce del instante ni de los valores.
        val c = liveAt(b + 120_000L)
        process(p, s, row("obs-c", c, -81)).getOrThrow(); process(p, s, row("obs-d", c, -81)).getOrThrow()
        assertEquals(2, s.evaluations.count { it.observationId in setOf("obs-c", "obs-d") })
        val x = ObservationContext(c, null, null); val y = ObservationContext(c, null, null)
        assertNotEquals("dos lecturas, dos identidades", x.observationId, y.observationId)
    }

    @Test fun `a reserved observation with two rows is never learned through the other row`() {
        val p = ProfileObservationProcessor(); val (s, last) = matureStore(p)
        val before = s.profile()
        val at = holdoutAt(last + 600_000L)
        process(p, s, row("obs-h", at, -79)).getOrThrow()
        assertEquals(ProfileObservationProcessor.Outcome.ALREADY_EVALUATED, process(p, s, row("obs-h", at, -79)).getOrThrow())
        val e = s.evaluations.single { it.observationId == "obs-h" }
        assertTrue(e.holdout); assertFalse(e.learned)
        assertEquals("nada aprendido", before, s.profile())
    }

    @Test fun `a quarantined reading with two rows is counted once`() {
        val p = ProfileObservationProcessor(); val (s, last) = matureStore(p)
        val q = s.profile()!!.quarantinedSamples
        val at = liveAt(last + 600_000L)
        process(p, s, row("obs-q", at, -40)).getOrThrow(); process(p, s, row("obs-q", at, -40)).getOrThrow()
        assertEquals(q + 1, s.profile()!!.quarantinedSamples)
        assertEquals(1, s.evaluations.count { it.observationId == "obs-q" })
        assertEquals(DeviationLevel.HIGH, s.evaluations.single { it.observationId == "obs-q" }.level)
    }

    @Test fun `a failed evaluation write undoes the learning, and the next row of the observation evaluates it once`() {
        val p = ProfileObservationProcessor(); val (s, last) = matureStore(p)
        val before = s.profile()
        val at = liveAt(last + 600_000L)
        s.failNextEvaluation = true
        assertTrue(process(p, s, row("obs-f", at, -82)).isFailure)
        assertEquals("ni aprendizaje", before, s.profile())
        assertFalse(s.hasEvaluation("obs-f"))
        assertEquals(ProfileObservationProcessor.Outcome.EVALUATED, process(p, s, row("obs-f", at, -82)).getOrThrow())
        assertEquals(1, s.evaluations.count { it.observationId == "obs-f" })
        assertEquals(before!!.liveSamples + 1, s.profile()!!.liveSamples)
    }

    @Test fun `after a restart or a restore the stored evaluation still deduplicates`() {
        val (s, last) = matureStore(ProfileObservationProcessor())
        val at = liveAt(last + 600_000L)
        process(ProfileObservationProcessor(), s, row("obs-r", at)).getOrThrow()
        // Proceso nuevo: procesador nuevo, misma base.
        assertEquals(ProfileObservationProcessor.Outcome.ALREADY_EVALUATED, process(ProfileObservationProcessor(), s, row("obs-r", at)).getOrThrow())
        // Copia restaurada: lo guardado viaja con la base.
        val restored = s.copy()
        assertEquals(ProfileObservationProcessor.Outcome.ALREADY_EVALUATED, process(ProfileObservationProcessor(), restored, row("obs-r", at)).getOrThrow())
        assertEquals(ProfileObservationProcessor.Outcome.EVALUATED, process(ProfileObservationProcessor(), restored, row("obs-new", liveAt(at + 120_000L))).getOrThrow())
    }

    @Test fun `statistics count observations, not rows`() {
        val p = ProfileObservationProcessor(); val (s, last) = matureStore(p)
        val baseline = s.evaluations.size
        var at = last
        repeat(5) { i ->
            at = liveAt(at + 120_000L)
            val rows = if (i % 2 == 0) 2 else 1
            repeat(rows) { process(p, s, row("obs-s$i", at, if (i == 4) -40 else -80)).getOrThrow() }
        }
        assertEquals(5, s.evaluations.size - baseline)
        assertEquals(5, s.levelCounts().values.sum() - baseline)
    }

    @Test fun `rows without identity (before schema 24) keep the previous per-row behaviour`() {
        val p = ProfileObservationProcessor(); val (s, last) = matureStore(p)
        val at = liveAt(last + 600_000L)
        process(p, s, row(null, at)).getOrThrow(); process(p, s, row(null, at)).getOrThrow()
        assertEquals(2, s.evaluations.count { it.observationId == null && it.historyId > 40 })
    }

    @Test fun `bootstrap uses each observation once and reserves 20 percent of observations`() {
        fun r(id: Long, at: Long, obs: String?) = ProfileBootstrap.Row(id, ctx, -80, -10, at, obs)
        val now = t0 + 10 * day
        val rows = (0 until 10).flatMap { i ->
            val at = t0 + i * 3_600_000L
            if (i % 2 == 0) listOf(r(2L * i, at, "o$i"), r(2L * i + 1, at, "o$i")) else listOf(r(2L * i, at, "o$i"))
        }
        val plan = ProfileBootstrap.plan(rows, now, restoredFromBackup = false)
        val all = plan.learn + plan.holdout
        assertEquals("una fila por observación", 10, all.size)
        assertEquals(all.map { it.observationId }.toSet().size, all.size)
        assertEquals("20 % de 10 observaciones, no de 15 filas", 2, plan.holdout.size)
        assertTrue(plan.learn.maxOf { it.observedAtMs!! } < plan.holdout.minOf { it.observedAtMs!! })
        // Filas antiguas sin identidad: como antes, una a una.
        assertEquals(2, ProfileBootstrap.onePerObservation(listOf(r(1, t0, null), r(2, t0, null))).size)
    }
}
