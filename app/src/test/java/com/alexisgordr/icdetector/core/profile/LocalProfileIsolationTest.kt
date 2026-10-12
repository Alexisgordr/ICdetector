package com.alexisgordr.icdetector.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — Modo sombra: el perfil local no puede cambiar scores, rachas, alertas, el aprendizaje actual
 * ni decisiones forenses. Se comprueba en el código: el motor no lo conoce, solo escribe en sus
 * tablas y se llama después de guardar la fila, con sus fallos absorbidos.
 */
class LocalProfileIsolationTest {
    private val root = listOf(File("src/main/java/com/alexisgordr/icdetector"), File("app/src/main/java/com/alexisgordr/icdetector")).first { it.exists() }
    private fun source(path: String) = File(root, path).readText()

    @Test fun `the engine, alerts, learning and forensics never reference the local profile`() {
        val engine = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter {
            val rel = it.relativeTo(root).path.replace('\\', '/')
            (rel.startsWith("core/") && !rel.startsWith("core/profile/")) || rel.startsWith("forensics/") ||
                rel == "service/SecurityAlertController.kt" || rel == "service/ExternalVerificationController.kt"
        }.toList()
        assertTrue(engine.size > 20)
        engine.forEach { f ->
            val text = f.readText()
            listOf("core.profile", "LocalProfile", "ProfileEvaluation", "DeviationLevel").forEach {
                assertFalse("${f.name} usa $it", text.contains(it))
            }
        }
    }

    @Test fun `the shadow scorer never receives the engine verdict`() {
        val scorer = source("core/profile/LocalProfileScorer.kt") + source("core/profile/LocalProfileLearner.kt")
        listOf("securityScore", "suspiciousReason", "heuristicReport", "anomalyConfidence", "failedHeuristics").forEach {
            assertFalse("el perfil no usa $it como etiqueta", scorer.contains(it))
        }
    }

    @Test fun `the store only writes its own tables`() {
        val store = source("storage/LocalProfileStore.kt")
        val writes = Regex("""db\.(insert\w*|update|delete|execSQL)\((\w+)""").findAll(store).map { it.groupValues[2] }.toSet()
        assertEquals(setOf("P", "E", "M"), writes)
        assertFalse(Regex("""(?i)(update|delete from|insert into)\s+history""").containsMatchIn(store))
    }

    @Test fun `the hook runs after the row is saved and its failures are absorbed`() {
        val persistence = source("service/ObservationPersistenceController.kt")
        assertEquals(2, Regex("""onWrite\((rowId|it)\)\s*\n\s*shadow\(pending\.row, rowId\)""").findAll(persistence).count())
        assertTrue(persistence.contains("if (rowId > 0) runCatching { afterWrite(row, rowId) }"))
        val service = source("service/MiniICService.kt")
        assertTrue(service.contains("afterWrite = localProfile::onRowWritten"))
        assertTrue(service.contains("runCatching { localProfile.bootstrapIfNeeded() }"))
        // El controlador no devuelve nada al servicio: no hay ningún valor del que depender.
        val controller = source("service/LocalProfileController.kt")
        assertTrue(controller.contains("fun onRowWritten(row: ObservationRow, historyId: Long) {"))
    }

    @Test fun `a restored history never bootstraps the profile`() {
        assertTrue(source("backup/RestoreStartup.kt").contains("putBoolean(FirstRunPolicy.PREF_HISTORY_RESTORED, true)"))
        val controller = source("service/LocalProfileController.kt")
        assertTrue(controller.contains("val restored = prefs.getBoolean(PREF_HISTORY_RESTORED, false)"))
        assertTrue(controller.contains("if (!restored) {"))
    }
}
