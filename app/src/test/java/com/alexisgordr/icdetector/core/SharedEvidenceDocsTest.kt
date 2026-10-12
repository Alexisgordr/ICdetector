package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.ThreatEpisodeTracker.Family
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — Los solapamientos documentados en el manual describen el motor tal como está. Si cambia
 * una de estas piezas, este test obliga a revisar la sección «Shared evidence» (no cambia nada).
 */
class SharedEvidenceDocsTest {
    private fun doc(name: String) = listOf(File(name), File("../$name")).first { it.exists() }.readText()
    private fun src(path: String) = listOf(File("src/main/java/com/alexisgordr/icdetector/$path"),
        File("app/src/main/java/com/alexisgordr/icdetector/$path")).first { it.exists() }.readText()

    @Test fun `H11 and H16 share the mobility family but both shorten confirmation`() {
        assertEquals(Family.MOBILITY, HeuristicCatalog.MOBILE_CELL_ID.episodeFamily)
        assertEquals(Family.MOBILITY, HeuristicCatalog.TRANSITION_COHERENCE.episodeFamily)
        val strong = src("core/TemporalConfidence.kt").substringAfter("val strongFailures = listOf(").substringBefore(")")
        listOf("report.mobileCellId", "report.signalBaseline", "report.rfStability", "report.transitionCoherence").forEach {
            assertTrue(it, strong.contains(it))
        }
        val manual = doc("MANUAL.md")
        assertTrue(manual.contains("### Shared evidence: when two results are not independent"))
        assertTrue(manual.contains("| H11 and H16 |") && manual.contains("**Possible double counting.**"))
    }

    @Test fun `PCI feeds H11 severity, H15 and local confidence, which still needs H11 or H16`() {
        val analyzer = src("core/ThreatAnalyzer.kt")
        assertTrue(analyzer.contains("val pciMismatch = record.pci != null && active.pci != null && record.pci != active.pci"))
        val trust = src("core/LocalCellTrustEngine.kt")
        assertTrue(trust.contains("contradictions += \"PCI\""))
        assertTrue(trust.contains("val alarmCandidate = eligible && rfChanged && independentContradiction"))
        assertTrue(doc("MANUAL.md").contains("| H11, H15 and local cell confidence |"))
    }

    @Test fun `H13 fingerprint only counts when the power check did not fail`() {
        assertTrue(src("core/ThreatAnalyzer.kt").contains("if (!isWifiActive && hSignalBaseline) {\n            rfFingerprint?.let"))
        assertTrue(doc("MANUAL.md").contains("| H13 and the local profile |"))
    }

    @Test fun `docs no longer call the two-phase shortcut independent, and state the Stable-Site limits`() {
        val readme = doc("README.md")
        val manual = doc("MANUAL.md")
        assertFalse(readme.contains("Two independent high-value failures"))
        assertFalse(manual.contains("If two or more independent\nhigh-value checks"))
        assertTrue(readme.contains("*recorded in shadow* is a cumulative mark of the episode"))
        assertTrue(manual.contains("belong only to its **last saved evaluation**"))
        assertTrue(manual.contains("routes are aggregates") || manual.contains("Topology routes are aggregates"))
    }
}
