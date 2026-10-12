package com.alexisgordr.icdetector.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.alexisgordr.icdetector.core.LegacyNetworkAlertMode
import com.alexisgordr.icdetector.core.LegacyNetworkEpisodeGate

/**
 * Aviso de uso de red 2G/3G, una vez por episodio ([LegacyNetworkEpisodeGate]) y según la preferencia
 * [LegacyNetworkAlertMode]. No es un ataque confirmado: también ocurre por cobertura o en llamadas
 * sin VoLTE. Todo tono de este aviso pasa por aquí y solo suena con [LegacyNetworkAlertMode.NOTIFY_SOUND].
 */
internal class LegacyNetworkProtection(
    private val context: Context,
    private val notifications: ServiceNotificationController,
    private val log: (String) -> Unit,
    private val playWarningTone: () -> Unit
) {
    fun onEpisodeStart(kind: LegacyNetworkEpisodeGate.Trigger, mode: LegacyNetworkAlertMode) {
        Log.w("MiniIC", "2G/3G network in use ($kind, alert ${mode.storedValue})")
        val what = if (kind == LegacyNetworkEpisodeGate.Trigger.CHANGE_FROM_MODERN) "Red 2G/3G en uso: cambio desde 4G/5G" else "Red 2G/3G en uso"
        log("$what · aviso ${mode.storedValue}. $CAUSE_NOTE")
        if (mode == LegacyNetworkAlertMode.OFF) return
        if (mode == LegacyNetworkAlertMode.NOTIFY_SOUND) playWarningTone()
        if (!tryEnableAirplaneMode()) {
            notifications.showLegacyNetworkNotice(kind, silent = mode != LegacyNetworkAlertMode.NOTIFY_SOUND)
        }
    }

    private fun tryEnableAirplaneMode(): Boolean = try {
        val enabled = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        if (!enabled) {
            Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
            context.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", true))
        }
        true
    } catch (_: SecurityException) {
        false
    }

    companion object {
        const val CAUSE_NOTE = "Puede deberse a cobertura o a llamadas sin VoLTE; no es un ataque confirmado."
    }
}
