package com.alexisgordr.icdetector.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — Opción experimental del perfil local: desactivada por defecto, solo visualización, con
 * registro de cada cambio y separada de las decisiones del motor.
 */
class LocalProfileOptionTest {
    private val app = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun source(path: String) = File(app, "java/com/alexisgordr/icdetector/$path").readText()
    private fun res(dir: String) = File(app, "res/$dir/strings.xml").readText()

    @Test fun `off by default and the contract never touches alerts`() {
        assertFalse(LocalProfileOption.DEFAULT_ENABLED)
        assertFalse(LocalProfileOption.MAY_ADD_ALERTS)
        assertFalse(LocalProfileOption.MAY_SILENCE_OR_DOWNGRADE_ALERTS)
        assertFalse(LocalProfileOption.MAY_SKIP_TEMPORAL_CONFIRMATION)
        assertFalse("sin validación, solo se muestra", LocalProfile.ALGORITHM_VALIDATED)
        assertEquals(ProfileMode.SHADOW, LocalProfileOption.mode(false))
        assertEquals(ProfileMode.EXPERIMENTAL_DISPLAY, LocalProfileOption.mode(true))
    }

    @Test fun `the option only changes the mode stored with each evaluation`() {
        val service = source("service/MiniICService.kt")
        assertTrue(service.contains("LocalProfileOption.mode(profilePrefs.getBoolean("))
        assertEquals("solo el perfil lee la opción", 1, Regex("LocalProfileOption\\.PREF_KEY").findAll(service).count())
        val alerts = source("service/SecurityAlertController.kt")
        assertFalse(alerts.contains("LocalProfile"))
    }

    @Test fun `every change and every reset is logged, and turning it off is always possible`() {
        val section = source("ui/LocalProfileSection.kt")
        assertTrue(section.contains("LocalProfileStore.recordModeEvent(db(), LocalProfileOption.mode(requested)"))
        assertTrue(section.contains("LocalProfileStore.recordEvent(db, LocalProfileOption.RESET_EVENT"))
        val switch = section.substringAfter("Switch(").substringBefore("colors =")
        assertFalse("el interruptor nunca se bloquea", Regex("""^\s*enabled\s*=(?!\s*requested)""", RegexOption.MULTILINE).containsMatchIn(switch))
        assertFalse(section.contains("context.getString"))
        assertTrue(source("ui/SettingsScreen.kt").contains("LocalProfileSection()"))
        assertTrue(source("service/LocalProfileController.kt").contains("LocalProfileStore.recordModeEvent(db, mode(), appVersion, now)"))
    }

    @Test fun `the display is labelled experimental, uncalibrated and not an engine decision`() {
        val section = source("ui/LocalProfileSection.kt")
        assertTrue(section.contains("R.string.local_profile_not_engine"))
        assertTrue(res("values").contains("EXPERIMENTAL — not an engine decision. Uncalibrated: the deviation is not a probability."))
        assertTrue(res("values-es").contains("EXPERIMENTAL — no es una decisión del motor. Sin calibrar: la desviación no es una probabilidad."))
        listOf("values", "values-es").forEach { assertFalse(res(it).contains("probabilidad de ataque\"")) }
    }

    @Test fun `restoring a backup leaves the option off and logs it when it was on`() {
        assertFalse("la opción no viaja en las copias", LocalProfileOption.PREF_KEY in com.alexisgordr.icdetector.backup.BackupSettings.KEYS)
        val restore = source("backup/RestoreStartup.kt")
        val restored = restore.substringAfter("if (restoreOutcome == RestoreEngine.RestoreOutcome.RESTORED) {").substringBefore("return result")
        assertTrue(restored.contains(".putBoolean(com.alexisgordr.icdetector.core.profile.LocalProfileOption.PREF_KEY, false)"))
        assertTrue(restored.contains("if (wasOn) editor.putBoolean(FirstRunPolicy.PREF_PROFILE_OFF_BY_RESTORE, true)"))
        assertTrue(restored.contains("editor.commit()"))
        assertTrue(com.alexisgordr.icdetector.backup.FirstRunPolicy.PREF_PROFILE_OFF_BY_RESTORE in com.alexisgordr.icdetector.backup.FirstRunPolicy.OWN_KEYS)
        val controller = source("service/LocalProfileController.kt")
        assertTrue(controller.contains("LocalProfileStore.recordModeEvent(dbHelper.writableDatabase, null, appVersion, clock())"))
        assertTrue(source("service/MiniICService.kt").contains("runCatching { localProfile.logSwitchOffByRestore() }"))
    }
}
