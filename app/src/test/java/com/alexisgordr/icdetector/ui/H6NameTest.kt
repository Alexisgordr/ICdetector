package com.alexisgordr.icdetector.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — H6 solo evalúa proximidad: su nombre lo dice en todas partes y en ambos idiomas. */
class H6NameTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun names(dir: String) = Regex("""<string-array name="heuristic_names">(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
        .find(File(main, "res/$dir/strings.xml").readText())!!.groupValues[1]
        .let { Regex("<item>(.*?)</item>").findAll(it).map { m -> m.groupValues[1] }.toList() }

    @Test fun `H6 is called Timing Advance proximity in both languages`() {
        assertEquals("Proximidad por Timing Advance", names("values-es")[5])
        assertEquals("Timing Advance proximity", names("values")[5])
        assertEquals("Timing Advance proximity", localizeTerminalLine("Proximidad por Timing Advance"))
        val java = File(main, "java/com/alexisgordr/icdetector")
        assertTrue(File(java, "core/DiagnosticEngine.kt").readText().contains("HeuristicDiagnostic(6, \"Proximidad por Timing Advance\""))
        assertTrue(File(java, "service/AuditLogController.kt").readText().contains("\"6. Proximidad por Timing Advance\" to report.taDistance"))
        val all = java.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() } +
            File(main, "res/values/strings.xml").readText() + File(main, "res/values-es/strings.xml").readText()
        listOf("Coherencia geométrica (TA)", "Geometric coherence (TA)", "Geometría (TA)").forEach { assertFalse(it, all.contains(it)) }
    }
}
