package com.alexisgordr.icdetector.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — La copia de seguridad está conectada a la app (lo que la JVM no puede ejecutar). */
class BackupWiringTest {

    private fun read(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
    private val main = "src/main/java/com/alexisgordr/icdetector"

    @Test fun `the pending restore runs first in the main process, never in the restart process`() {
        val manifest = read("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:name=\"com.alexisgordr.icdetector.ICDetectionApp\""))
        assertTrue(manifest.contains("android:name=\"com.alexisgordr.icdetector.backup.RestartActivity\"\n            android:process=\":restart\""))
        val app = read("$main/ICDetectionApp.kt")
        val onCreate = app.substringAfter("override fun onCreate()")
        assertTrue(onCreate.indexOf("RestartActivity.PROCESS_SUFFIX") < onCreate.indexOf("RestoreStartup.run(this)"))
    }

    @Test fun `nothing restarts monitoring while a restore is being prepared`() {
        val activity = read("$main/MainActivity.kt")
        assertTrue(activity.contains("if (isBound || isBinding || restoreInProgress) return"))
        val stop = activity.substringAfter("suspend fun stopMonitoringForRestore()").substringBefore("fun resumeMonitoringAfterRestoreCancelled")
        // Primero se terminan las escrituras pendientes; el servicio se para después.
        assertTrue(stop.indexOf("restoreInProgress = true") < stop.indexOf("quiesceForRestore()"))
        assertTrue(stop.indexOf("quiesceForRestore()") < stop.indexOf("stopService("))
        val section = read("$main/ui/BackupSection.kt")
        assertTrue(section.contains("val stopped = activity?.stopMonitoringForRestore() ?: true"))
        assertTrue(section.substringAfter("if (stopped) {").substringBefore("} else {").contains("preBackupLauncher.launch("))
    }

    @Test fun `the restore is confirmed only after the copy of the current data is saved`() {
        val controller = read("$main/backup/BackupController.kt").substringAfter("fun saveCurrentAndConfirm(")
        val export = controller.indexOf("exportTo(context, preBackupUri)")
        val failed = controller.indexOf("discardStaged(context)")
        val confirm = controller.indexOf("requestInstall()")
        assertTrue(export in 0 until failed && failed < confirm)
        val section = read("$main/ui/BackupSection.kt")
        // El reinicio solo se lanza si la copia previa salió bien.
        assertTrue(section.contains("if (result is BackupExporter.Result.Ok) {\n                RestartActivity.restart(context)"))
        assertEquals(1, Regex("RestartActivity.restart\\(").findAll(section).count())
    }

    @Test fun `every rejection has an explanation in both languages`() {
        val section = read("$main/ui/BackupSection.kt")
        val en = read("src/main/res/values/strings.xml")
        val es = read("src/main/res/values-es/strings.xml")
        BackupPolicy.Rejection.entries.forEach { assertTrue(it.name, section.contains("BackupPolicy.Rejection.${it.name} ->")) }
        Regex("R\\.string\\.(restore_[a-z_]+|backup_[a-z_]+)").findAll(section + read("$main/ui/RestoreResultDialog.kt"))
            .map { it.groupValues[1] }.toSet().forEach { key ->
                assertTrue("en $key", en.contains("<string name=\"$key\">"))
                assertTrue("es $key", es.contains("<string name=\"$key\">"))
            }
    }

    @Test fun `the export never includes the API key`() {
        assertEquals("opencellid_key", BackupSettings.NEVER_EXPORTED)
        assertTrue(BackupSettings.NEVER_EXPORTED !in BackupSettings.KEYS)
    }
}
