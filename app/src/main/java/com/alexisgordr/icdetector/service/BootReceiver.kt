package com.alexisgordr.icdetector.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alexisgordr.icdetector.backup.FirstRun
import com.alexisgordr.icdetector.core.BootStart

/**
 * 3.1.0 (#42) — Tras reiniciar el teléfono, vuelve a arrancar el monitoreo si el usuario lo ha
 * pedido en Ajustes (desactivado por defecto) y estaba activado al apagar. Ver [BootStart].
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(MonitoringStopSink.PREFS_FILE, Context.MODE_PRIVATE)
        BootStart.handle(
            action = intent.action,
            settingEnabled = prefs.getBoolean(BootStart.PREF_ENABLED, BootStart.DEFAULT_ENABLED),
            monitoringWasEnabled = prefs.getBoolean(BootStart.PREF_MONITORING_ENABLED, false),
            firstRunPending = FirstRun.isPending(context),
            sink = MonitoringStopSink(context),
            nowMs = System.currentTimeMillis()
        ) {
            context.startForegroundService(Intent(context, MiniICService::class.java))
        }
    }
}
