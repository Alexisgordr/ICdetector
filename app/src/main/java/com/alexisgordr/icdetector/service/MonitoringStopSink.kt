package com.alexisgordr.icdetector.service

import android.content.Context
import com.alexisgordr.icdetector.core.MonitoringStop

/**
 * 3.1.0 (#42) — Lo que deja un fallo al arrancar el servicio en primer plano: el evento de
 * collection-health y la notificación normal «Monitoreo detenido». Lo usan el servicio, la
 * actividad y el receptor de arranque.
 */
internal class MonitoringStopSink(private val context: Context) : MonitoringStop.Sink {
    private val preferences get() = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override fun record(stopped: MonitoringStop.Stopped) =
        CollectionHealthController.recordForegroundStartFailure(preferences, stopped)

    override fun notifyStopped() = ServiceNotificationController.notifyMonitoringStopped(context, preferences)

    override fun cleared() {
        CollectionHealthController.clearForegroundStartFailure(preferences)
        ServiceNotificationController.cancelMonitoringStopped(context)
        // 3.1.0 (#42) — Un arranque correcto (también abriendo la app sin tocarla) deja sin objeto
        // la notificación «El teléfono se ha reiniciado».
        ServiceNotificationController.cancelResumeAfterBoot(context)
    }

    companion object {
        const val PREFS_FILE = "miniic_prefs"
    }
}
