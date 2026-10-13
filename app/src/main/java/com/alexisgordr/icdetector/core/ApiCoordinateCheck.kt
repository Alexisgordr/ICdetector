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
