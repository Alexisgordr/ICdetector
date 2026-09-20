package com.alexisgordr.icdetector.service

import com.alexisgordr.icdetector.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.alexisgordr.icdetector.MainActivity

/** Owns notification construction and channels for the foreground service. */
internal class ServiceNotificationController(
    private val context: Context,
    private val serviceClass: Class<*>
) {
    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "miniIC Channel", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                context.getString(R.string.security_alerts_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Avisos accionables de degradación celular" }
        )
    }

    fun foregroundNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopService = PendingIntent.getService(
            context,
            1,
            Intent(context, serviceClass).apply { action = MiniICService.ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("ICdetection: Monitoreo · GPS continuo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.stop), stopService)
            .build()
    }

    fun notifyForeground(text: String) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, foregroundNotification(text))
    }

    fun showAirplaneModeAction() {
        val settings = PendingIntent.getActivity(
            context,
            2,
            Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle("ICdetection: red 2G/3G detectada")
            .setContentText(context.getString(R.string.airplane_settings_hint))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(settings)
            .addAction(android.R.drawable.ic_menu_manage, "ABRIR AJUSTES", settings)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(AIRPLANE_ACTION_NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "miniic_channel"
        const val ALERT_CHANNEL_ID = "miniic_security_alerts"
        const val NOTIFICATION_ID = 202
        const val AIRPLANE_ACTION_NOTIFICATION_ID = 204
    }
}
