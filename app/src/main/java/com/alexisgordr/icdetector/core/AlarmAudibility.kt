package com.alexisgordr.icdetector.core

/**
 * 3.0 (#35) — ¿Sonará la notificación de una alarma confirmada?
 *
 * Bug found during testing: la notificación fija de monitorización se repintaba cada ~2 s y, si su
 * canal tenía sonido, el móvil pitaba en cada actualización. Lo lógico era silenciar la app entera,
 * y eso silenciaba también el canal de alertas de seguridad: una alarma confirmada llegaba sin
 * sonido y nada lo decía. La notificación fija ya no avisa nunca; esto detecta el caso en que el
 * usuario ya ha silenciado las alarmas, para avisarle en la pantalla principal.
 *
 * La app no puede reactivar el sonido: lo decide el usuario en los ajustes de Android. Solo puede
 * decirlo y abrir esos ajustes.
 *
 * Sin dependencias de Android: recibe lo que devuelve `NotificationManager`.
 */
object AlarmAudibility {

    enum class AlarmSound {
        /** Notificaciones activas y canal de alertas con sonido (o aún no creado: nace con sonido). */
        AUDIBLE,
        /** El canal de alertas existe pero no hace sonido (silencioso, mínimo o sonido "Ninguno"). */
        SILENCED,
        /** Las notificaciones de la app o el canal de alertas están desactivados. */
        BLOCKED
    }

    // Valores de NotificationManager.IMPORTANCE_*, copiados para no depender de Android.
    const val IMPORTANCE_NONE = 0
    const val IMPORTANCE_DEFAULT = 3

    /**
     * @param notificationsEnabled `NotificationManager.areNotificationsEnabled()`.
     * @param alertChannelImportance importancia del canal de alertas, o null si aún no existe.
     * @param alertChannelHasSound si el canal tiene un sonido: `channel.sound` distinto de null y de
     *   `Uri.EMPTY`. Android permite un canal de importancia alta con sonido "Ninguno"; ese canal
     *   salta en pantalla pero no suena. Bug found during testing: antes no se miraba.
     */
    fun evaluate(
        notificationsEnabled: Boolean,
        alertChannelImportance: Int?,
        alertChannelHasSound: Boolean = true
    ): AlarmSound = when {
        !notificationsEnabled -> AlarmSound.BLOCKED
        alertChannelImportance == null -> AlarmSound.AUDIBLE
        alertChannelImportance <= IMPORTANCE_NONE -> AlarmSound.BLOCKED
        alertChannelImportance < IMPORTANCE_DEFAULT -> AlarmSound.SILENCED
        !alertChannelHasSound -> AlarmSound.SILENCED
        else -> AlarmSound.AUDIBLE
    }
}
