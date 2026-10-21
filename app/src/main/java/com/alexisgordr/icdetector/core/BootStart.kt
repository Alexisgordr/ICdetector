package com.alexisgordr.icdetector.core

/**
 * 3.1.0 (#42) — Arranque del monitoreo tras reiniciar el teléfono.
 *
 * Solo si el usuario lo ha pedido ([PREF_ENABLED], un ajuste visible, desactivado por defecto) y el
 * monitoreo estaba activado al apagar ([PREF_MONITORING_ENABLED]: lo marca un arranque correcto
 * del servicio y lo desmarca el botón «Detener»). Con la pregunta de la primera apertura sin
 * contestar no se arranca nada. Si Android no deja arrancar el servicio (en Android 14+ sin
 * permiso de ubicación «todo el tiempo» puede negarse), el fallo se informa como cualquier otro
 * ([MonitoringStop]): notificación «Monitoreo detenido — abre la app para reanudar».
 */
object BootStart {
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val PREF_ENABLED = "start_on_boot"
    const val DEFAULT_ENABLED = false
    const val PREF_MONITORING_ENABLED = "monitoring_enabled"

    fun shouldStart(action: String?, settingEnabled: Boolean, monitoringWasEnabled: Boolean, firstRunPending: Boolean): Boolean =
        action == ACTION_BOOT_COMPLETED && settingEnabled && monitoringWasEnabled && !firstRunPending

    /**
     * Lo que hace el receptor: arranca con [start] si procede; si [start] lanza, lo informa con
     * [MonitoringStop.fail]. Devuelve true si intentó arrancar.
     */
    fun handle(
        action: String?,
        settingEnabled: Boolean,
        monitoringWasEnabled: Boolean,
        firstRunPending: Boolean,
        sink: MonitoringStop.Sink,
        nowMs: Long,
        start: () -> Unit
    ): Boolean {
        if (!shouldStart(action, settingEnabled, monitoringWasEnabled, firstRunPending)) return false
        try {
            start()
        } catch (e: Exception) {
            MonitoringStop.fail(sink, nowMs, e)
        }
        return true
    }
}
