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
 *  - [ADAPTIVE] (ahorro): con la pantalla encendida, igual que el continuo. Con la pantalla apagada
 *    no hay GPS continuo; se pide una ubicación al volver a usar el móvil, al cambiar de celda o al
 *    detectar una sospecha, como mucho una cada [ADAPTIVE_MIN_FIX_INTERVAL_MS], para que cambios de
 *    celda repetidos o una sospecha persistente no acaben pidiendo GPS sin parar.
 *
 * Una ubicación de más de 2 minutos nunca se usa (ver LocationCollectionController): en modo
 * adaptativo, entre peticiones, las reglas que necesitan posición quedan en N/A en vez de usar una
 * posición vieja. Cada fila del historial guarda el modo con el que se observó.
 */
enum class LocationMode(val storedValue: String) {
    CONTINUOUS("CONTINUOUS"),
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

    /** ¿Debe estar activo el GPS continuo? En adaptativo, solo con la pantalla encendida. */
    fun streamWanted(mode: LocationMode, screenOn: Boolean): Boolean =
        mode == LocationMode.CONTINUOUS || screenOn

    /**
     * ¿Se acepta ahora una petición de ubicación bajo demanda? En continuo no cambia nada (se
     * aplican las esperas de siempre). En adaptativo, como mucho una cada
     * [LocationMode.ADAPTIVE_MIN_FIX_INTERVAL_MS], también las forzadas por una sospecha.
     */
    class OnDemandGate(private val minIntervalMs: Long = LocationMode.ADAPTIVE_MIN_FIX_INTERVAL_MS) {
        private var lastGrantedAtMs: Long? = null

        @Synchronized
        fun tryAcquire(mode: LocationMode, nowElapsedMs: Long): Boolean {
            if (mode == LocationMode.CONTINUOUS) return true
            val last = lastGrantedAtMs
            if (last != null && nowElapsedMs - last < minIntervalMs) return false
            lastGrantedAtMs = nowElapsedMs
            return true
        }
    }
}
