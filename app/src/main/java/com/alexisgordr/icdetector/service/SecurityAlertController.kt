package com.alexisgordr.icdetector.service

import android.media.ToneGenerator
import android.os.SystemClock
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
    /**
     * Reloj monotónico para las dos duraciones de esta clase (enfriamiento del tono de señal fuerte
     * y cierre de episodios 2G/3G). Un cambio de hora civil no debe alargarlas ni acortarlas.
     */
    private val nowMs: () -> Long = SystemClock::elapsedRealtime
) {
    // 3.0 — También los toca onServiceInterrupted() desde el hilo de lectura de celdas.
    @Volatile private var previousNetworkType: String? = null
    @Volatile private var previousDbm: Int? = null
    private var lastStrongSignalAlarm: Long? = null
    private val alarmEpisodes = AlarmEpisodeGate()
    private val legacyEpisodes = LegacyNetworkEpisodeGate()
    private var pingPongLogged = false

    fun resetAlarmEpisode() {
        alarmEpisodes.reset()
        pingPongLogged = false
    }

    /** Cambio de celda: cierra el episodio de alarma salvo que sea el de un ping-pong. */
    fun onHandover() {
        if (!alarmEpisodes.onHandover()) pingPongLogged = false
    }

    /**
     * 3.0 — El servicio no tiene celda activa (sin señal, modo avión, primaria inutilizable). Un
     * episodio 2G/3G abierto sigue abierto, pero su cuenta de cierre vuelve a empezar, y la lectura
     * siguiente no cuenta como "cambio desde 4G/5G" porque no hubo continuidad.
     */
    fun onServiceInterrupted() {
        legacyEpisodes.interrupt()
        previousNetworkType = null
        previousDbm = null
    }

    /**
     * @param episodeKey clave del episodio de alarma: la identidad de la celda o, durante un
     * ping-pong, la misma clave de conjunto con la que se confirmó (una notificación por episodio).
     */
    fun evaluate(
        cell: CellData,
        confirmed: Boolean,
        context: ObservationContext,
        episodeKey: String = cell.identityKey
    ) {
        val dbm = cell.dbm
        val network = cell.networkType

        if (strongSignalEnabled() && dbm != -999 && dbm >= strongSignalThreshold()) {
            val now = nowMs()
            if (lastStrongSignalAlarm?.let { now - it <= STRONG_SIGNAL_COOLDOWN_MS } != true) {
                lastStrongSignalAlarm = now
                tone()?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 150)
                Log.w(TAG, "Warning: High power signal detected: $dbm dBm")
                log("⚠️ Señal sospechosamente fuerte detectada: $dbm dBm")
            }
        }

        // 3.0 — Red 2G/3G: una sola ruta, un aviso por episodio continuo y según la preferencia.
        // El cambio 4G/5G → 2G/3G es solo el tipo de episodio, no una ruta con su propio tono.
        legacyEpisodes.observe(network, previousNetworkType, nowMs())?.let { kind ->
            // previousDbm es la última potencia observada antes de esta lectura (null tras una
            // interrupción); LegacyNetworkProtection la valida y la muestra solo como contexto.
            legacyProtection.onEpisodeStart(kind, legacyAlertMode(), previousDbm)
        }

        if (cell.isSuspicious) {
            // 3.0 — Sin tono aquí: sonaba en cada evaluación mientras durase la alarma. El único
            // sonido de una alarma confirmada es su notificación (canal de alertas), una por episodio.
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
            if (confirmed && alarmEpisodes.onConfirmedAlarm(episodeKey)) {
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
