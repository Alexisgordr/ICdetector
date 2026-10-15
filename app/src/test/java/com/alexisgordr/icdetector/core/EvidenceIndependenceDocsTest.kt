package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.ui.localizeTerminalLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — Los comentarios y la documentación de ThreatEpisodeTracker y LocalCellTrustEngine describen
 * la implementación: familias distintas no son evidencia independiente, la promoción se mantiene
 * hasta 30 s sin fallos actuales y todo pasa por TemporalConfidence.
 */
class EvidenceIndependenceDocsTest {
    private val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }
    private fun doc(name: String) = listOf(File(name), File("../$name")).first { it.exists() }.readText()
    private fun src(path: String) = main.resolve("com/alexisgordr/icdetector/$path").readText()

    @Test fun `episode tracker comment matches the hold and the temporal confirmation`() {
        val tracker = src("core/ThreatEpisodeTracker.kt")
        val kdoc = tracker.substringBefore("class ThreatEpisodeTracker(")
        assertFalse("ya no dice que la observación actual deba tener evidencia", kdoc.contains("current observation must still contain evidence"))
        assertFalse(kdoc.contains("independent physical families"))
        listOf("STARTS or is RENEWED only when the current observation adds new evidence",
            "HELD for up to [promotionHoldMs] (30 s) even if the current\n *    observation has no failures",
            "still goes through [TemporalConfidence]", "not guaranteed to be independent evidence")
            .forEach { assertTrue(it, kdoc.contains(it)) }
        // Lo que el comentario afirma, en el código: 30 s de mantenimiento y renovación solo con evidencia nueva.
        assertTrue(tracker.contains("private val promotionHoldMs: Long = 30_000L"))
        assertTrue(tracker.contains("if (acceptedEvidence && gateReached) {"))
    }

    @Test fun `local trust comment says the second signal is not independent`() {
        val engine = src("core/LocalCellTrustEngine.kt")
        assertFalse(engine.contains("independentContradiction"))
        assertTrue(engine.contains("val alarmCandidate = eligible && rfChanged && positionOrHandoverContradiction"))
        listOf("two different signals,\n * not two independent ones", "[TemporalConfidence] before any alert")
            .forEach { assertTrue(it, engine.contains(it)) }
    }

    @Test fun `manual and terminal text no longer claim independence`() {
        val manual = doc("MANUAL.md")
        assertFalse(manual.contains("an alarm requires an RF contradiction plus independent geographic"))
        assertFalse(manual.contains("or two when two independent\n  high-value"))
        listOf("**Multi-signal episodes.**", "held for up to 30 s even if the current observation\nhas no failures",
            "Different families are not proof of independent evidence", "but that is a different signal, not an independent one")
            .forEach { assertTrue(it, manual.contains(it)) }
        assertTrue(res.resolve("values-es/strings.xml").readText().contains("Episodio multiseñal: familias distintas %1\$s."))
        assertTrue(res.resolve("values/strings.xml").readText().contains("Multi-signal episode: different families %1\$s."))
        assertEquals("[SEC] Multi-signal episode: different families RF_DOMINANCE, MOBILITY.",
            localizeTerminalLine("[SEC] Episodio multiseñal: familias distintas RF_DOMINANCE, MOBILITY."))
    }
}
