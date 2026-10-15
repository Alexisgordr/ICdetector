package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HeuristicStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 (#7, O2) — Las tres maneras de nombrar una regla coinciden con la lista canónica. */
class HeuristicCatalogTest {

    @Test fun `ids follow H1 to H16 in the report order`() {
        assertEquals((1..16).map { "H$it" }, HeuristicCatalog.values().map { it.id })
        val snapshotIds = HeuristicReport().snapshot().split(";").map { it.substringBefore("=") }
        assertEquals(snapshotIds, HeuristicCatalog.values().map { it.id })
    }

    @Test fun `each rule reads its own status from the report`() {
        HeuristicCatalog.values().forEachIndexed { index, rule ->
            val statuses = MutableList(16) { HeuristicStatus.PASSED }
            statuses[index] = HeuristicStatus.FAILED
            val report = HeuristicReport(
                statuses[0], statuses[1], statuses[2], statuses[3], statuses[4], statuses[5], statuses[6],
                statuses[7], statuses[8], statuses[9], statuses[10], statuses[11], statuses[12],
                statuses[13], statuses[14], statuses[15]
            )
            assertEquals(rule.id, HeuristicStatus.FAILED, rule.status(report))
        }
    }

    @Test fun `every evidence key has a weight and every weight belongs to a rule`() {
        val keys = HeuristicCatalog.values().filter { it.isEvidence }.map { it.scorerKey }.toSet()
        assertEquals(keys, BayesianScorer.weightedKeys)
        assertTrue(keys.containsAll(BayesianScorer.groupedKeys))
    }

    @Test fun `H8 is a data-quality flag and H9 is reserved, neither is evidence`() {
        val h8 = HeuristicCatalog.ARFCN_SANITY
        assertEquals(false, h8.isEvidence)
        assertEquals(ThreatEpisodeTracker.Family.DATA_QUALITY, h8.episodeFamily)
        assertEquals(BayesianScorer.Group.NOT_SCORED, BayesianScorer.groupOf(h8.scorerKey))
        assertTrue("sin LR", h8.scorerKey !in BayesianScorer.weightedKeys)
        val h9 = HeuristicCatalog.HARDWARE_CIPHERING
        assertEquals("H9", h9.id)
        assertEquals("H9 conserva su posición", 8, h9.ordinal)
        assertEquals(false, h9.isEvidence)
        assertEquals(ThreatEpisodeTracker.Family.UNAVAILABLE, h9.episodeFamily)
        assertEquals(BayesianScorer.Group.NOT_SCORED, BayesianScorer.groupOf(h9.scorerKey))
        assertTrue("sin LR", h9.scorerKey !in BayesianScorer.weightedKeys)
        assertEquals(listOf("H8", "H9"), HeuristicCatalog.entries.filter { !it.isEvidence }.map { it.id })
        // El recuento de fallos del informe usa la misma lista.
        assertEquals(HeuristicCatalog.entries.filter { !it.isEvidence }.map { it.id }.toSet(), HeuristicReport.NON_EVIDENCE_IDS)
    }

    @Test fun `the analyzer emits only catalogued keys`() {
        val analyzer = listOf(
            File("src/main/java/com/alexisgordr/icdetector/core/ThreatAnalyzer.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/core/ThreatAnalyzer.kt")
        ).first { it.exists() }.readText()
        val emitted = Regex("""\) add\("([A-Za-z0-9]+)"\)""").findAll(analyzer).map { it.groupValues[1] }.toSet()
        val keys = HeuristicCatalog.values().filter { it.isEvidence }.map { it.scorerKey }.toSet()
        assertTrue("sin catalogar: ${emitted - keys}", keys.containsAll(emitted))
        assertTrue("latency la aporta el monitor de latencia", (keys - emitted) == setOf("latency"))
        assertTrue("H8 no se entrega al bayesiano", "arfcn" !in emitted)
        assertTrue("H9 no se entrega al bayesiano", "ciphering" !in emitted)
    }

    @Test fun `every episode family has at least one rule`() {
        assertEquals(ThreatEpisodeTracker.Family.values().toSet(), HeuristicCatalog.values().map { it.episodeFamily }.toSet())
    }

    // ── 3.0 (fase 4) — Familia de episodio frente a grupo del scorer ────────────────────────

    @Test fun `the declared scorer group is the real one`() {
        HeuristicCatalog.entries.forEach { assertEquals(it.id, BayesianScorer.groupOf(it.scorerKey), it.scorerGroup) }
    }

    /** Correspondencia esperada familia → grupos del scorer. */
    private fun corresponds(f: ThreatEpisodeTracker.Family, g: BayesianScorer.Group) = when (f) {
        ThreatEpisodeTracker.Family.RF_DOMINANCE -> g == BayesianScorer.Group.RF_DOMINANCE
        ThreatEpisodeTracker.Family.MOBILITY -> g == BayesianScorer.Group.MOBILITY
        ThreatEpisodeTracker.Family.IDENTITY -> g == BayesianScorer.Group.WEAK_IDENTITY || g == BayesianScorer.Group.INDEPENDENT
        ThreatEpisodeTracker.Family.CROSS_LAYER -> g == BayesianScorer.Group.INDEPENDENT
        ThreatEpisodeTracker.Family.DATA_QUALITY -> g == BayesianScorer.Group.NOT_SCORED
        ThreatEpisodeTracker.Family.UNAVAILABLE -> g == BayesianScorer.Group.NOT_SCORED
    }

    @Test fun `family and scorer group differ only in the three documented rules`() {
        val differing = HeuristicCatalog.entries.filter { !corresponds(it.episodeFamily, it.scorerGroup) }
        assertEquals(listOf("H6", "H14", "H15"), differing.map { it.id })
        differing.forEach { assertTrue("${it.id} debe explicar por qué", !it.familyDiffersFromScorer.isNullOrBlank()) }
        HeuristicCatalog.entries.filter { corresponds(it.episodeFamily, it.scorerGroup) }
            .forEach { assertEquals("${it.id} no tiene excepción que explicar", null, it.familyDiffersFromScorer) }
    }

    @Test fun `H6 shares the RF dominance group with H2 and H7`() {
        listOf("taDistance", "powerJump", "ghostCells").forEach { assertEquals(BayesianScorer.Group.RF_DOMINANCE, BayesianScorer.groupOf(it)) }
    }
}
