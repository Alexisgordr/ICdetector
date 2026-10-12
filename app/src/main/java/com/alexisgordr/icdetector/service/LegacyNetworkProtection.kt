package com.alexisgordr.icdetector.service

import android.util.Log
import com.alexisgordr.icdetector.core.LegacyNetwork
import com.alexisgordr.icdetector.core.LegacyNetworkAlertMode
import com.alexisgordr.icdetector.core.LegacyNetworkEpisodeGate

/**
 * Aviso de uso de red 2G/3G, una vez por episodio ([LegacyNetworkEpisodeGate]) y según la preferencia
 * [LegacyNetworkAlertMode]. No es un ataque confirmado: también ocurre por cobertura o en llamadas
 * sin VoLTE. El único sonido posible es el de la propia notificación, en su canal, y solo con
 * [LegacyNetworkAlertMode.NOTIFY_SOUND]; no hay tono manual.
 *
 * El modo avión no se activa nunca de forma automática: la notificación ofrece abrir sus ajustes y
 * decide el usuario. Escribir AIRPLANE_MODE_ON exige WRITE_SECURE_SETTINGS, un permiso privilegiado
 * que la app no declara (ni debe declarar); ese intento fallaba siempre.
 */
internal class LegacyNetworkProtection(
    private val notifications: ServiceNotificationController,
    private val log: (String) -> Unit
) {
    /** @param previousDbm última potencia anterior observada (sin validar); solo contexto. */
    fun onEpisodeStart(kind: LegacyNetworkEpisodeGate.Trigger, mode: LegacyNetworkAlertMode, previousDbm: Int?) {
        Log.w("MiniIC", "2G/3G network in use ($kind, alert ${mode.storedValue})")
        val what = if (kind == LegacyNetworkEpisodeGate.Trigger.CHANGE_FROM_MODERN) "Red 2G/3G en uso: cambio desde 4G/5G" else "Red 2G/3G en uso"
        log("$what · ${LegacyNetwork.previousSignalLog(previousDbm)} · aviso ${mode.storedValue}. $CAUSE_NOTE")
        if (mode == LegacyNetworkAlertMode.OFF) return
        notifications.showLegacyNetworkNotice(kind, silent = mode != LegacyNetworkAlertMode.NOTIFY_SOUND,
            previousDbm = LegacyNetwork.previousSignalDbm(previousDbm))
    }

    companion object {
        const val CAUSE_NOTE = "Puede deberse a cobertura o a llamadas sin VoLTE; no es un ataque confirmado."
    }
}
