/*
 * ICdetection - IMSI-Catcher Detection Tool
 * Copyright (C) 2026 Alexis Gómez Rodríguez
 */

package com.example.miniic

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
import com.example.miniic.service.MiniICService
import com.example.miniic.storage.CellDbHelper
import com.example.miniic.ui.MainLayout

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<MiniICService?>(null)
    private var isBound = false
    private lateinit var dbHelper: CellDbHelper

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as MiniICService.LocalBinder
            service = b.getService()
            isBound = true
            service?.forceRefresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = CellDbHelper(this)
        
        startAndBindService()

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
        val intent = Intent(this, MiniICService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
