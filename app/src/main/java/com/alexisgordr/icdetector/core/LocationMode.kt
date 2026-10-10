package com.alexisgordr.icdetector.core

/**
 * 3.0 — Modo de ubicación, elegido en Ajustes.
 *
 * Found during testing: el GPS continuo (un fix cada 15 s, también con la pantalla apagada) es lo
 * que más batería gasta. Para una campaña es lo correcto, porque H11, H13 y H16 necesitan una
 * posición contemporánea, pero no todo el mundo usa la app para eso.
 *
 *  - [CONTINUOUS] (predeterminado): el comportamiento de siempre. GPS activo mientras monitoriza,
 *    con la pantalla encendida o apagada. Es el modo de la campaña.
 *  - [INTELLIGENT]: intenta un fix en una ventana acotada aproximadamente cada 45 s, también con la
 *    pantalla apagada. No mantiene una suscripción GPS permanente.
 *  - [ADAPTIVE] (ahorro): con recepción normal y pantalla encendida, igual que el continuo. Apagada
 *    no hay GPS continuo; se pide una ubicación al volver a usar el móvil, al cambiar de celda o al
 *    detectar una sospecha, como mucho una cada [ADAPTIVE_MIN_FIX_INTERVAL_MS], para que cambios de
 *    celda repetidos o una sospecha persistente no acaben pidiendo GPS sin parar.
 *
 * INTELLIGENT y ADAPTIVE espacian los reintentos a 2, 5 y 10 minutos si no consiguen fixes. Esto es
 * evidencia de falta de recepción, no un detector fiable de interiores. Ver [GpsPowerPolicy].
 * Una ubicación de más de 2 minutos nunca se usa (ver LocationCollectionController): en modo
 * adaptativo, entre peticiones, las reglas que necesitan posición quedan en N/A en vez de usar una
 * posición vieja. Cada fila del historial guarda el modo con el que se observó.
 */
enum class LocationMode(val storedValue: String) {
    CONTINUOUS("CONTINUOUS"),
    INTELLIGENT("INTELLIGENT"),
    ADAPTIVE("ADAPTIVE");

    companion object {
        /** Clave en las preferencias. */
        const val PREF_KEY = "location_mode"

        /** Intervalo mínimo entre peticiones de ubicación bajo demanda en modo adaptativo. */
        const val ADAPTIVE_MIN_FIX_INTERVAL_MS = 60_000L

        /** Lo guardado en preferencias; cualquier valor desconocido o ausente es el continuo. */
        fun fromStored(value: String?): LocationMode =
            entries.firstOrNull { it.storedValue == value } ?: CONTINUOUS
    }
}

/** 3.0 — Decisiones del modo de ubicación, sin Android, para poder probarlas. */
object LocationPolicy {
    /** GPS permanente: continuo siempre; adaptativo con pantalla encendida; inteligente nunca. */
    fun streamWanted(mode: LocationMode, screenOn: Boolean): Boolean =
        mode == LocationMode.CONTINUOUS || (mode == LocationMode.ADAPTIVE && screenOn)
}
