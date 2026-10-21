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

    @Test fun `after a reboot it only notifies, with the setting on, monitoring on and the first run answered`() {
        fun boot(setting: Boolean = true, wasOn: Boolean = true, firstRun: Boolean = false, action: String? = BootStart.ACTION_BOOT_COMPLETED) =
            BootStart.onBoot(action, setting, wasOn, firstRun)
        assertEquals(BootStart.Action.NOTIFY, boot())
        assertEquals("ajuste desactivado", BootStart.Action.NOTHING, boot(setting = false))
        assertEquals("monitoreo desactivado al apagar", BootStart.Action.NOTHING, boot(wasOn = false))
        assertEquals("primera apertura sin contestar", BootStart.Action.NOTHING, boot(firstRun = true))
        assertEquals("antes de desbloquear", BootStart.Action.NOTHING, boot(action = "android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertEquals(BootStart.Action.NOTHING, boot(action = null))
        assertFalse("desactivado por defecto", BootStart.DEFAULT_ENABLED)
        // Ninguna acción arranca el servicio: solo NOTIFY o NOTHING.
        assertEquals(setOf("NOTIFY", "NOTHING"), BootStart.Action.entries.map { it.name }.toSet())
    }

    @Test fun `tapping the notification resumes only with the first run answered`() {
        assertTrue(BootStart.resumeOnOpen(hasResumeExtra = true, firstRunPending = false))
        assertFalse(BootStart.resumeOnOpen(hasResumeExtra = true, firstRunPending = true))
        assertFalse("una apertura normal no es la notificación", BootStart.resumeOnOpen(hasResumeExtra = false, firstRunPending = false))
    }

    @Test fun `a start that Android refuses from the activity is still reported`() {
        val sink = FakeSink()
        MonitoringStop.fail(sink, 9L, SecurityException())
        assertEquals(1, sink.notified)
        assertEquals(MonitoringStop.Stopped(9L, "SecurityException"), MonitoringStop.state.value)
    }

    /**
     * Test de cableado: el receptor no arranca ningún servicio (lee el código; un BroadcastReceiver
     * no se puede ejecutar en la JVM).
     */
    @Test fun `the boot receiver never starts the service from the background`() {
        val receiver = src("service/BootReceiver.kt")
        assertFalse(receiver.contains("startForegroundService") || receiver.contains("startService"))
        assertTrue(receiver.contains("if (action == BootStart.Action.NOTIFY) ServiceNotificationController.notifyResumeAfterBoot(context)"))
    }

    /**
     * Test de cableado: lee el código del servicio, la actividad, MainScreen y
     * ServiceNotificationController. Son clases de Android y Compose que no se pueden ejecutar en la
     * JVM; el comportamiento (MonitoringStop, BootStart) se prueba arriba, y el aviso en pantalla en
     * androidTest (MonitoringStoppedNoticeTest).
     */
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
        // Tras reiniciar, el monitoreo se reanuda desde la actividad al tocar la notificación.
        val resume = activity.substringAfter("private fun resumeAfterBootIfAsked(").substringBefore("\n    }\n")
        assertTrue(resume.contains("BootStart.resumeOnOpen(asked, com.alexisgordr.icdetector.backup.FirstRun.isPending(this))"))
        assertTrue(resume.contains("cancelResumeAfterBoot(this)") && resume.contains("startAndBindService()"))
        assertTrue(activity.contains("if (savedInstanceState == null) resumeAfterBootIfAsked(intent)"))
        val screen = src("ui/MainScreen.kt")
        assertTrue(screen.contains("val monitoringStopped by com.alexisgordr.icdetector.core.MonitoringStop.state.collectAsStateWithLifecycle()"))
        assertTrue(screen.contains("monitoringStopped != null -> stringResource(R.string.monitoring_stopped_title)"))
        assertTrue(screen.contains("MonitoringStoppedNotice(monitoringStopped)"))
        val notifications = src("service/ServiceNotificationController.kt")
        assertTrue("notificación normal, no de primer plano", notifications.substringAfter("fun notifyMonitoringStopped(")
            .substringBefore("fun cancelMonitoringStopped(").let { it.contains("manager.notify(MONITORING_STOPPED_NOTIFICATION_ID") && !it.contains("startForeground") && !it.contains("setOngoing(true)") })
    }

    /**
     * El conjunto de permisos se comprueba sobre el manifiesto (es el propio artefacto). Lo de
     * SettingsScreen es un test de cableado: Compose no se ejecuta en la JVM. El valor por defecto
     * del ajuste se comprueba con BootStart y BackupSettings.
     */
    @Test fun `only RECEIVE_BOOT_COMPLETED is added and the setting is visible and off by default`() {
        val manifest = main.resolve("AndroidManifest.xml").readText()
        val permissions = Regex("""<uses-permission\s+android:name="android\.permission\.(\w+)"(?![^>]*tools:node="remove")""")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "READ_PHONE_STATE", "ACCESS_NETWORK_STATE",
            "FOREGROUND_SERVICE_LOCATION", "POST_NOTIFICATIONS", "WAKE_LOCK", "RECEIVE_BOOT_COMPLETED"), permissions - "FOREGROUND_SERVICE")
        assertFalse("sin ubicación en segundo plano", manifest.contains("ACCESS_BACKGROUND_LOCATION"))
        assertTrue(manifest.contains("android:name=\"com.alexisgordr.icdetector.service.BootReceiver\"\n            android:exported=\"false\""))
        assertTrue(manifest.contains("<action android:name=\"android.intent.action.BOOT_COMPLETED\" />"))
        val settings = src("ui/SettingsScreen.kt")
        assertTrue(settings.contains("prefs.getBoolean(BootStart.PREF_ENABLED, BootStart.DEFAULT_ENABLED)"))
        assertTrue(settings.contains("putBoolean(BootStart.PREF_ENABLED, startOnBoot)"))
        assertEquals(false, BackupSettings.DEFAULTS_FOR_MISSING[BootStart.PREF_ENABLED])
        listOf("values", "values-es").forEach { dir ->
            val strings = main.resolve("res/$dir/strings.xml").readText()
            listOf("monitoring_stopped_title", "monitoring_stopped_body", "monitoring_stopped_resume",
                "start_on_boot_title", "start_on_boot_description", "resume_after_boot_title", "resume_after_boot_body").forEach { assertTrue("$dir: $it", strings.contains("name=\"$it\"")) }
        }
        assertTrue(main.resolve("res/values-es/strings.xml").readText()
            .contains("<string name=\"monitoring_stopped_title\">Monitoreo detenido — abre la app para reanudar</string>"))
        assertTrue(main.resolve("res/values-es/strings.xml").readText()
            .contains("<string name=\"resume_after_boot_title\">El teléfono se ha reiniciado — toca para reanudar el monitoreo</string>"))
        assertTrue(main.resolve("res/values/strings.xml").readText()
            .contains("<string name=\"resume_after_boot_title\">The phone restarted — tap to resume monitoring</string>"))
        listOf("values", "values-es").forEach { dir ->
            val text = main.resolve("res/$dir/strings.xml").readText()
            assertFalse("$dir: sin «Permitir todo el tiempo»", text.contains("Allow all the time") || text.contains("Permitir todo el tiempo"))
        }
    }
}
