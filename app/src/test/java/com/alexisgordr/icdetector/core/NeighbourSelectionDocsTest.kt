package com.alexisgordr.icdetector.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — El cambio de vecinas está documentado sin cifrar su efecto, y requiredCycles no promete independencia. */
class NeighbourSelectionDocsTest {
    private fun doc(name: String) = listOf(File(name), File("../$name")).first { it.exists() }.readText()
    private val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }

    @Test fun `neighbour selection change is documented with its commit and consumers`() {
        val changelog = doc("CHANGELOG.md").substringAfter("**Neighbour selection").substringBefore("\n- **")
        listOf(
            "commit `6e99267`", "H1 (isolation", "H2 (power", "H3–H5", "H7 (ghost", "H11 distance threshold",
            "H16", "Bayesian density factor", "Stable-Site\n  learning", "forensic black box",
            "**not been measured**", "rows are not rewritten or recomputed"
        ).forEach { assertTrue(it, changelog.contains(it)) }
        val manual = doc("MANUAL.md")
        assertTrue(manual.contains("**Which entries count as neighbours.**"))
        assertTrue(manual.contains("does not assume that secondaries come from the same site"))
        assertTrue(manual.contains("**Neighbour selection before 3.0 beta (commit `6e99267`):**"))
        assertTrue(doc("README.md").contains("A secondary carrier is never counted as a\nneighbour"))
    }

    @Test fun `required cycles no longer claims independent evidence`() {
        val temporal = main.resolve("com/alexisgordr/icdetector/core/TemporalConfidence.kt").readText()
        val kdoc = temporal.substringBefore("private fun requiredCycles(").substringAfterLast("/**")
        assertTrue(kdoc.contains("NO son necesariamente evidencias independientes"))
        assertTrue(kdoc.contains("H11 y H16 leen el mismo"))
        assertTrue("la lógica no cambia", temporal.contains("return if (cell.isSuspicious && strongFailures >= 2) 2 else confirmationCycles"))
    }
}
