package com.alexisgordr.icdetector.core

/**
 * v2.10.9 — Cuándo recalcular la huella RSRQ/SINR cacheada aunque la celda y el TTL sigan siendo
 * los mismos. La huella se acota a la posición actual, así que tras moverse hay que recalcularla;
 * misma distancia que ya usaba el baseline de potencia. Sin Android, para poder probarla.
 */
internal object RfFingerprintRefresh {
    const val REFRESH_METERS = 150f

    /**
     * @param computedWithFix si la huella cacheada se calculó con una posición GPS.
     * @param movedMeters distancia desde esa posición hasta la actual; null si ahora no hay GPS.
     */
    fun needed(computedWithFix: Boolean, movedMeters: Float?): Boolean = when {
        movedMeters == null -> false          // sin GPS ahora: se mantiene la última huella
        !computedWithFix -> true              // se calculó sin GPS y ya lo hay: acotarla
        else -> movedMeters >= REFRESH_METERS
    }
}
