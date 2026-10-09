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

    @Test fun `every scorer key has a weight and every weight belongs to a rule`() {
        val keys = HeuristicCatalog.values().map { it.scorerKey }.toSet()
        assertEquals(keys, BayesianScorer.weightedKeys)
        assertTrue(keys.containsAll(BayesianScorer.groupedKeys))
    }

    @Test fun `the analyzer emits only catalogued keys`() {
        val analyzer = listOf(
            File("src/main/java/com/alexisgordr/icdetector/core/ThreatAnalyzer.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/core/ThreatAnalyzer.kt")
        ).first { it.exists() }.readText()
        val emitted = Regex("""\) add\("([A-Za-z0-9]+)"\)""").findAll(analyzer).map { it.groupValues[1] }.toSet()
        val keys = HeuristicCatalog.values().map { it.scorerKey }.toSet()
        assertTrue("sin catalogar: ${emitted - keys}", keys.containsAll(emitted))
        assertTrue("latency la aporta el monitor de latencia", (keys - emitted) == setOf("latency"))
    }

    @Test fun `every episode family has at least one rule`() {
        assertEquals(ThreatEpisodeTracker.Family.values().toSet(), HeuristicCatalog.values().map { it.episodeFamily }.toSet())
    }
}
