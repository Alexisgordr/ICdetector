package com.alexisgordr.icdetector.service

import android.media.ToneGenerator
import android.util.Log
import com.alexisgordr.icdetector.core.AlarmEpisodeGate
import com.alexisgordr.icdetector.core.LegacyNetworkAlertMode
import com.alexisgordr.icdetector.core.LegacyNetworkEpisodeGate
import com.alexisgordr.icdetector.core.ObservationContext
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.identityKey

/** Owns audible and legacy-network alert state after temporal threat confirmation. */
internal class SecurityAlertController(
    private val tone: () -> ToneGenerator?,
    private val legacyProtection: LegacyNetworkProtection,
    private val log: (String) -> Unit,
    private val requestPreciseLocation: () -> Unit,
    private val persistConfirmedAlarm: (CellData, ObservationContext) -> Unit,
    private val notifyConfirmedAlarm: (CellData) -> Unit,
    private val strongSignalEnabled: () -> Boolean,
    private val strongSignalThreshold: () -> Float,
    private val legacyAlertMode: () -> LegacyNetworkAlertMode,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private var previousNetworkType: String? = null
    private var previousDbm: Int? = null
    private var lastStrongSignalAlarm = 0L
    private val alarmEpisodes = AlarmEpisodeGate()
    private val legacyEpisodes = LegacyNetworkEpisodeGate()
    private var pingPongLogged = false

    fun resetAlarmEpisode() {
        alarmEpisodes.reset()
        pingPongLogged = false
    }

    fun evaluate(cell: CellData, confirmed: Boolean, context: ObservationContext) {
        val dbm = cell.dbm
        val network = cell.networkType

        if (strongSignalEnabled() && dbm != -999 && dbm >= strongSignalThreshold()) {
            val now = System.currentTimeMillis()
            if (now - lastStrongSignalAlarm > STRONG_SIGNAL_COOLDOWN_MS) {
                lastStrongSignalAlarm = now
                tone()?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 150)
                Log.w(TAG, "Warning: High power signal detected: $dbm dBm")
                log("⚠️ Señal sospechosamente fuerte detectada: $dbm dBm")
            }
        }

        // 3.0 — Red 2G/3G: una sola ruta, un aviso por episodio continuo y según la preferencia.
        // El cambio 4G/5G → 2G/3G es solo el tipo de episodio, no una ruta con su propio tono.
        legacyEpisodes.observe(network, previousNetworkType, nowMs())?.let { kind ->
            if (kind == LegacyNetworkEpisodeGate.Trigger.CHANGE_FROM_MODERN) {
                Log.w(TAG, "Change to 2G/3G; previous signal: ${previousDbm ?: "?"} dBm")
            }
            legacyProtection.onEpisodeStart(kind, legacyAlertMode())
        }

        if (cell.isSuspicious) {
            tone()?.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 200)
            Log.e(TAG, "THREAT DETECTED: ${cell.suspiciousReason}")
            requestPreciseLocation()
            // v2.10.7 — Se escribe al confirmarse, no en cada evaluación posterior de la misma
            // alarma (llegaba dos veces cada pocos segundos). Solo afecta al terminal.
            val pingPongConfirmed = !cell.heuristicReport.pingPongPassed
            if (pingPongConfirmed && !pingPongLogged) {
                log("🚨 Efecto Ping-Pong confirmado por TemporalConfidence.")
            }
            pingPongLogged = pingPongConfirmed
            // v2.10.10 — Un episodio por identidad completa que se cierra tras 60 s sin alarma
            // (ver AlarmEpisodeGate). Antes solo se cerraba al cambiar de celda.
            if (confirmed && alarmEpisodes.onConfirmedAlarm(cell.identityKey)) {
                persistConfirmedAlarm(cell, context)
                // v2.10.8 — Aviso visible, una vez por episodio (antes solo tono y registro).
                notifyConfirmedAlarm(cell)
            }
        } else {
            pingPongLogged = false
            if (confirmed) alarmEpisodes.onClear()
        }

        previousNetworkType = network
        previousDbm = dbm
    }

    private companion object {
        const val TAG = "MiniIC"
        const val STRONG_SIGNAL_COOLDOWN_MS = 60_000L
    }
}
