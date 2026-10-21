package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 (UI3) — Un solo umbral de puntuación sospechosa: 70, con `score < 70`. */
class ScoreThresholdTest {
    private fun src(p: String) = listOf("app/src/main/java/com/alexisgordr/icdetector/$p", "src/main/java/com/alexisgordr/icdetector/$p")
        .map(::File).first { it.exists() }.readText()

    @Test fun `value and comparison are unchanged`() {
        assertEquals(70, ScoreThreshold.SUSPICIOUS_BELOW)
        assertTrue(ScoreThreshold.isBelow(69)); assertTrue(ScoreThreshold.isBelow(0))
        assertFalse(ScoreThreshold.isBelow(70)); assertFalse(ScoreThreshold.isBelow(100))
    }

    @Test fun `engine and screens share it, and other 70s keep their own meaning`() {
        val engine = src("core/ThreatAnalyzer.kt")
        assertTrue(engine.contains("val isSuspicious = ScoreThreshold.isBelow(finalScore)"))
        assertFalse(engine.contains("finalScore < 70"))
        assertTrue(src("ui/MainScreen.kt").contains("private const val SCORE_WATCH = com.alexisgordr.icdetector.core.ScoreThreshold.SUSPICIOUS_BELOW"))
        assertFalse(src("ui/HistoryScreen.kt").contains("score < 70"))
        assertTrue(src("ui/Visualizers.kt").contains("val umbral = -70"))
        assertTrue(src("core/BandPlan.kt").contains("LteBand(70,"))
    }

    @Test fun `H3 and H15 alone stay on the boundary and the manual says it is unvalidated`() {
        val analyzer = src("core/ThreatAnalyzer.kt")
        val h3 = analyzer.substringAfter("reasons.add(mccResult.failReason ?: MccConsistencyRule.REASON_PREFIX)").substringBefore("}")
        val h15 = analyzer.substringAfter("StoredReasons.H15_REASON)").substringBefore("}")
        assertTrue(h3.contains("score -= 30"))
        assertTrue(h15.contains("score -= 30"))
        assertFalse("100 - 30 queda en la frontera, no por debajo", ScoreThreshold.isBelow(100 - 30))
        val manual = listOf(File("MANUAL.md"), File("../MANUAL.md")).first { it.exists() }.readText()
        assertTrue(manual.contains("This is a design decision that has not been validated yet"))
        assertTrue(manual.contains("will be reviewed with field\ndata"))
    }
}
