package com.alexisgordr.icdetector.core

/** Una posición propia que puede servir de referencia: [elapsedMs] es su tiempo monotónico (o null). */
data class CoordinateReference(val latitude: Double, val longitude: Double, val elapsedMs: Long?)

/**
 * 3.0 (V3) — ¿Es creíble la coordenada de antena que devolvió OpenCellID?
 *
 * Barreras que se conservan: rango, (0,0), y la coordenada repetida en ≥ 3 áreas sin relación
 * (valor por defecto de la API). Después, la distancia a la posición propia: 50 km con un fix vivo,
 * 500 km con la última posición aceptada si tiene ≤ 6 h.
 *
 * La edad de esa última posición se mide ahora con el reloj MONOTÓNICO (Location.elapsedRealtimeNanos
 * frente a SystemClock.elapsedRealtime), no con la hora civil: cambiar la hora del móvil ya no hace
 * pasar por reciente una posición vieja ni descarta una reciente. Una posición sin tiempo monotónico
 * del arranque actual (p. ej. la guardada antes de reiniciar) no puede demostrar su edad y no se usa.
 *
 * Sin referencia utilizable la coordenada NO se rechaza (sería castigar la falta de GPS), pero el
 * resultado lo dice: [Result.FOUND_WITHOUT_DISTANCE_CHECK] en vez de [Result.FOUND_DISTANCE_CHECKED].
 * Ninguno de los dos afirma legitimidad; el estado sigue siendo VERIFIED (registro compatible).
 */
object ApiCoordinateCheck {
    enum class Result { REJECTED, FOUND_DISTANCE_CHECKED, FOUND_WITHOUT_DISTANCE_CHECK }

    enum class Reason { NONE, ZERO, OUT_OF_RANGE, SHARED_ACROSS_AREAS, TOO_FAR_FROM_LIVE, TOO_FAR_FROM_RECENT }

    data class Outcome(val result: Result, val reason: Reason = Reason.NONE, val distanceM: Double? = null)

    const val SENTINEL_MIN_AREAS = 3
    const val REFERENCE_MAX_AGE_MS = 6L * 60L * 60L * 1000L
    const val LIVE_MAX_DISTANCE_METERS = 50_000.0
    const val FALLBACK_MAX_DISTANCE_METERS = 500_000.0

    /** Edad monotónica, o null si no puede demostrarse (sin tiempo, tiempo 0 o del futuro). */
    fun monotonicAgeMs(fixElapsedMs: Long?, nowElapsedMs: Long): Long? {
        if (fixElapsedMs == null || fixElapsedMs <= 0L) return null
        return (nowElapsedMs - fixElapsedMs).takeIf { it >= 0L }
    }

    fun isUsableFallback(ref: CoordinateReference?, nowElapsedMs: Long): Boolean =
        ref != null && (monotonicAgeMs(ref.elapsedMs, nowElapsedMs) ?: Long.MAX_VALUE) <= REFERENCE_MAX_AGE_MS

    /**
     * [sharedAreas] cuántas áreas sin relación ya tienen esta coordenada (null si la base falló: la
     * barrera de distancia sigue aplicando). [live] fix vivo ya validado; [recent] última aceptada.
     */
    fun check(lat: Double, lon: Double, sharedAreas: Int?, live: CoordinateReference?, recent: CoordinateReference?, nowElapsedMs: Long): Outcome {
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return Outcome(Result.REJECTED, Reason.OUT_OF_RANGE)
        if (lat == 0.0 && lon == 0.0) return Outcome(Result.REJECTED, Reason.ZERO)
        if (sharedAreas != null && sharedAreas >= SENTINEL_MIN_AREAS) return Outcome(Result.REJECTED, Reason.SHARED_ACROSS_AREAS)
        val (ref, max, reason) = when {
            live != null -> Triple(live, LIVE_MAX_DISTANCE_METERS, Reason.TOO_FAR_FROM_LIVE)
            isUsableFallback(recent, nowElapsedMs) -> Triple(recent!!, FALLBACK_MAX_DISTANCE_METERS, Reason.TOO_FAR_FROM_RECENT)
            else -> return Outcome(Result.FOUND_WITHOUT_DISTANCE_CHECK)
        }
        val d = CellGeometry.distanceMeters(ref.latitude, ref.longitude, lat, lon)
        return if (d > max) Outcome(Result.REJECTED, reason, d) else Outcome(Result.FOUND_DISTANCE_CHECKED, distanceM = d)
    }

    /** Línea del terminal cuando no hubo contraste de distancia. */
    const val LOG_NO_DISTANCE_CHECK =
        "OpenCellID: registro encontrado sin contraste de distancia (no hay posición propia reciente y fiable). No confirma que la antena esté donde dice la base."
}

/**
 * 3.0 (V3) — La última posición aceptada, guardada para sobrevivir a un reinicio del servicio.
 * Formato `lat,lon,horaCivil,elapsedMs,arranque` (el antiguo, `lat,lon,horaCivil`, se sigue leyendo).
 *
 * Al recuperarla:
 *  - Mismo arranque y marca monotónica válida: la edad se decide con el reloj MONOTÓNICO. Corregir
 *    la hora del móvil ya no descarta una referencia válida ni da por reciente una vieja. Una marca
 *    del futuro (imposible en el mismo arranque) se descarta.
 *  - Formato antiguo u otro arranque: conservador. Solo se conserva si la hora civil dice que tiene
 *    ≤ 6 h, y SIN tiempo monotónico: sirve para la continuidad del GPS, pero ApiCoordinateCheck no la
 *    usa para validar coordenadas (no puede demostrar su edad).
 */
object StoredReference {
    data class Restored(val latitude: Double, val longitude: Double, val civilTimeMs: Long, val elapsedMs: Long?)

    fun serialize(lat: Double, lon: Double, civilTimeMs: Long, elapsedMs: Long, bootId: String?) =
        "$lat,$lon,$civilTimeMs,$elapsedMs,${bootId.orEmpty()}"

    fun restore(text: String?, nowCivilMs: Long, nowElapsedMs: Long, bootId: String?): Restored? {
        val parts = text?.split(",") ?: return null
        if (parts.size != 3 && parts.size != 5) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        val civil = parts[2].toLongOrNull() ?: return null
        val elapsed = parts.getOrNull(3)?.toLongOrNull()
        val boot = parts.getOrNull(4)
        if (elapsed != null && elapsed > 0 && !boot.isNullOrEmpty() && boot == bootId) {
            val age = ApiCoordinateCheck.monotonicAgeMs(elapsed, nowElapsedMs) ?: return null
            return if (age <= ApiCoordinateCheck.REFERENCE_MAX_AGE_MS) Restored(lat, lon, civil, elapsed) else null
        }
        return if (nowCivilMs - civil in 0..ApiCoordinateCheck.REFERENCE_MAX_AGE_MS) Restored(lat, lon, civil, null) else null
    }
}
