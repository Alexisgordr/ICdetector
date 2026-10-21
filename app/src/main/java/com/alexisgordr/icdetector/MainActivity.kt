/*
 * ICdetection - IMSI-Catcher Detection Tool
 * Copyright (C) 2026 Alexis Gómez Rodríguez
 */

package com.alexisgordr.icdetector

import android.os.Bundle
import android.os.IBinder
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alexisgordr.icdetector.service.MiniICService
import com.alexisgordr.icdetector.storage.CellDbHelper
import com.alexisgordr.icdetector.ui.MainLayout
import com.alexisgordr.icdetector.util.LocaleController

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<MiniICService?>(null)
    private var isBound = false
    private var isBinding = false
    private var isActivityVisible = false
    /** 3.0 — Pregunta de la primera apertura sin resolver: no se muestra la app ni se recoge nada. */
    private var firstRunPending by mutableStateOf(false)
    /** 3.0 — Mientras se prepara una restauración, nada vuelve a arrancar la monitorización. */
    private var restoreInProgress = false
    private lateinit var dbHelper: CellDbHelper

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as MiniICService.LocalBinder
            service = b.getService()
            isBound = true
            isBinding = false
            service?.setUiVisible(isActivityVisible)
            service?.forceRefresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            isBinding = false
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleController.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = CellDbHelper.getInstance(this)
        // 3.1.0 (#47) — Único punto de apertura de la base, antes de que nada la lea. Si es de una
        // versión más nueva, DatabaseGate lo publica y abajo se enseña el diálogo bloqueante.
        dbHelper.openThroughGate()
        firstRunPending = com.alexisgordr.icdetector.backup.FirstRun.isPending(this)
        // 3.1.0 (#42) — Un fallo de arranque del servicio (también de un proceso anterior, p. ej.
        // tras reiniciar) se enseña en la pantalla principal hasta que el servicio arranque bien.
        com.alexisgordr.icdetector.core.MonitoringStop.restore(
            com.alexisgordr.icdetector.service.CollectionHealthController.foregroundStartFailure(
                getSharedPreferences(com.alexisgordr.icdetector.service.MonitoringStopSink.PREFS_FILE, MODE_PRIVATE)
            )
        )
        // 3.0 — Un aviso silencioso de Stable-Site abre su episodio. Solo en la primera creación:
        // al girar la pantalla el destino ya está fijado (o cerrado) y no se vuelve a abrir.
        if (savedInstanceState == null) com.alexisgordr.icdetector.ui.StableSiteNoticeTarget.consume(intent)
        // 3.1.0 (#42) — Abierta desde la notificación «El teléfono se ha reiniciado»: se reanuda aquí,
        // en primer plano. Solo en la primera creación (al girar la pantalla ya se hizo).
        if (savedInstanceState == null) resumeAfterBootIfAsked(intent)
        
        // No iniciamos el servicio aquí directamente para evitar crasheos por permisos en Android 14+
        // El inicio del servicio se gestionará desde el MainLayout cuando los permisos sean otorgados.

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFE0E0E0),
                    background = Color(0xFF0A0A0A),
                    surface = Color(0xFF141414),
                    error = Color(0xFFCF6679)
                )
            ) {
                val databaseState by com.alexisgordr.icdetector.storage.DatabaseGate.state.collectAsState()
                val newer = databaseState as? com.alexisgordr.icdetector.storage.DatabaseGate.State.NewerSchema
                if (newer != null) {
                    // 3.1.0 (#47) — Bloqueante: ninguna pantalla que lea la base, ningún servicio.
                    com.alexisgordr.icdetector.ui.DatabaseNewerDialog(newer, onClose = { finish() })
                } else if (firstRunPending) {
                    com.alexisgordr.icdetector.ui.FirstRunScreen(onStartedFresh = { firstRunPending = false })
                } else {
                    MainLayout(context = this, dbHelper = dbHelper, service = service)
                }
                com.alexisgordr.icdetector.ui.RestoreResultDialog()
            }
        }
    }

    /** 3.0 — Con la app ya abierta, tocar un aviso de Stable-Site llega aquí (singleTop). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        com.alexisgordr.icdetector.ui.StableSiteNoticeTarget.consume(intent)
        resumeAfterBootIfAsked(intent)
    }

    /**
     * 3.1.0 (#42) — Notificación tras reiniciar (BootStart): con la primera apertura contestada,
     * quita la notificación y arranca el monitoreo como cualquier arranque desde la app.
     */
    private fun resumeAfterBootIfAsked(intent: Intent?) {
        val asked = intent?.getBooleanExtra(com.alexisgordr.icdetector.core.BootStart.EXTRA_RESUME_AFTER_BOOT, false) == true
        if (!com.alexisgordr.icdetector.core.BootStart.resumeOnOpen(asked, com.alexisgordr.icdetector.backup.FirstRun.isPending(this))) return
        intent?.removeExtra(com.alexisgordr.icdetector.core.BootStart.EXTRA_RESUME_AFTER_BOOT)
        com.alexisgordr.icdetector.service.ServiceNotificationController.cancelResumeAfterBoot(this)
        startAndBindService()
    }

    fun startAndBindService() {
        // Guard: evita un doble bindService() si esto se llama dos veces antes de que la conexión
        // se establezca (puede ocurrir entre el callback de permisos y el LaunchedEffect). Sin él,
        // dos binds quedarían con un solo unbind al cerrar (fuga de ciclo de vida).
        if (isBound || isBinding || restoreInProgress) return
        // 3.1.0 (#47) — Base de una versión más nueva: el diálogo está a la vista, nada arranca.
        if (com.alexisgordr.icdetector.storage.DatabaseGate.blocked) return
        if (com.alexisgordr.icdetector.backup.FirstRun.isPending(this)) return

        val intent = Intent(this, MiniICService::class.java)
        // startForegroundService() puede lanzar ForegroundServiceStartNotAllowedException (Android
        // 12+) si la app no tiene derecho a arrancar un servicio en primer plano en ese momento.
        // Ocurre aquí, en el llamador, antes de llegar al try/catch del propio servicio, así que se
        // protege también aquí para no crashear la app.
        try {
            startForegroundService(intent)
            // bindService() devuelve false si el servicio no se puede enlazar (sin lanzar). En ese
            // caso paramos el servicio que acabamos de arrancar para no dejarlo huérfano, y dejamos
            // isBinding en false para poder reintentar.
            isBinding = bindService(intent, connection, BIND_AUTO_CREATE)
            if (!isBinding) {
                stopService(intent)
                android.util.Log.e("MainActivity", "No se pudo enlazar el servicio.")
            }
        } catch (e: Exception) {
            isBinding = false
            stopService(intent)
            android.util.Log.e("MainActivity", "No se pudo iniciar/enlazar el servicio: ${e.message}", e)
            // 3.1.0 (#42) — No en silencio: notificación, evento y estado en la pantalla.
            com.alexisgordr.icdetector.core.MonitoringStop.fail(
                com.alexisgordr.icdetector.service.MonitoringStopSink(this), System.currentTimeMillis(), e
            )
        }
    }

    /**
     * 3.1.0 (#42) — Botón «Reanudar» del aviso de monitoreo detenido: suelta el servicio que no
     * llegó a arrancar en primer plano (si sigue enlazado) y lo vuelve a arrancar.
     */
    fun resumeMonitoring() {
        if (restoreInProgress) return
        if (isBound || isBinding) {
            try { unbindService(connection) } catch (_: IllegalArgumentException) {}
            isBound = false
            isBinding = false
        }
        service = null
        stopService(Intent(this, MiniICService::class.java))
        startAndBindService()
    }

    /**
     * 3.0 — Antes de guardar la copia de los datos actuales y restaurar: para la monitorización y
     * TERMINA las escrituras que ya estaban en cola o en marcha (parar el servicio a secas cancelaría
     * su scope y descartaría las no empezadas). False si no se pueden terminar: no se restaura.
     */
    suspend fun stopMonitoringForRestore(): Boolean {
        restoreInProgress = true
        // Enlazándose todavía: no hay forma de vaciar su cola. Mejor no seguir.
        if (isBinding && service == null) {
            restoreInProgress = false
            return false
        }
        val running = service
        if (running != null && !running.quiesceForRestore()) {
            running.resumeWritesAfterCancelledRestore()
            restoreInProgress = false
            return false
        }
        if (isBound || isBinding) {
            try { unbindService(connection) } catch (_: IllegalArgumentException) {}
            isBound = false
            isBinding = false
        }
        service = null
        stopService(Intent(this, MiniICService::class.java))
        return true
    }

    /** 3.0 — La restauración se ha cancelado o ha fallado antes de reemplazar nada. */
    fun resumeMonitoringAfterRestoreCancelled() {
        if (!restoreInProgress) return
        restoreInProgress = false
        startAndBindService()
    }

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
        service?.setUiVisible(true)
    }

    override fun onStop() {
        service?.setUiVisible(false)
        isActivityVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Un bindService() aceptado exige su unbindService() aunque la conexión aún no haya
        // llegado (isBinding): si no, la actividad destruida deja la conexión colgada.
        if (isBound || isBinding) {
            try { unbindService(connection) } catch (_: IllegalArgumentException) {}
            isBound = false
            isBinding = false
        }
    }
}
