package com.alexisgordr.icdetector.core

/**
 * 3.0 (#7, O5) — Espera mínima entre intentos de fix GPS preciso cuando fallan seguidos.
 *
 * Cada handover puede pedir un fix preciso de 20 s. Donde el GPS no llega (interior, túnel,
 * garaje) todos esos intentos fallan y cada uno mantiene el GPS encendido 20 s: sin fuga, pero
 * con un gasto de batería que no aporta nada. Tras cada fallo seguido la espera mínima se dobla
 * (30 s, 1, 2, 4, 8 min, tope 10 min); el primer fix aceptado la devuelve a 30 s.
 *
 * Solo afecta a los intentos normales. Los forzados (episodio sospechoso, salida de modo avión)
 * no esperan nunca: ahí la posición real importa más que la batería.
 */
class PreciseFixBackoff(
    private val baseIntervalMs: Long = 30_000L,
    private val maxIntervalMs: Long = 600_000L
) {
    var consecutiveFailures: Int = 0
        private set

    fun minIntervalMs(): Long =
        (baseIntervalMs shl consecutiveFailures.coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(maxIntervalMs)

    fun onTimeout() { consecutiveFailures++ }

    fun onSuccess() { consecutiveFailures = 0 }

    private companion object { const val MAX_DOUBLINGS = 10 }
}
