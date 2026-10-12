package com.alexisgordr.icdetector.core.profile

import com.alexisgordr.icdetector.core.profile.LocalProfileLearner.Companion.DAY_MS
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0 — Evaluación en sombra contra el perfil local: niveles, abstención y orden prequential. */
class LocalProfileScorerTest {
    private val ctx = ProfileContext("214", "07", "1", "100", RadioTech.LTE)
    private val device = "google/pixel 8"
    private val now = 1_790_000_000_000L

    private fun profile(metric: ProfileMetric, mean: Double, sd: Double, samples: Int = 60, days: Int = 4,
                        lastEvidence: Long = now - 3_600_000L, dev: String = device, radio: RadioTech = RadioTech.LTE,
                        algorithm: Int = LocalProfile.ALGORITHM_VERSION, revision: Long = 60) = MetricProfile(
        contextKey = ctx.key, metric = metric, radio = radio, deviceModel = dev, algorithmVersion = algorithm,
        revision = revision, mean = mean, variance = sd * sd, effectiveSamples = samples, bootstrapSamples = 0,
        liveSamples = samples, quarantinedSamples = 0, distinctDays = days, lastDay = "2026-09-21", daySamples = 10,
        dayStartMean = mean, firstEvidenceMs = now - days * DAY_MS, lastEvidenceMs = lastEvidence,
        lastSampleMs = lastEvidence, maturity = ProfileMaturity.MATURE, abstainReason = null, updatedAtMs = lastEvidence,
        generation = "g1"
    )

    private val mature = mapOf(
        ProfileMetric.SIGNAL_DBM to profile(ProfileMetric.SIGNAL_DBM, -80.0, 4.0),
        ProfileMetric.SIGNAL_RSRQ to profile(ProfileMetric.SIGNAL_RSRQ, -10.0, 2.0, revision = 55)
    )

    private fun eval(profiles: Map<ProfileMetric, MetricProfile>, dbm: Int?, rsrq: Int?, context: ProfileContext = ctx, dev: String = device) =
        LocalProfileScorer.evaluate(profiles, context, mapOf(ProfileMetric.SIGNAL_DBM to dbm, ProfileMetric.SIGNAL_RSRQ to rsrq), dev, now)

    @Test fun `levels follow the largest robust deviation`() {
        assertEquals(DeviationLevel.NONE, eval(mature, -82, -10).level)
        assertEquals(DeviationLevel.LOW, eval(mature, -90, -10).level)        // 2.5
        assertEquals(DeviationLevel.MODERATE, eval(mature, -80, -17).level)   // 3.5
        val high = eval(mature, -60, -10)                                     // 5.0
        assertEquals(DeviationLevel.HIGH, high.level)
        assertEquals(5.0, high.score!!, 1e-9)
        assertEquals(2, high.metricsEvaluated)
        assertEquals(2, high.metricsTotal)
        assertNull(high.abstainReason)
        assertEquals("SIGNAL_DBM@g1:60;SIGNAL_RSRQ@g1:55", high.revision)
        assertTrue(high.evidence!!.contains("SIGNAL_DBM -60 vs -80.000±4.000 z=5.000"))
        assertFalse("sin comas: no rompe el CSV ni depende del idioma", high.evidence!!.contains(","))
    }

    @Test fun `the score is a deviation, not a probability`() {
        assertTrue(eval(mature, -30, -10).score!! > 1.0)
    }

    @Test fun `a missing metric lowers coverage instead of the level`() {
        val r = eval(mature, -80, null)                                       // 3G o módem sin RSRQ
        assertEquals(DeviationLevel.NONE, r.level)
        assertEquals(1, r.metricsEvaluated)
        assertEquals("SIGNAL_DBM@g1:60", r.revision)
    }

    @Test fun `abstains with an explicit reason`() {
        fun reason(p: Map<ProfileMetric, MetricProfile>, dbm: Int? = -80, rsrq: Int? = -10, context: ProfileContext = ctx, dev: String = device) =
            eval(p, dbm, rsrq, context, dev).also { assertEquals(DeviationLevel.ABSTAIN, it.level); assertNull(it.score) }.abstainReason
        assertEquals(AbstainReason.INCOMPLETE_CONTEXT, reason(mature, context = ctx.copy(tac = "N/A")))
        assertEquals(AbstainReason.NO_PROFILE, reason(emptyMap()))
        assertEquals(AbstainReason.PROFILE_IMMATURE, reason(mature.mapValues { it.value.copy(effectiveSamples = 10) }))
        assertEquals(AbstainReason.PROFILE_IMMATURE, reason(mature.mapValues { it.value.copy(distinctDays = 1) }))
        assertEquals(AbstainReason.PROFILE_STALE, reason(mature.mapValues { it.value.copy(lastEvidenceMs = now - 40 * DAY_MS) }))
        assertEquals(AbstainReason.INCOMPATIBLE_DEVICE, reason(mature, dev = "samsung/sm-s911b"))
        assertEquals(AbstainReason.INCOMPATIBLE_RADIO, reason(mature, context = ctx.copy(radio = RadioTech.NR)))
        assertEquals(AbstainReason.INCOMPATIBLE_ALGORITHM, reason(mature.mapValues { it.value.copy(algorithmVersion = 0) }))
        assertEquals(AbstainReason.NO_COMPATIBLE_METRIC, reason(mature, dbm = -999, rsrq = null))
    }

    @Test fun `shadow evaluates before learning and never learns a holdout row`() {
        val shadow = LocalProfileShadow()
        val obs = LocalProfileShadow.Observation(11, ctx, -82, -10, now, device)
        val step = shadow.process(mature, obs, ProfileMode.SHADOW, now, holdout = false)
        assertEquals("evaluada con la referencia anterior", "SIGNAL_DBM@g1:60;SIGNAL_RSRQ@g1:55", step.record.profileRevision)
        assertTrue(step.record.learned)
        assertEquals(setOf(61L, 56L), step.changed.map { it.revision }.toSet())

        val held = shadow.process(mature, obs.copy(historyId = 20), ProfileMode.SHADOW, now, holdout = true)
        assertTrue(held.record.holdout)
        assertFalse(held.record.learned)
        assertTrue(held.changed.isEmpty())
        assertEquals(DeviationLevel.NONE, held.record.level)
    }

    @Test fun `a quarantined observation is evaluated, counted and not learned`() {
        val step = LocalProfileShadow().process(mature, LocalProfileShadow.Observation(11, ctx, -40, -10, now, device), ProfileMode.SHADOW, now)
        assertEquals(DeviationLevel.HIGH, step.record.level)
        assertTrue("RSRQ sí se aprende", step.record.learned)
        val dbm = step.changed.single { it.metric == ProfileMetric.SIGNAL_DBM }
        assertEquals(1, dbm.quarantinedSamples)
        assertEquals(60L, dbm.revision)
    }

    @Test fun `references from different lives never share an identity (F04)`() {
        val before = mature.getValue(ProfileMetric.SIGNAL_DBM)
        val after = before.copy(mean = -105.0, generation = MetricProfile.generationAt(now + 1))
        assertEquals(before.revision, after.revision)
        assertTrue(LocalProfileScorer.revisionOf(before) != LocalProfileScorer.revisionOf(after))
    }

    @Test fun `a stale or old-algorithm reference relearned with the same revision is exported as learned`() {
        val obs = LocalProfileShadow.Observation(11, ctx, -82, -10, now, device)
        // Revisión 1 en ambas: la nueva generación también empieza en 1.
        val stale = mature.mapValues { it.value.copy(revision = 1, lastEvidenceMs = now - 40 * DAY_MS, lastSampleMs = now - 40 * DAY_MS) }
        val oldAlgorithm = mature.mapValues { it.value.copy(revision = 1, algorithmVersion = LocalProfile.ALGORITHM_VERSION - 1) }
        listOf(stale, oldAlgorithm).forEach { before ->
            val step = LocalProfileShadow().process(before, obs, ProfileMode.SHADOW, now, holdout = false)
            assertEquals(setOf(1L), step.changed.map { it.revision }.toSet())
            assertTrue(step.changed.all { it.generation == MetricProfile.generationAt(now) })
            assertTrue("ProfileLearned debe ser 1", step.record.learned)
        }
    }

    @Test fun `the same input always gives the same record`() {
        val obs = LocalProfileShadow.Observation(11, ctx, -70, -12, now, device)
        assertEquals(LocalProfileShadow().process(mature, obs, ProfileMode.SHADOW, now), LocalProfileShadow().process(mature, obs, ProfileMode.SHADOW, now))
    }
}
