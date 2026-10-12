package com.alexisgordr.icdetector.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 (F10) — Los fallos del perfil local siguen aislados, pero quedan registrados y visibles. */
class LocalProfileHealthTest {
    private val app = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun source(path: String) = File(app, "java/com/alexisgordr/icdetector/$path").readText()

    @Test fun `a failure is described briefly and logging is rate limited`() {
        assertEquals("evaluation: IllegalStateException: profile evaluation not written",
            LocalProfileHealth.describe("evaluation", IllegalStateException("profile evaluation not written")))
        assertEquals(120 + "bootstrap: RuntimeException: ".length, LocalProfileHealth.describe("bootstrap", RuntimeException("x".repeat(500))).length)
        assertTrue(LocalProfileHealth.shouldLog(null, 0))
        assertFalse(LocalProfileHealth.shouldLog(0, LocalProfileHealth.LOG_INTERVAL_MS - 1))
        assertTrue(LocalProfileHealth.shouldLog(0, LocalProfileHealth.LOG_INTERVAL_MS))
    }

    @Test fun `hook, bootstrap and writes report failures instead of dropping them`() {
        assertTrue(source("service/ObservationPersistenceController.kt")
            .contains("runCatching { afterWrite(row, rowId) }.onFailure { e -> runCatching { onAfterWriteError(e) } }"))
        val service = source("service/MiniICService.kt")
        assertTrue(service.contains(".onFailure { e -> runCatching { localProfile.recordFailure(\"bootstrap\", e) } }"))
        assertTrue(service.contains("onAfterWriteError = { e -> localProfile.recordFailure(\"evaluation\", e) }"))
        assertTrue(source("storage/LocalProfileStore.kt").contains("check(db.insertWithOnConflict(P, null, v, SQLiteDatabase.CONFLICT_REPLACE) != -1L)"))
        assertTrue(source("service/LocalProfileController.kt").contains("check(LocalProfileStore.insertEvaluation(db, step.record) != -1L)"))
    }

    @Test fun `settings show profile errors as diagnosis, never as an alert`() {
        val section = source("ui/LocalProfileSection.kt")
        assertTrue(section.contains("R.string.local_profile_errors"))
        assertTrue(section.contains("R.string.local_profile_log_failed"))
        assertFalse(source("service/SecurityAlertController.kt").contains("LocalProfileHealth"))
        assertFalse(source("service/LocalProfileController.kt").contains("notification"))
    }
}
