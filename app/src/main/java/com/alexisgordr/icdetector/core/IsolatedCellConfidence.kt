package com.alexisgordr.icdetector.core

/**
 * Rejects one-off empty-neighbour modem snapshots before they reach H1.
 *
 * Empty neighbour lists are common transient radio snapshots. H1 becomes evidence only after
 * [requiredFreshObservations] consecutive fresh deliveries for the same serving identity. Any
 * contradictory snapshot or handover clears the streak immediately.
 *
 * 3.0 — Un token nulo, 0 o negativo es «desconocido» y cuenta como entrega fresca. Antes el servicio
 * convertía el null en 0, y un módem cuyo `timestampMillis` vale 0 dejaba la racha en 1/3 para
 * siempre (H1 en N/A sin explicación). Un token constante distinto de cero no se puede distinguir
 * de un sondeo repetido de la misma lectura: sigue sin contar, pero [stuckOnRepeatedToken] lo
 * delata para que el servicio lo diga en el terminal.
 */
class IsolatedCellConfidence(
    private val requiredFreshObservations: Int = 3
) {
    private var identity: String? = null
    private var lastToken: Long? = null
    private var consecutive = 0
    private var repeats = 0

    /**
     * 3.0 — Muchas entregas candidatas seguidas con el mismo token y la racha sin completar: el
     * módem no parece fechar las lecturas. H1 queda N/A mientras dure.
     */
    val stuckOnRepeatedToken: Boolean get() = repeats >= STUCK_REPEATS && consecutive < requiredFreshObservations

    /** 3.0 (#28) — Entregas seguidas sin vecinas ya vistas para la celda actual. */
    val progress: Int get() = consecutive

    /** 3.0 (#28) — Entregas necesarias para confirmar el aislamiento. */
    val required: Int get() = requiredFreshObservations

    fun observe(identity: String, candidate: Boolean, observationToken: Long?): Boolean {
        if (this.identity != identity) {
            this.identity = identity
            lastToken = null
            consecutive = 0
            repeats = 0
        }
        val token = observationToken?.takeIf { it > 0L }

        if (!candidate) {
            lastToken = token
            consecutive = 0
            repeats = 0
            return false
        }

        val fresh = token == null || token != lastToken
        if (fresh) {
            lastToken = token
            consecutive++
            repeats = 0
        } else {
            repeats++
        }
        return consecutive >= requiredFreshObservations
    }

    fun reset() {
        identity = null
        lastToken = null
        consecutive = 0
        repeats = 0
    }

    companion object {
        /** Entregas candidatas repetidas a partir de las que se avisa (unos minutos de sondeo). */
        const val STUCK_REPEATS = 20
    }
}
