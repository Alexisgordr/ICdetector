package com.alexisgordr.icdetector.core

/**
 * 3.1.0 (#42) — Qué hacer cuando el teléfono termina de reiniciarse.
 *
 * El monitoreo NO se arranca desde segundo plano. La app no pide ubicación en segundo plano
 * (ACCESS_BACKGROUND_LOCATION), así que un servicio en primer plano arrancado desde
 * BOOT_COMPLETED no recibe ubicación ni celdas: quedaría «Monitoreo activo» sin recoger nada. En
 * su lugar, si el usuario lo ha pedido ([PREF_ENABLED], ajuste visible, desactivado por defecto),
 * el monitoreo estaba activado al apagar ([PREF_MONITORING_ENABLED]: lo marca un arranque correcto
 * del servicio y lo desmarca el botón «Detener») y la pregunta de la primera apertura está
 * contestada, se publica una notificación «El teléfono se ha reiniciado — toca para reanudar el
 * monitoreo». Al tocarla se abre la app ([EXTRA_RESUME_AFTER_BOOT]) y el monitoreo arranca desde
 * la actividad, en primer plano, como cualquier arranque normal.
 */
object BootStart {
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val PREF_ENABLED = "start_on_boot"
    const val DEFAULT_ENABLED = false
    const val PREF_MONITORING_ENABLED = "monitoring_enabled"

    /** Extra del intent de la notificación: la actividad reanuda el monitoreo al abrirse. */
    const val EXTRA_RESUME_AFTER_BOOT = "com.alexisgordr.icdetector.RESUME_AFTER_BOOT"

    enum class Action { NOTIFY, NOTHING }

    /** Solo BOOT_COMPLETED (no LOCKED_BOOT_COMPLETED: antes de desbloquear no hay nada que hacer). */
    fun onBoot(action: String?, settingEnabled: Boolean, monitoringWasEnabled: Boolean, firstRunPending: Boolean): Action =
        if (action == ACTION_BOOT_COMPLETED && settingEnabled && monitoringWasEnabled && !firstRunPending) Action.NOTIFY
        else Action.NOTHING

    /** Al abrir la app desde esa notificación: reanudar solo con la primera apertura contestada. */
    fun resumeOnOpen(hasResumeExtra: Boolean, firstRunPending: Boolean): Boolean = hasResumeExtra && !firstRunPending
}
