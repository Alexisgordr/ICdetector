package com.alexisgordr.icdetector.core

/**
 * 3.1.3 (#50) — Tamaño del texto de la app, elegido en Ajustes.
 *
 * Muchas notas, etiquetas y el terminal usan 7-9 sp: en pantallas densas no se leen bien. El factor
 * se aplica una sola vez a toda la interfaz (MainActivity) y se multiplica por el tamaño de letra de
 * Android, que la app ya respetaba. [NORMAL] deja la app como estaba.
 */
enum class TextScale(val storedValue: String, val factor: Float) {
    NORMAL("NORMAL", 1.0f),
    LARGE("LARGE", 1.25f),
    EXTRA_LARGE("EXTRA_LARGE", 1.5f);

    companion object {
        /** Clave en las preferencias (miniic_prefs); viaja en la copia de seguridad. */
        const val PREF_KEY = "text_scale"

        /** Lo guardado en preferencias; cualquier valor desconocido o ausente es [NORMAL]. */
        fun fromStored(value: String?): TextScale =
            entries.firstOrNull { it.storedValue == value } ?: NORMAL

        /** Escala de letra final: la de Android multiplicada por la elegida en la app. */
        fun fontScale(systemFontScale: Float, scale: TextScale): Float = systemFontScale * scale.factor
    }
}
