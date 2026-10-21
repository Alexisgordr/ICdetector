package com.alexisgordr.icdetector.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alexisgordr.icdetector.backup.FirstRun
import com.alexisgordr.icdetector.core.BootStart

/**
 * 3.1.0 (#42) — Tras reiniciar el teléfono, si el usuario lo ha pedido en Ajustes (desactivado por
 * defecto) y el monitoreo estaba activado al apagar, avisa con una notificación para reanudarlo.
 * No arranca el servicio desde segundo plano: ver [BootStart].
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(MonitoringStopSink.PREFS_FILE, Context.MODE_PRIVATE)
        val action = BootStart.onBoot(
            action = intent.action,
            settingEnabled = prefs.getBoolean(BootStart.PREF_ENABLED, BootStart.DEFAULT_ENABLED),
            monitoringWasEnabled = prefs.getBoolean(BootStart.PREF_MONITORING_ENABLED, false),
            firstRunPending = FirstRun.isPending(context)
        )
        if (action == BootStart.Action.NOTIFY) ServiceNotificationController.notifyResumeAfterBoot(context)
    }
}
