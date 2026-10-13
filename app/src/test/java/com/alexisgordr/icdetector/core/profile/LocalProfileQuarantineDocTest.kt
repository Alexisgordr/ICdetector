package com.alexisgordr.icdetector.core.profile

import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (L1) — Lo que el manual dice de la cuarentena sostenida es lo que hace el aprendizaje: las
 * muestras en cuarentena no mueven la última evidencia, la referencia caduca 30 días después de
 * esa evidencia y una muestra aceptada aplaza la caducidad. Sin reinicio automático.
 */
class LocalProfileQuarantineDocTest {
    private val learner = LocalProfileLearner()
    private val ctx = ProfileContext("214", "07", "31601", "100", RadioTech.LTE)
    private val day = LocalProfileLearner.DAY_MS

    private fun sample(v: Int, at: Long) = ProfileSample(ctx, ProfileMetric.SIGNAL_DBM, v, at, "Pixel", Provenance.LIVE)

    /** Referencia madura en torno a -80 dBm: 10 muestras al día durante 4 días. */
    private fun mature(start: Long): MetricProfile {
        var p: MetricProfile? = null
        repeat(4) { d -> repeat(10) { i ->
            val at = start + d * day + i * 120_000L
            p = learner.learn(p, sample(-80 + (i % 3) - 1, at), at).profile
        } }
        return p!!.also { assertEquals(ProfileMaturity.MATURE, it.maturity) }
    }

    @Test fun `quarantined readings never move the last learned evidence and expire 30 days after it`() {
        val t0 = 1_760_000_000_000L
        var p = mature(t0)
        val lastEvidence = p.lastEvidenceMs!!
        // Cambio permanente a -50 dBm: cuarentena una y otra vez durante 29 días.
        var t = lastEvidence + 600_000L
        while (t < lastEvidence + 29 * day) {
            val r = learner.learn(p, sample(-50, t), t)
            assertEquals(LearnOutcome.QUARANTINED, r.outcome)
            p = r.profile!!
            t += 6 * 3_600_000L
        }
        assertEquals("la última evidencia aprendida no se mueve", lastEvidence, p.lastEvidenceMs)
        assertTrue(p.quarantinedSamples > 100)
        assertEquals(ProfileMaturity.STALE, learner.maturity(p, lastEvidence + 30 * day + 1))
        // Pasados 30 días desde esa evidencia, la siguiente lectura empieza otra generación.
        val later = lastEvidence + 30 * day + 3_600_000L
        val relearn = learner.learn(p, sample(-50, later), later)
        assertEquals(LearnOutcome.LEARNED, relearn.outcome)
        assertNotEquals(p.generation, relearn.profile!!.generation)
        assertEquals(ProfileMaturity.LEARNING, relearn.profile!!.maturity)
    }

    @Test fun `an accepted reading postpones staleness, so 30 days is not a fixed duration from the change`() {
        val t0 = 1_760_000_000_000L
        var p = mature(t0)
        val first = p.lastEvidenceMs!!
        val at = first + 20 * day
        p = learner.learn(p, sample(-81, at), at).profile!!
        assertEquals(at, p.lastEvidenceMs)
        assertEquals(ProfileMaturity.MATURE, learner.maturity(p, first + 31 * day))
    }

    @Test fun `the manual explains it and no automatic reset exists`() {
        fun doc(name: String) = listOf(File(name), File("../$name")).first { it.exists() }.readText()
        val manual = doc("MANUAL.md")
        listOf("**Sustained quarantine after a real change.**", "belongs to a **cell identity**",
            "30 days after that **last learned evidence**", "are therefore not counted from the start of the change",
            "The app does not reset a reference by itself because of\nmany quarantined readings")
            .forEach { assertTrue(it, manual.contains(it)) }
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val learnerSrc = main.resolve("com/alexisgordr/icdetector/core/profile/LocalProfileLearner.kt").readText()
        assertTrue("la cuarentena solo cuenta, sin tocar lastEvidenceMs ni reiniciar", learnerSrc.contains("val q = current.copy(quarantinedSamples = current.quarantinedSamples + 1, lastSeenMs = sample.observedAtMs, updatedAtMs = nowMs)"))
    }
}
