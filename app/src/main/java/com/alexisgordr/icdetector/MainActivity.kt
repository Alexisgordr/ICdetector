/*
 * ICdetection - IMSI-Catcher Detection Tool
 * Copyright (C) 2026 Alexis Gómez Rodríguez
 */

package com.alexisgordr.icdetector

import android.os.Bundle
import android.os.IBinder
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alexisgordr.icdetector.service.MiniICService
import com.alexisgordr.icdetector.storage.CellDbHelper
import com.alexisgordr.icdetector.ui.MainLayout

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<MiniICService?>(null)
    private var isBound = false
    private var isBinding = false
    private lateinit var dbHelper: CellDbHelper

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as MiniICService.LocalBinder
            service = b.getService()
            isBound = true
            isBinding = false
            service?.forceRefresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            isBinding = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = CellDbHelper(this)
        
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
                MainLayout(context = this, dbHelper = dbHelper, service = service)
            }
        }
    }

    fun startAndBindService() {
        // Guard: evita un doble bindService() si esto se llama dos veces antes de que la conexión
        // se establezca (puede ocurrir entre el callback de permisos y el LaunchedEffect). Sin él,
        // dos binds quedarían con un solo unbind al cerrar (fuga de ciclo de vida).
        if (isBound || isBinding) return

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
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
