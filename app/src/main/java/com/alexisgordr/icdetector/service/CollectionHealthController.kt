package com.alexisgordr.icdetector.service

import android.content.SharedPreferences
import com.alexisgordr.icdetector.core.CollectionHealth

/** Owns persistence and user-visible state for database write continuity. */
internal class CollectionHealthController(private val preferences: SharedPreferences) {
    enum class Change { NONE, FAILED, RECOVERED }

    private val health = CollectionHealth()
    private var interruptionNoticeUntilMs = 0L
    private var interruptionNotice = ""

    fun restore(nowMs: Long = System.currentTimeMillis()): String? {
        health.restore(preferences.getLong(KEY_LAST_SUCCESSFUL_WRITE, 0L))
        val gap = health.interruptionBefore(nowMs) ?: return null
        val hours = gap / 3_600_000L
        val minutes = (gap % 3_600_000L) / 60_000L
        interruptionNotice = "⚠ Recolección interrumpida ${hours}h ${minutes}min"
        interruptionNoticeUntilMs = nowMs + INTERRUPTION_NOTICE_MS
        return "$interruptionNotice — sin registrar nada desde la última escritura. Revisa si el servicio se detuvo (reinicio del móvil, OTA o batería agotada)."
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
        health.isFailing -> WRITE_FAILURE_TEXT
        nowMs < interruptionNoticeUntilMs -> "$interruptionNotice · $requested"
        else -> requested
    }

    companion object {
        const val WRITE_FAILURE_TEXT = "⚠ ESCRITURA FALLIDA — no se están guardando datos"
        private const val KEY_LAST_SUCCESSFUL_WRITE = "last_successful_write_ms"
        private const val INTERRUPTION_NOTICE_MS = 10L * 60_000L
    }
}
