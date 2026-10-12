package com.alexisgordr.icdetector.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — Nota de aprendizaje con Wi-Fi y limpieza del criterio de reputación. */
class WifiLearningDocsTest {
    private fun doc(name: String) = listOf(File(name), File("../$name")).first { it.exists() }.readText()
    private val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }

    @Test fun `the manual explains learning from Wi-Fi rows without claiming a measured bias`() {
        val wifi = doc("MANUAL.md").substringAfter("### Wi-Fi and rule coverage").substringBefore("### What your own data")
        listOf(
            "the local references", "can learn from it",
            "\"No failure recorded\" therefore does not mean \"every rule was evaluated\"",
            "The Wi-Fi state is not stored per row and cannot be reconstructed",
            "**limitation still to be measured**, not a quantified bias", "not a\ndemonstrated change in detection",
            "No Wi-Fi column is added and these rows are not excluded"
        ).forEach { assertTrue(it, wifi.contains(it)) }
        assertTrue(doc("README.md").contains("the Wi-Fi state is not stored per row"))
    }

    @Test fun `reputation uses the trusted-baseline score constant and comments sit on their functions`() {
        val db = main.resolve("com/alexisgordr/icdetector/storage/CellDbHelper.kt").readText()
        val reputation = db.substringAfter("fun getCellReputation(").substringBefore("\n    fun ")
        assertTrue(reputation.contains("score >= TRUSTED_BASELINE_MIN_SCORE && StoredReasons.isClean(reason)"))
        assertFalse(Regex("""score >= 85\b""").containsMatchIn(reputation))
        val service = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        assertFalse("el KDoc ya no está sobre el companion object", service.contains("Escribe en el terminal el resultado de una celda"))
        val audit = main.resolve("com/alexisgordr/icdetector/service/AuditLogController.kt").readText()
        assertTrue(audit.substringBefore("fun logVerificationOutcome(").trimEnd().endsWith("*/"))
    }
}
