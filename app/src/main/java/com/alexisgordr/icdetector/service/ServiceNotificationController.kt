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
import com.alexisgordr.icdetector.core.AlarmAudibility
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.ui.localizeTerminalLine
import com.alexisgordr.icdetector.util.AppLanguage
import com.alexisgordr.icdetector.util.LocaleController

/** Owns notification construction and channels for the foreground service. */
internal class ServiceNotificationController(
    private val context: Context,
    private val serviceClass: Class<*>,
    /** 3.0 — Modo de ubicación, para que el título diga si el GPS es continuo o adaptativo. */
    private val locationMode: () -> com.alexisgordr.icdetector.core.LocationMode = {
        com.alexisgordr.icdetector.core.LocationMode.CONTINUOUS
    }
) {
    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_monitoring_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                context.getString(R.string.security_alerts_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.channel_alerts_description) }
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
            .setContentTitle(
                context.getString(
                    if (locationMode() == com.alexisgordr.icdetector.core.LocationMode.ADAPTIVE) {
                        R.string.notif_monitoring_title_adaptive
                    } else {
                        R.string.notif_monitoring_title
                    }
                )
            )
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(openApp)
            .setOngoing(true)
            // 3.0 (#35) — Se repinta cada ~2 s: nunca debe sonar ni vibrar, aunque el usuario
            // haya dado sonido al canal. Si pitaba, la salida lógica era silenciar la app entera,
            // y con ella las alarmas confirmadas.
            .setOnlyAlertOnce(true)
            .setSilent(true)
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
            .setContentTitle(context.getString(R.string.notif_legacy_network_title))
            .setContentText(context.getString(R.string.airplane_settings_hint))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(settings)
            .addAction(android.R.drawable.ic_menu_manage, context.getString(R.string.notif_open_settings), settings)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(AIRPLANE_ACTION_NOTIFICATION_ID, notification)
    }

    /**
     * v2.10.8 — Una alarma confirmada solo sonaba y se guardaba: si el móvil estaba en el bolsillo
     * o con el sonido apagado, no quedaba ningún aviso visible. Se publica una notificación por
     * episodio (la misma regla que decide cuándo se guarda la alarma). No cambia la detección.
     */
    fun showConfirmedAlarm(cell: CellData) {
        val openApp = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // El motivo se guarda en castellano (dato del dataset); solo se traduce para mostrarlo.
        val storedReason = cell.suspiciousReason.orEmpty()
        val reason = if (LocaleController.selectedLanguage(context) == AppLanguage.ENGLISH) {
            localizeTerminalLine(storedReason)
        } else {
            storedReason
        }
        val body = context.getString(R.string.alarm_notification_body_format, cell.cellId, cell.networkType, reason)
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(CONFIRMED_ALARM_NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "miniic_channel"
        const val ALERT_CHANNEL_ID = "miniic_security_alerts"
        const val NOTIFICATION_ID = 202
        const val AIRPLANE_ACTION_NOTIFICATION_ID = 204
        const val CONFIRMED_ALARM_NOTIFICATION_ID = 205

        /** 3.0 (#35) — ¿Sonaría ahora la notificación de una alarma confirmada? */
        fun alarmAudibility(context: Context): AlarmAudibility.AlarmSound {
            val manager = context.getSystemService(NotificationManager::class.java)
            return AlarmAudibility.evaluate(
                notificationsEnabled = manager.areNotificationsEnabled(),
                alertChannelImportance = manager.getNotificationChannel(ALERT_CHANNEL_ID)?.importance
            )
        }

        /**
         * 3.0 (#35) — Ajustes del canal de alertas; si las notificaciones de la app están
         * desactivadas, los ajustes de notificaciones de la app (el canal no se puede tocar).
         */
        fun alarmSettingsIntent(context: Context): Intent {
            val manager = context.getSystemService(NotificationManager::class.java)
            val intent = if (manager.areNotificationsEnabled()) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, ALERT_CHANNEL_ID)
            } else {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            }
            return intent
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
