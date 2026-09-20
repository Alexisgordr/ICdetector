package com.alexisgordr.icdetector.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/** Handles the actionable response to a detected 2G/3G downgrade. */
internal class LegacyNetworkProtection(
    private val context: Context,
    private val notifications: ServiceNotificationController,
    private val log: (String) -> Unit,
    private val playWarningTone: () -> Unit
) {
    private var lastTriggerMs = 0L

    fun trigger(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastTriggerMs < COOLDOWN_MS) return
        lastTriggerMs = nowMs
        Log.e("MiniIC", "CRITICAL: 2G/3G network detected! Fallback to manual settings.")
        log("🚨 CRÍTICO: Red 2G/3G detectada. Abriendo ajustes para Modo Avión manual.")
        playWarningTone()
        try {
            val enabled = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0
            ) != 0
            if (!enabled) {
                Settings.Global.putInt(
                    context.contentResolver,
                    Settings.Global.AIRPLANE_MODE_ON,
                    1
                )
                context.sendBroadcast(
                    Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", true)
                )
            }
        } catch (_: SecurityException) {
            notifications.showAirplaneModeAction()
        }
    }

    private companion object {
        const val COOLDOWN_MS = 60_000L
    }
}
