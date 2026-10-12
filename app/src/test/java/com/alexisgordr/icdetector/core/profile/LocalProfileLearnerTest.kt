package com.alexisgordr.icdetector.core.profile

import com.alexisgordr.icdetector.core.profile.LocalProfileLearner.Companion.DAY_MS
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

/** 3.0 — Aprendizaje protegido del perfil local. */
class LocalProfileLearnerTest {
    private val learner = LocalProfileLearner()
    private val ctx = ProfileContext("214", "07", "1", "100", RadioTech.LTE)
    private val device = "google/pixel 8"
    private val t0 = 1_790_000_000_000L

    private fun sample(v: Int?, at: Long, metric: ProfileMetric = ProfileMetric.SIGNAL_DBM, context: ProfileContext = ctx,
                       dev: String = device, prov: Provenance = Provenance.LIVE) = ProfileSample(context, metric, v, at, dev, prov)

    /** [perDay] muestras por día durante [days] días, cada 5 minutos, con valores que alternan alrededor de [center]. */
    private fun train(days: Int, perDay: Int = 20, center: Int = -80, start: MetricProfile? = null, from: Long = t0): MetricProfile {
        var p = start
        for (d in 0 until days) for (i in 0 until perDay) {
            val at = from + d * DAY_MS + i * 5 * 60_000L
            p = learner.learn(p, sample(center + listOf(-2, -1, 0, 1, 2)[i % 5], at), at).profile
        }
        return p!!
    }

    @Test fun `learns a reference and becomes mature only with enough samples and days`() {
        val p = train(days = 4)
        assertEquals(ProfileMaturity.MATURE, p.maturity)
        assertTrue(abs(p.mean!! - (-80.0)) < 1.0)
        assertEquals(80, p.effectiveSamples)
        assertEquals(4, p.distinctDays)
        assertEquals(80L, p.revision)
        assertEquals(80, p.liveSamples)
        assertEquals(0, p.bootstrapSamples)
    }

    @Test fun `one long session in a single day never makes a profile mature`() {
        var p: MetricProfile? = null
        val outcomes = mutableListOf<LearnOutcome>()
        repeat(300) { i ->
            val at = t0 + i * 61_000L
            val r = learner.learn(p, sample(-80, at), at); p = r.profile; outcomes += r.outcome
        }
        assertEquals(48, p!!.effectiveSamples)
        assertEquals(ProfileMaturity.LEARNING, p!!.maturity)
        assertTrue(outcomes.count { it == LearnOutcome.DAILY_CAP } > 200)
    }

    @Test fun `absent, invalid and old samples are not learned`() {
        val p = train(days = 1, perDay = 5)
        val at = t0 + 2 * DAY_MS
        assertEquals(LearnOutcome.INVALID, learner.learn(p, sample(null, at), at).outcome)
        assertEquals(LearnOutcome.INVALID, learner.learn(p, sample(Int.MAX_VALUE, at), at).outcome)
        assertEquals(LearnOutcome.INVALID, learner.learn(p, sample(-999, at), at).outcome)
        assertEquals(LearnOutcome.TOO_OLD, learner.learn(p, sample(-80, at - 31 * DAY_MS), at).outcome)
        assertEquals(LearnOutcome.INCOMPLETE_CONTEXT, learner.learn(p, sample(-80, at, context = ctx.copy(cellId = "N/A")), at).outcome)
    }

    @Test fun `repeated and out-of-order samples count once`() {
        val first = learner.learn(null, sample(-80, t0), t0).profile
        assertEquals(LearnOutcome.DUPLICATE, learner.learn(first, sample(-80, t0 + 10_000), t0 + 10_000).outcome)
        assertEquals(LearnOutcome.DUPLICATE, learner.learn(first, sample(-80, t0 - 120_000), t0).outcome)
        assertEquals(LearnOutcome.LEARNED, learner.learn(first, sample(-80, t0 + 60_000), t0 + 60_000).outcome)
    }

    @Test fun `a doubtful observation is quarantined and does not move the reference`() {
        val p = train(days = 4)
        val at = t0 + 5 * DAY_MS
        val r = learner.learn(p, sample(-45, at), at)
        assertEquals(LearnOutcome.QUARANTINED, r.outcome)
        assertEquals(p.mean, r.profile!!.mean)
        assertEquals(p.revision, r.profile!!.revision)
        assertEquals(1, r.profile!!.quarantinedSamples)
    }

    private fun eval(p: MetricProfile, value: Int, at: Long) = LocalProfileScorer.evaluate(
        mapOf(ProfileMetric.SIGNAL_DBM to p), ctx, mapOf(ProfileMetric.SIGNAL_DBM to value, ProfileMetric.SIGNAL_RSRQ to null), device, at
    )

    /** Ráfaga de [n] muestras en un día, cada una justo por debajo del umbral de cuarentena. */
    private fun burst(p: MetricProfile, day: Long, n: Int = 48): MetricProfile {
        var q = p
        repeat(n) { i ->
            val at = day + i * 61_000L
            q = learner.learn(q, sample(floor(q.mean!! + 3.9 * learner.spread(q)).toInt().coerceIn(-140, -30), at), at).profile!!
        }
        return q
    }

    @Test fun `a strange burst cannot redefine normal in a day`() {
        val p = train(days = 4)
        val floor = ProfileMetric.SIGNAL_DBM.floor
        val q = burst(p, t0 + 5 * DAY_MS, n = 200)
        assertTrue("la ráfaga se aprende, no se pone toda en cuarentena", q.revision > p.revision)
        assertTrue("la media se mueve como mucho un ruido mínimo al día", q.mean!! - p.mean!! <= floor + 1e-9)
    }

    @Test fun `a burst cannot make the reference permissive in a day (F02)`() {
        val p = train(days = 4)
        val day = t0 + 5 * DAY_MS
        assertEquals(DeviationLevel.HIGH, eval(p, -45, day).level)
        val q = burst(p, day)
        val floor = ProfileMetric.SIGNAL_DBM.floor
        assertTrue("dispersión ${learner.spread(q)}", learner.spread(q) <= learner.spread(p) + floor + 1e-9)
        assertEquals("antes de F02 pasaba a NONE", DeviationLevel.HIGH, eval(q, -45, day + 4_000_000L).level)
    }

    @Test fun `a genuinely noisier cell is still learned over days`() {
        var p: MetricProfile? = null
        val values = listOf(-92, -86, -80, -74, -68, -80)                           // dispersión real ≈ 8 dB
        for (d in 0 until 10) for (i in 0 until 30) {
            val at = t0 + d * DAY_MS + i * 5 * 60_000L
            p = learner.learn(p, sample(values[(i + d) % values.size], at), at).profile
        }
        assertTrue("dispersión ${learner.spread(p!!)}", learner.spread(p!!) > 6.0)
        assertEquals(ProfileMaturity.MATURE, p!!.maturity)
    }

    @Test fun `a reference from another algorithm version is relearned with a new generation`() {
        val old = train(days = 4).copy(algorithmVersion = LocalProfile.ALGORITHM_VERSION - 1, generation = "g1")
        val at = t0 + 5 * DAY_MS
        val r = learner.learn(old, sample(-80, at), at)
        assertEquals(LearnOutcome.LEARNED, r.outcome)
        assertEquals(1L, r.profile!!.revision)
        assertEquals(LocalProfile.ALGORITHM_VERSION, r.profile!!.algorithmVersion)
        assertEquals(MetricProfile.generationAt(at), r.profile!!.generation)
    }

    @Test fun `gradual contamination is slowed, bounded by days, not prevented`() {
        var q = train(days = 4)
        val start = q.mean!!
        val floor = ProfileMetric.SIGNAL_DBM.floor
        val days = 5
        for (d in 0 until days) repeat(48) { i ->
            val at = t0 + (5 + d) * DAY_MS + i * 61_000L
            val target = floor(q.mean!! + 3.9 * learner.spread(q)).toInt()
            q = learner.learn(q, sample(target, at), at).profile!!
        }
        val shift = q.mean!! - start
        assertTrue("acotado por días: $shift", shift <= days * floor + 1e-9)
        assertTrue("pero no impedido (limitación documentada): $shift", shift > 0)
    }

    @Test fun `a context change starts another profile and another phone is incompatible`() {
        val p = train(days = 1, perDay = 5)
        val at = t0 + 2 * DAY_MS
        assertEquals(LearnOutcome.INCOMPATIBLE, learner.learn(p, sample(-80, at, dev = "samsung/sm-s911b"), at).outcome)
        val nr = learner.learn(null, sample(-80, at, context = ctx.copy(radio = RadioTech.NR)), at).profile!!
        assertTrue(nr.contextKey != p.contextKey)
        assertEquals(LearnOutcome.INCOMPATIBLE, learner.learn(p, sample(-80, at, context = ctx.copy(radio = RadioTech.NR)), at).outcome)
    }

    @Test fun `a reset profile starts a new generation (F04)`() {
        val first = train(days = 4)
        val later = t0 + 10 * DAY_MS
        val relearned = train(days = 4, center = -105, from = later)
        assertEquals(MetricProfile.generationAt(t0), first.generation)
        assertEquals(MetricProfile.generationAt(later), relearned.generation)
        assertEquals(first.revision, relearned.revision)
        assertTrue(LocalProfileScorer.revisionOf(first) != LocalProfileScorer.revisionOf(relearned))
    }

    @Test fun `a profile without recent evidence becomes stale`() {
        val p = train(days = 4)
        assertEquals(ProfileMaturity.STALE, learner.maturity(p, p.lastEvidenceMs!! + 31 * DAY_MS))
    }

    @Test fun `bootstrap keeps provenance and reserves the latest rows for evaluation`() {
        val rows = (0 until 50).map { ProfileBootstrap.Row(it.toLong(), ctx, -80, -10, t0 + it * 600_000L) } +
            ProfileBootstrap.Row(999, ctx, -80, -10, null)                     // sin instante exacto: fuera
        val now = t0 + 50 * 600_000L
        val plan = ProfileBootstrap.plan(rows, now, restoredFromBackup = false)
        assertEquals(40, plan.learn.size)
        assertEquals(10, plan.holdout.size)
        assertTrue(plan.learn.maxOf { it.observedAtMs!! } < plan.holdout.minOf { it.observedAtMs!! })
        assertFalse(plan.learn.any { it.historyId == 999L } || plan.holdout.any { it.historyId == 999L })
        var p: MetricProfile? = null
        plan.learn.forEach { r -> p = learner.learn(p, sample(r.dbm, r.observedAtMs!!, prov = Provenance.BOOTSTRAP_HISTORY), now).profile }
        assertEquals(40, p!!.bootstrapSamples)
        assertEquals(0, p!!.liveSamples)
    }

    @Test fun `a history restored from another phone never bootstraps the profile`() {
        val rows = listOf(ProfileBootstrap.Row(1, ctx, -80, null, t0))
        assertTrue(ProfileBootstrap.plan(rows, t0, restoredFromBackup = true).learn.isEmpty())
    }

    @Test fun `live holdout is deterministic, about one in ten and not tied to a rhythm`() {
        val times = (1L..10_000L).map { t0 + it * 5 * 60_000L }                  // una lectura cada 5 min
        val held = times.filter { ProfileBootstrap.isLiveHoldout(ctx.key, it) }
        assertTrue(held.size in 800..1200)
        assertEquals(held, times.filter { ProfileBootstrap.isLiveHoldout(ctx.key, it) })
        listOf("1", "2", "3").forEach { cid ->
            val key = ctx.copy(cellId = cid).key
            assertTrue(times.count { ProfileBootstrap.isLiveHoldout(key, it) } in 800..1200)
        }
    }

    @Test fun `all rows of one observation share the holdout decision (F03)`() {
        val shadow = LocalProfileShadow()
        val p = mapOf(ProfileMetric.SIGNAL_DBM to train(days = 4))
        val at = (0L until 1000L).map { t0 + 5 * DAY_MS + it * 61_000L }.first { ProfileBootstrap.isLiveHoldout(ctx.key, it) }
        // Fila periódica (id 1) y fila de alarma confirmada (id 2) de la misma lectura.
        val periodic = shadow.process(p, LocalProfileShadow.Observation(1, ctx, -74, null, at, device), ProfileMode.SHADOW, at)
        val alarm = shadow.process(p + periodic.changed.associateBy { it.metric }, LocalProfileShadow.Observation(2, ctx, -74, null, at, device), ProfileMode.SHADOW, at)
        assertTrue(periodic.record.holdout && alarm.record.holdout)
        assertFalse(periodic.record.learned || alarm.record.learned)
        assertEquals(periodic.record.profileRevision, alarm.record.profileRevision)
        assertEquals(periodic.record.score, alarm.record.score)
    }

    @Test fun `bootstrap never splits rows of the same instant (F03)`() {
        val rows = (0 until 10).flatMap { i -> listOf(1, 2).map { k -> ProfileBootstrap.Row(i * 2L + k, ctx, -80, -10, t0 + i * 600_000L) } }
        val plan = ProfileBootstrap.plan(rows, t0 + 10 * 600_000L, restoredFromBackup = false, holdoutFraction = 0.25)
        val learnTimes = plan.learn.map { it.observedAtMs }.toSet()
        assertTrue(plan.holdout.none { it.observedAtMs in learnTimes })
        assertEquals(20, plan.learn.size + plan.holdout.size)
    }
}
