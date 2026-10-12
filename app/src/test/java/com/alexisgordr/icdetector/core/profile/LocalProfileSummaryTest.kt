package com.alexisgordr.icdetector.core.profile

import com.alexisgordr.icdetector.core.profile.LocalProfileLearner.Companion.DAY_MS
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 (F08) — La tarjeta de Ajustes cuenta con el estado actual y se refresca mientras está visible. */
class LocalProfileSummaryTest {
    private val device = "google/pixel 8"
    private val now = 1_790_000_000_000L

    private fun profile(cell: String, dev: String = device, lastEvidence: Long = now - 3_600_000L) = MetricProfile(
        contextKey = "214-07-1-$cell-LTE", metric = ProfileMetric.SIGNAL_DBM, radio = RadioTech.LTE, deviceModel = dev,
        algorithmVersion = LocalProfile.ALGORITHM_VERSION, revision = 60, mean = -80.0, variance = 9.0, effectiveSamples = 60,
        bootstrapSamples = 0, liveSamples = 60, quarantinedSamples = 0, distinctDays = 4, lastDay = null, daySamples = 0,
        dayStartMean = -80.0, firstEvidenceMs = now - 5 * DAY_MS, lastEvidenceMs = lastEvidence, lastSampleMs = lastEvidence,
        maturity = ProfileMaturity.MATURE, abstainReason = null, updatedAtMs = lastEvidence
    )

    @Test fun `stored MATURE references that went stale or belong to another phone are not usable`() {
        val profiles = listOf(
            profile("1"),
            profile("2", lastEvidence = now - 40 * DAY_MS),                            // guardada como MATURE, ya caducada
            profile("3", dev = "samsung/sm-s911b"),                                   // copia de otro modelo
            profile("4").copy(algorithmVersion = LocalProfile.ALGORITHM_VERSION - 1)
        )
        assertEquals(LocalProfileSummary.Counts(usable = 1, total = 4), LocalProfileSummary.counts(profiles, device, now))
    }

    @Test fun `an evaluation made before the last reset is marked as from the previous profile`() {
        assertTrue(LocalProfileSummary.fromPreviousProfile(now - 1, lastResetMs = now))
        assertFalse(LocalProfileSummary.fromPreviousProfile(now + 1, lastResetMs = now))
        assertFalse(LocalProfileSummary.fromPreviousProfile(now, lastResetMs = null))
    }

    @Test fun `the settings card refreshes, shows when and where, and counts with the current state`() {
        val app = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
        val section = File(app, "java/com/alexisgordr/icdetector/ui/LocalProfileSection.kt").readText()
        assertTrue(section.contains("while (enabled && isActive)"))
        assertTrue(section.contains("delay(LocalProfileSummary.REFRESH_MS)"))
        assertTrue(section.contains("LocalProfileSummary.counts(LocalProfileStore.allProfiles(db), deviceModel"))
        assertTrue(section.contains("R.string.local_profile_last_when"))
        assertTrue(section.contains("LocalProfileSummary.fromPreviousProfile(r.evaluatedAtMs, lastResetMs)"))
        assertFalse(File(app, "java/com/alexisgordr/icdetector/storage/LocalProfileStore.kt").readText().contains("maturityCounts"))
    }
}
