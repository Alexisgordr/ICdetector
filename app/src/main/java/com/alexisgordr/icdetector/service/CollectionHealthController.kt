package com.alexisgordr.icdetector.service

import android.content.SharedPreferences
import com.alexisgordr.icdetector.core.CollectionHealth
import com.alexisgordr.icdetector.core.MonitoringStop

/**
 * Owns persistence and user-visible state for database write continuity.
 *
 * v2.10.8 — Los textos de la notificación llegan de recursos (inglés/castellano). La línea del
 * terminal sigue en castellano: la traduce `localizeTerminalLine` como el resto del registro.
 */
internal class CollectionHealthController(
    private val preferences: SharedPreferences,
    private val interruptedText: (hours: Long, minutes: Long) -> String,
    private val writeFailureText: () -> String
) {
    enum class Change { NONE, FAILED, RECOVERED }

    private val health = CollectionHealth()
    private var interruptionNoticeUntilMs = 0L
    private var interruptionNotice = ""

    fun restore(nowMs: Long = System.currentTimeMillis()): String? {
        health.restore(preferences.getLong(KEY_LAST_SUCCESSFUL_WRITE, 0L))
        val gap = health.interruptionBefore(nowMs) ?: return null
        val hours = gap / 3_600_000L
        val minutes = (gap % 3_600_000L) / 60_000L
        interruptionNotice = interruptedText(hours, minutes)
        interruptionNoticeUntilMs = nowMs + INTERRUPTION_NOTICE_MS
        return "⚠ Recolección interrumpida ${hours}h ${minutes}min — sin registrar nada desde la última escritura. Revisa si el servicio se detuvo (reinicio del móvil, OTA o batería agotada)."
    }

    @Synchronized
    fun noteWrite(rowId: Long, nowMs: Long = System.currentTimeMillis()): Change {
        val changed = health.noteWrite(rowId, nowMs)
        if (rowId != -1L) {
            preferences.edit().putLong(KEY_LAST_SUCCESSFUL_WRITE, health.lastSuccessMs).apply()
        }
        return when {
            !changed -> Change.NONE
            health.isFailing -> Change.FAILED
            else -> Change.RECOVERED
        }
    }

    fun visibleNotification(requested: String, nowMs: Long = System.currentTimeMillis()): String = when {
        health.isFailing -> writeFailureText()
        nowMs < interruptionNoticeUntilMs -> "$interruptionNotice · $requested"
        else -> requested
    }

    companion object {
        /**
         * 3.1.0 (#42) — Evento de collection-health: el servicio en primer plano no pudo arrancar
         * (hora y clase de la excepción). Lo borra el siguiente arranque correcto.
         */
        fun recordForegroundStartFailure(preferences: SharedPreferences, stopped: MonitoringStop.Stopped) {
            preferences.edit()
                .putLong(MonitoringStop.PREF_STOPPED_AT_MS, stopped.atMs)
                .putString(MonitoringStop.PREF_STOPPED_ERROR, stopped.errorClass)
                .apply()
        }

        fun foregroundStartFailure(preferences: SharedPreferences): MonitoringStop.Stopped? = MonitoringStop.fromStored(
            preferences.getLong(MonitoringStop.PREF_STOPPED_AT_MS, 0L),
            preferences.getString(MonitoringStop.PREF_STOPPED_ERROR, null)
        )

        fun clearForegroundStartFailure(preferences: SharedPreferences) {
            preferences.edit().remove(MonitoringStop.PREF_STOPPED_AT_MS).remove(MonitoringStop.PREF_STOPPED_ERROR).apply()
        }

        private const val KEY_LAST_SUCCESSFUL_WRITE = "last_successful_write_ms"
        private const val INTERRUPTION_NOTICE_MS = 10L * 60_000L
    }
}
