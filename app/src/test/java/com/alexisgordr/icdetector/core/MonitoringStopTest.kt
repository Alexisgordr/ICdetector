package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.backup.BackupSettings
import com.alexisgordr.icdetector.ui.localizeTerminalLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.1.0 (#42) — Un fallo de startForeground() ya no para el servicio en silencio: notificación
 * normal, evento de collection-health y estado «detenido» en la pantalla principal. Y el arranque
 * tras reiniciar solo ocurre con el ajuste activado.
 */
class MonitoringStopTest {
    private class FakeSink : MonitoringStop.Sink {
        val recorded = mutableListOf<MonitoringStop.Stopped>()
        var notified = 0
        var cleared = 0
        override fun record(stopped: MonitoringStop.Stopped) { recorded += stopped }
        override fun notifyStopped() { notified++ }
        override fun cleared() { cleared++ }
    }

    /** Excepción con el nombre de la de Android 12+ (no existe en la JVM). */
    private class ForegroundServiceStartNotAllowedException(message: String) : IllegalStateException(message)

    private val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun src(path: String) = main.resolve("java/com/alexisgordr/icdetector/$path").readText()

    @After fun clearState() { MonitoringStop.startForeground(FakeSink(), 0L) {} }

    @Test fun `an injected startForeground failure notifies, records the event and shows the stopped state`() {
        val sink = FakeSink()
        val started = MonitoringStop.startForeground(sink, 1_790_000_000_000L) {
            throw ForegroundServiceStartNotAllowedException("startForegroundService() not allowed")
        }
        assertFalse(started)
        val expected = MonitoringStop.Stopped(1_790_000_000_000L, "ForegroundServiceStartNotAllowedException")
        assertEquals("evento con la clase de la excepción", listOf(expected), sink.recorded)
        assertEquals("notificación normal", 1, sink.notified)
        assertEquals("estado en pantalla", expected, MonitoringStop.state.value)
        assertEquals(0, sink.cleared)
    }

    @Test fun `a SecurityException is reported the same way`() {
        val sink = FakeSink()
        assertFalse(MonitoringStop.startForeground(sink, 5L) { throw SecurityException("no location") })
        assertEquals("SecurityException", sink.recorded.single().errorClass)
        assertEquals("SecurityException", MonitoringStop.state.value?.errorClass)
    }

    @Test fun `a successful start clears the event, the notification and the screen state`() {
        MonitoringStop.fail(FakeSink(), 1L, IllegalStateException())
        val sink = FakeSink()
        var calls = 0
        assertTrue(MonitoringStop.startForeground(sink, 2L) { calls++ })
        assertEquals(1, calls)
        assertEquals(1, sink.cleared)
        assertNull(MonitoringStop.state.value)
    }

    @Test fun `a failure stored by an earlier process is shown when the app opens`() {
        assertNull(MonitoringStop.fromStored(0L, "X"))
        assertNull(MonitoringStop.fromStored(5L, null))
        MonitoringStop.restore(MonitoringStop.fromStored(5L, "SecurityException"))
        assertEquals(MonitoringStop.Stopped(5L, "SecurityException"), MonitoringStop.state.value)
        MonitoringStop.restore(null)
        assertEquals("nada guardado no borra un fallo de este proceso", 5L, MonitoringStop.state.value?.atMs)
    }

    @Test fun `the next start writes it in the terminal, translated`() {
        val line = MonitoringStop.terminalLine(MonitoringStop.Stopped(1L, "SecurityException"))
        assertEquals("⚠ Monitoring stopped: Android did not allow the foreground service (SecurityException). " +
            "Nothing was recorded until this start.", localizeTerminalLine(line))
    }

    @Test fun `the boot receiver starts nothing while the setting is off`() {
        val sink = FakeSink()
        var starts = 0
        fun boot(setting: Boolean, wasOn: Boolean = true, firstRun: Boolean = false, action: String = BootStart.ACTION_BOOT_COMPLETED) =
            BootStart.handle(action, setting, wasOn, firstRun, sink, 0L) { starts++ }
        assertFalse(boot(setting = false))
        assertFalse("monitoreo desactivado al apagar", boot(setting = true, wasOn = false))
        assertFalse("pregunta de la primera apertura pendiente", boot(setting = true, firstRun = true))
        assertFalse("otra acción", boot(setting = true, action = "android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertEquals(0, starts)
        assertTrue(boot(setting = true))
        assertEquals(1, starts)
        assertEquals(0, sink.notified)
        assertFalse("desactivado por defecto", BootStart.DEFAULT_ENABLED)
    }

    @Test fun `a boot start that Android refuses is reported like any other`() {
        val sink = FakeSink()
        assertTrue(BootStart.handle(BootStart.ACTION_BOOT_COMPLETED, true, true, false, sink, 9L) { throw SecurityException() })
        assertEquals(1, sink.notified)
        assertEquals(MonitoringStop.Stopped(9L, "SecurityException"), MonitoringStop.state.value)
    }

    @Test fun `the service, the activity and the screen are wired to it`() {
        val service = src("service/MiniICService.kt")
        val create = service.substringAfter("override fun onCreate()")
        assertTrue(create.contains("com.alexisgordr.icdetector.core.MonitoringStop.startForeground(\n            MonitoringStopSink(this), System.currentTimeMillis()\n        )"))
        assertTrue(create.contains("if (!foregroundStarted) {"))
        assertFalse("ya no hay un catch que pare en silencio", create.substringBefore("if (!foregroundStarted)")
            .contains("No se pudo iniciar el servicio en primer plano: \${e.message}"))
        assertTrue(service.contains("putBoolean(com.alexisgordr.icdetector.core.BootStart.PREF_MONITORING_ENABLED, true)"))
        assertTrue(service.substringAfter("intent?.action == ACTION_STOP").substringBefore("stopSelf()")
            .contains("BootStart.PREF_MONITORING_ENABLED, false"))
        val activity = src("MainActivity.kt")
        assertTrue(activity.contains("com.alexisgordr.icdetector.core.MonitoringStop.fail("))
        assertTrue(activity.contains("com.alexisgordr.icdetector.core.MonitoringStop.restore("))
        val screen = src("ui/MainScreen.kt")
        assertTrue(screen.contains("val monitoringStopped by com.alexisgordr.icdetector.core.MonitoringStop.state.collectAsStateWithLifecycle()"))
        assertTrue(screen.contains("monitoringStopped != null -> stringResource(R.string.monitoring_stopped_title)"))
        assertTrue(screen.contains("MonitoringStoppedNotice(monitoringStopped)"))
        val notifications = src("service/ServiceNotificationController.kt")
        assertTrue("notificación normal, no de primer plano", notifications.substringAfter("fun notifyMonitoringStopped(")
            .substringBefore("fun cancelMonitoringStopped(").let { it.contains("manager.notify(MONITORING_STOPPED_NOTIFICATION_ID") && !it.contains("startForeground") && !it.contains("setOngoing(true)") })
    }

    @Test fun `only RECEIVE_BOOT_COMPLETED is added and the setting is visible and off by default`() {
        val manifest = main.resolve("AndroidManifest.xml").readText()
        val permissions = Regex("""<uses-permission\s+android:name="android\.permission\.(\w+)"(?![^>]*tools:node="remove")""")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "READ_PHONE_STATE", "ACCESS_NETWORK_STATE",
            "FOREGROUND_SERVICE_LOCATION", "POST_NOTIFICATIONS", "WAKE_LOCK", "RECEIVE_BOOT_COMPLETED"), permissions - "FOREGROUND_SERVICE")
        assertTrue(manifest.contains("android:name=\"com.alexisgordr.icdetector.service.BootReceiver\"\n            android:exported=\"false\""))
        assertTrue(manifest.contains("<action android:name=\"android.intent.action.BOOT_COMPLETED\" />"))
        val settings = src("ui/SettingsScreen.kt")
        assertTrue(settings.contains("prefs.getBoolean(BootStart.PREF_ENABLED, BootStart.DEFAULT_ENABLED)"))
        assertTrue(settings.contains("putBoolean(BootStart.PREF_ENABLED, startOnBoot)"))
        assertEquals(false, BackupSettings.DEFAULTS_FOR_MISSING[BootStart.PREF_ENABLED])
        listOf("values", "values-es").forEach { dir ->
            val strings = main.resolve("res/$dir/strings.xml").readText()
            listOf("monitoring_stopped_title", "monitoring_stopped_body", "monitoring_stopped_resume",
                "start_on_boot_title", "start_on_boot_description").forEach { assertTrue("$dir: $it", strings.contains("name=\"$it\"")) }
        }
        assertTrue(main.resolve("res/values-es/strings.xml").readText()
            .contains("<string name=\"monitoring_stopped_title\">Monitoreo detenido — abre la app para reanudar</string>"))
    }
}
