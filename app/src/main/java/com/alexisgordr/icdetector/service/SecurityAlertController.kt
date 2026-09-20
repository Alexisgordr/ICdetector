package com.alexisgordr.icdetector.service

import android.media.ToneGenerator
import android.util.Log
import com.alexisgordr.icdetector.models.CellData

/** Owns audible and legacy-network alert state after temporal threat confirmation. */
internal class SecurityAlertController(
    private val tone: () -> ToneGenerator?,
    private val legacyProtection: LegacyNetworkProtection,
    private val log: (String) -> Unit,
    private val requestPreciseLocation: () -> Unit,
    private val persistConfirmedAlarm: (CellData) -> Unit,
    private val strongSignalEnabled: () -> Boolean,
    private val strongSignalThreshold: () -> Float,
    private val legacyProtectionEnabled: () -> Boolean
) {
    private var previousNetworkType: String? = null
    private var previousDbm: Int? = null
    private var lastStrongSignalAlarm = 0L
    private var persistedAlarmCellId: String? = null

    fun resetAlarmEpisode() {
        persistedAlarmCellId = null
    }

    fun evaluate(cell: CellData, confirmed: Boolean) {
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

        val previousType = previousNetworkType
        val previousPower = previousDbm
        if (previousType != null && previousPower != null) {
            val wasSecure = previousType.contains("4G") || previousType.contains("5G")
            val isLegacy = network.contains("2G") || network.contains("3G")
            if (wasSecure && isLegacy && previousPower >= -85) {
                tone()?.startTone(ToneGenerator.TONE_SUP_ERROR, 500)
                Log.e(TAG, "CRITICAL: Downgrade detected; pre-transition signal: $previousPower dBm")
                legacyProtection.trigger()
            }
        }

        if (cell.isSuspicious) {
            tone()?.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 200)
            Log.e(TAG, "THREAT DETECTED: ${cell.suspiciousReason}")
            requestPreciseLocation()
            if (!cell.heuristicReport.pingPongPassed) {
                log("🚨 Efecto Ping-Pong confirmado por TemporalConfidence.")
            }
            if (confirmed && persistedAlarmCellId != cell.cellId) {
                persistedAlarmCellId = cell.cellId
                persistConfirmedAlarm(cell)
            }
        }

        if (legacyProtectionEnabled() && (network.contains("3G") || network.contains("2G"))) {
            legacyProtection.trigger()
        }

        previousNetworkType = network
        previousDbm = dbm
    }

    private companion object {
        const val TAG = "MiniIC"
        const val STRONG_SIGNAL_COOLDOWN_MS = 60_000L
    }
}
