package com.alexisgordr.icdetector.backup

import com.alexisgordr.icdetector.backup.FirstRunPolicy.Decision
import com.alexisgordr.icdetector.backup.RestoreEngine.RestoreOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — Pregunta de la primera apertura. */
class FirstRunPolicyTest {

    @Test fun `a fresh install asks, and stores that it is pending`() {
        assertEquals(Decision.PENDING to true, FirstRunPolicy.resolveAtStart(null, emptySet()))
        // Las claves que escribe la propia restauración no cuentan como uso previo.
        assertEquals(Decision.PENDING to true, FirstRunPolicy.resolveAtStart(null, FirstRunPolicy.OWN_KEYS))
    }

    @Test fun `an install that already had its own preferences is not asked`() {
        assertEquals(Decision.EXISTING to true, FirstRunPolicy.resolveAtStart(null, setOf("location_mode")))
        assertEquals(Decision.EXISTING to true, FirstRunPolicy.resolveAtStart(null, setOf("last_accepted_loc")))
    }

    @Test fun `a stored decision is kept as it is`() {
        Decision.entries.forEach {
            assertEquals(it to false, FirstRunPolicy.resolveAtStart(it.name, setOf("location_mode")))
        }
        // Un valor desconocido se vuelve a resolver.
        assertEquals(Decision.PENDING to true, FirstRunPolicy.resolveAtStart("WHATEVER", emptySet()))
    }

    @Test fun `nothing is collected while pending or while a restore is unfinished`() {
        assertFalse(FirstRunPolicy.allowsCollection(Decision.PENDING, restorePending = false))
        assertFalse(FirstRunPolicy.allowsCollection(null, restorePending = false))
        listOf(Decision.RESTORED, Decision.FRESH, Decision.EXISTING).forEach {
            assertTrue(FirstRunPolicy.allowsCollection(it, restorePending = false))
            assertFalse(FirstRunPolicy.allowsCollection(it, restorePending = true))
        }
    }

    @Test fun `the question is resolved by a restore only when it finished well`() {
        assertEquals(Decision.RESTORED, FirstRunPolicy.afterRestore(Decision.PENDING, RestoreOutcome.RESTORED))
        listOf(RestoreOutcome.ROLLED_BACK, RestoreOutcome.PENDING, RestoreOutcome.DISCARDED, RestoreOutcome.NOTHING).forEach {
            assertNull(it.name, FirstRunPolicy.afterRestore(Decision.PENDING, it))
        }
        // Una restauración desde Ajustes no cambia una decisión ya tomada.
        assertNull(FirstRunPolicy.afterRestore(Decision.EXISTING, RestoreOutcome.RESTORED))
    }

    @Test fun `start from scratch counts as finished only when nothing is left`() {
        assertTrue(FirstRunPolicy.freshCleanupComplete(emptyList(), emptyList(), setOf(FirstRunPolicy.PREF_DECISION)))
        assertTrue(FirstRunPolicy.freshCleanupComplete(emptyList(), emptyList(), emptySet()))
        assertFalse(FirstRunPolicy.freshCleanupComplete(listOf("icdetector_history.db-wal"), emptyList(), emptySet()))
        assertFalse(FirstRunPolicy.freshCleanupComplete(emptyList(), listOf("leftover"), emptySet()))
        assertFalse(FirstRunPolicy.freshCleanupComplete(emptyList(), emptyList(), setOf("location_mode")))
    }

    private fun read(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
    private val main = "src/main/java/com/alexisgordr/icdetector"

    @Test fun `the gate runs before anything else in the service`() {
        val service = read("$main/service/MiniICService.kt")
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("override fun onStartCommand")
        val gate = onCreate.indexOf("FirstRun.allowsCollection(this)")
        assertTrue(gate in 0 until onCreate.indexOf("isServiceRunning = true"))
        assertTrue(gate < onCreate.indexOf("CellDbHelper.getInstance(this)"))
        assertTrue(service.substringAfter("override fun onStartCommand").substringBefore("ACTION_STOP")
            .contains("if (collectionBlocked) {"))
        assertTrue(service.substringAfter("override fun onDestroy() {").substringBefore("isServiceRunning = false")
            .contains("if (collectionBlocked) return"))
    }

    @Test fun `the screen never starts monitoring while pending, and cancelling stores nothing`() {
        val activity = read("$main/MainActivity.kt")
        assertTrue(activity.contains("if (com.alexisgordr.icdetector.backup.FirstRun.isPending(this)) return"))
        val screen = read("$main/ui/FirstRunScreen.kt")
        assertFalse(screen.contains("PREF_DECISION"))
        assertTrue(screen.contains("if (uri == null) return@rememberLauncherForActivityResult"))
        val firstRun = read("$main/backup/FirstRun.kt").substringAfter("fun startFresh(")
        // La decisión FRESH se escribe solo después de comprobar que el borrado terminó.
        assertTrue(firstRun.indexOf("deleteDatabase") < firstRun.indexOf("freshCleanupComplete("))
        assertTrue(firstRun.indexOf("if (!complete) return false") < firstRun.indexOf("Decision.FRESH.name"))
        assertTrue(firstRun.contains("val prefsCleared = editor.commit()"))
        assertTrue(screen.contains("if (done) onStartedFresh() else message = context.getString(R.string.first_run_fresh_failed)"))
        val app = read("$main/ICDetectionApp.kt")
        assertTrue(app.indexOf("RestoreStartup.run(this)") < app.indexOf("FirstRun.resolveAtStart(this, restore.outcome)"))
    }
}
