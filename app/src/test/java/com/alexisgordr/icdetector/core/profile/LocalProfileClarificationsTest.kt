package com.alexisgordr.icdetector.core.profile

import com.alexisgordr.icdetector.core.ThreatAnalyzer
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.CellRfFingerprint
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.SignalBaseline
import com.alexisgordr.icdetector.models.StoredReasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (L4) — Aclaraciones sin cambiar el método: textos de H13 compartidos con los literales
 * históricos exactos, días UTC y política real por modelo de teléfono.
 */
class LocalProfileClarificationsTest {
    private val cell = CellData(isRegistered = true, networkType = "4G LTE", cellId = "1000", mnc = "07", tac = "1",
        dbm = -60, mcc = "214", radioTech = RadioTech.LTE, rsrq = -3, sinr = 25)

    private fun observation(failed: String?) = HistoricalObservation(
        timestamp = null, observedAtMs = 1L, mcc = "214", mnc = "07", tac = "1", cid = "1000", radio = RadioTech.LTE,
        dbm = -60, rsrq = null, sinr = null, score = 80, failedHeuristics = failed, notEvaluatedHeuristics = "",
        locationMode = null, appVersion = null)

    @Test fun `the shared H13 texts are exactly the historical ones`() {
        assertEquals("Potencia anómala vs historial", StoredReasons.H13_POWER_PREFIX)
        assertEquals("Huella RF incoherente con el historial", StoredReasons.H13_RF_PREFIX)
        assertEquals("Huella RF incoherente con el historial (RSRQ/SINR muy desviados)", StoredReasons.H13_RF_REASON)
        assertEquals("Potencia anómala vs historial (+20dB)", StoredReasons.h13PowerReason(20))
        assertEquals(listOf(StoredReasons.H13_POWER_PREFIX, StoredReasons.H13_RF_PREFIX), H13History.FAILURE_MARKERS)
    }

    @Test fun `the real engine writes them and the H13 view recognises what it wrote`() {
        // Potencia: +25 dB sobre un historial plano.
        val power = ThreatAnalyzer.analyzeThreats(active = cell, neighbors = emptyList(), isHardwareCipheringActive = true,
            cellChangeHistory = emptyList(), currentLocation = null,
            signalBaseline = SignalBaseline(sampleCount = 10, meanDbm = -85.0, stdDevDbm = 3.0, minDbm = -90, maxDbm = -80))
        assertEquals(HeuristicStatus.FAILED, power.heuristicReport.signalBaseline)
        assertTrue(power.suspiciousReason.orEmpty(), power.suspiciousReason.orEmpty().contains("Potencia anómala vs historial (+25dB)"))
        assertEquals(H13History.FAILED, H13History.of(observation(power.suspiciousReason)))
        // Huella RF: RSRQ y SINR muy desviados, sin exceso de potencia.
        val rf = ThreatAnalyzer.analyzeThreats(active = cell.copy(dbm = -85), neighbors = emptyList(), isHardwareCipheringActive = true,
            cellChangeHistory = emptyList(), currentLocation = null,
            rfFingerprint = CellRfFingerprint(sampleCount = 30, rsrqMean = -15.0, rsrqStd = 1.0, sinrMean = 0.0, sinrStd = 1.0))
        assertEquals(HeuristicStatus.FAILED, rf.heuristicReport.signalBaseline)
        assertTrue(rf.suspiciousReason.orEmpty().contains("Huella RF incoherente con el historial (RSRQ/SINR muy desviados)"))
        assertEquals(H13History.FAILED, H13History.of(observation(rf.suspiciousReason)))
    }

    @Test fun `rows stored by earlier builds keep being recognised, untranslated`() {
        listOf("Potencia anómala vs historial (+18dB)", "[sub-umbral] Potencia anómala vs historial (+31dB)",
            "Huella RF incoherente con el historial (RSRQ/SINR muy desviados)",
            "OK | Huella RF incoherente con el historial (RSRQ/SINR muy desviados)")
            .forEach { assertEquals(it, H13History.FAILED, H13History.of(observation(it))) }
        assertEquals(H13History.NO_FAILURE_RECORDED, H13History.of(observation("Anomalous power vs history (+20dB)")))
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val analyzer = main.resolve("com/alexisgordr/icdetector/core/ThreatAnalyzer.kt").readText()
        assertTrue("sin literales sueltos en el motor", !analyzer.contains("\"Potencia anómala vs historial") && !analyzer.contains("\"Huella RF incoherente"))
    }

    @Test fun `profile days are UTC days`() {
        val beforeMidnightMadrid = java.time.ZonedDateTime.of(2026, 7, 10, 23, 30, 0, 0, java.time.ZoneId.of("Europe/Madrid")).toInstant().toEpochMilli()
        assertEquals("23:30 en Madrid (verano) son las 21:30 UTC del mismo día", "2026-07-10", LocalProfileLearner.dayOf(beforeMidnightMadrid))
        val afterMidnightLocal = java.time.ZonedDateTime.of(2026, 7, 11, 1, 30, 0, 0, java.time.ZoneId.of("Europe/Madrid")).toInstant().toEpochMilli()
        assertEquals("01:30 local del 11 es aún el 10 en UTC", "2026-07-10", LocalProfileLearner.dayOf(afterMidnightLocal))
        val manual = listOf(File("MANUAL.md"), File("../MANUAL.md")).first { it.exists() }.readText()
        assertTrue(manual.contains("Profile days are **UTC\n  days**"))
    }

    @Test fun `another phone model, the real policy - scorer abstains, learner replaces only after expiry`() {
        val learner = LocalProfileLearner()
        val ctx = ProfileContext("214", "07", "1", "1000", RadioTech.LTE)
        val day = LocalProfileLearner.DAY_MS
        val t0 = 1_760_000_000_000L
        var p: MetricProfile? = null
        repeat(4) { d -> repeat(10) { i ->
            val at = t0 + d * day + i * 120_000L
            p = learner.learn(p, ProfileSample(ctx, ProfileMetric.SIGNAL_DBM, -80, at, "other/phone", Provenance.LIVE), at).profile
        } }
        val foreign = p!!
        val last = foreign.lastEvidenceMs!!
        // Evaluador: siempre se abstiene con otro modelo, también vigente y madura.
        val eval = LocalProfileScorer.evaluate(mapOf(ProfileMetric.SIGNAL_DBM to foreign), ctx,
            mapOf(ProfileMetric.SIGNAL_DBM to -80, ProfileMetric.SIGNAL_RSRQ to null), "this/phone", last + day, learner)
        assertEquals(DeviationLevel.ABSTAIN, eval.level)
        assertEquals(AbstainReason.INCOMPATIBLE_DEVICE, eval.abstainReason)
        // Aprendizaje: vigente -> no mezcla.
        val fresh = learner.learn(foreign, ProfileSample(ctx, ProfileMetric.SIGNAL_DBM, -80, last + 10 * day, "this/phone", Provenance.LIVE), last + 10 * day)
        assertEquals(LearnOutcome.INCOMPATIBLE, fresh.outcome)
        assertEquals(foreign, fresh.profile)
        // Caducada (más de 30 días desde su última evidencia) -> generación nueva de este modelo.
        val at = last + 31 * day
        val renewed = learner.learn(foreign, ProfileSample(ctx, ProfileMetric.SIGNAL_DBM, -80, at, "this/phone", Provenance.LIVE), at)
        assertEquals(LearnOutcome.LEARNED, renewed.outcome)
        assertEquals("this/phone", renewed.profile!!.deviceModel)
        assertNotEquals(foreign.generation, renewed.profile!!.generation)
        val manual = listOf(File("MANUAL.md"), File("../MANUAL.md")).first { it.exists() }.readText()
        assertTrue(manual.contains("**Another phone model.**"))
        assertTrue(!manual.contains("stay inactive until you reset the profile"))
    }
}
