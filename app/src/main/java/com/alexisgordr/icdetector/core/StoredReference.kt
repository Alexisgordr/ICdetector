package com.alexisgordr.icdetector.core

/**
 * 3.0 (V3) — La última posición aceptada, guardada para sobrevivir a un reinicio del servicio.
 * Formato `lat,lon,horaCivil,elapsedMs,arranque` (el antiguo, `lat,lon,horaCivil`, se sigue leyendo).
 *
 * Al recuperarla:
 *  - Mismo arranque y marca monotónica válida: la edad se decide con el reloj MONOTÓNICO. Corregir
 *    la hora del móvil ya no descarta una referencia válida ni da por reciente una vieja. Una marca
 *    del futuro (imposible en el mismo arranque) se descarta.
 *  - Formato antiguo u otro arranque: conservador. Solo se conserva si la hora civil dice que tiene
 *    ≤ 6 h, y SIN tiempo monotónico: sirve para la continuidad del GPS y nada más.
 *
 * 3.1.0 — Antes compartía fichero con la validación de coordenadas de la verificación externa;
 * al retirarse esta, queda sola (la usa LocationCollectionController).
 */
object StoredReference {
    const val MAX_AGE_MS = 6L * 60L * 60L * 1000L

    data class Restored(val latitude: Double, val longitude: Double, val civilTimeMs: Long, val elapsedMs: Long?)

    fun serialize(lat: Double, lon: Double, civilTimeMs: Long, elapsedMs: Long, bootId: String?) =
        "$lat,$lon,$civilTimeMs,$elapsedMs,${bootId.orEmpty()}"

    /** Edad monotónica, o null si no puede demostrarse (sin tiempo, tiempo 0 o del futuro). */
    fun monotonicAgeMs(fixElapsedMs: Long?, nowElapsedMs: Long): Long? {
        if (fixElapsedMs == null || fixElapsedMs <= 0L) return null
        return (nowElapsedMs - fixElapsedMs).takeIf { it >= 0L }
    }

    fun restore(text: String?, nowCivilMs: Long, nowElapsedMs: Long, bootId: String?): Restored? {
        val parts = text?.split(",") ?: return null
        if (parts.size != 3 && parts.size != 5) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        val civil = parts[2].toLongOrNull() ?: return null
        val elapsed = parts.getOrNull(3)?.toLongOrNull()
        val boot = parts.getOrNull(4)
        if (elapsed != null && elapsed > 0 && !boot.isNullOrEmpty() && boot == bootId) {
            val age = monotonicAgeMs(elapsed, nowElapsedMs) ?: return null
            return if (age <= MAX_AGE_MS) Restored(lat, lon, civil, elapsed) else null
        }
        return if (nowCivilMs - civil in 0..MAX_AGE_MS) Restored(lat, lon, civil, null) else null
    }
}
