package com.alexisgordr.icdetector.core

/** 3.1.0 (H17) — Movimiento del teléfono según la velocidad de un fix reciente. */
enum class ChurnMotion { STILL, MOVING, UNKNOWN }

/**
 * 3.1.0 (H17) — Churn de la servidora en los últimos [CellChurn.WINDOW_MS]: cuántas identidades
 * completas distintas la sirvieron y cuántos cambios hubo, con el movimiento del teléfono.
 */
data class ChurnSnapshot(val distinctCells15m: Int, val changes15m: Int, val motion: ChurnMotion)

/**
 * 3.1.0 (H17, en sombra) — Churn de celdas: cuántas servidoras distintas y cuántos cambios en 15 min.
 *
 * H10 mira ráfagas de 10 s (ping-pong). Un emisor que atrae al teléfono una y otra vez, o una red que
 * reasigna la servidora sin parar estando quieto, deja otra huella: muchas identidades y cambios en
 * un rato largo. En movimiento eso es normal (cada celda del camino), así que el dato va con el
 * movimiento. Es una regla EN SOMBRA: se calcula y se guarda en cada fila, pero no se evalúa, no
 * puntúa y no cuenta como evidencia hasta calibrar sus umbrales con los datos de campaña (3.2).
 *
 * Un cambio es el de [ServingIdentityChange]: identidad completa; un campo desconocido o un CID
 * ilegible no cuentan. Las identidades distintas incluyen la que servía al empezar la ventana.
 * [restartAt] tras una pérdida de servicio o un hueco ([CoverageContinuity]): lo de antes no se
 * suma a lo de después. Tiempos en un reloj monotónico (el servicio usa `elapsedRealtime`).
 *
 * Puro, sin Android.
 */
internal class CellChurn(private val windowMs: Long = WINDOW_MS) {

    /** Una servidora desde [startMs]; [isChange] = llegó por un cambio (no es la primera tras un reset). */
    private data class Segment(val key: String, val startMs: Long, val isChange: Boolean)

    private val segments = ArrayDeque<Segment>()
    private var previous: ServingIdentityChange.Identity? = null

    /** Lectura de la servidora en [nowMs]. */
    @Synchronized
    fun observe(identity: ServingIdentityChange.Identity, nowMs: Long) {
        val step = ServingIdentityChange.step(previous, identity)
        val first = previous == null
        previous = step.next
        if (step.handover) segments.addLast(Segment(identity.key, nowMs, isChange = !first))
        prune(nowMs)
    }

    /** Borrado del historial: empieza de cero. */
    @Synchronized
    fun reset() {
        segments.clear()
        previous = null
    }

    /**
     * 3.1.0 — Pérdida de servicio o hueco detectados al analizar la entrega de [atMs]. Las lecturas
     * se observan al recibirlas (antes del análisis), así que la de [atMs], y las que hayan llegado
     * después mientras se analizaba, ya están dentro: se olvida lo anterior a [atMs] y la servidora
     * de ese instante pasa a ser la primera (no un cambio). Lo posterior se conserva. Con
     * [servedAtMs] = false esa entrega no traía servidora: no queda ninguna de antes.
     */
    @Synchronized
    fun restartAt(atMs: Long, servedAtMs: Boolean) {
        val current = segments.lastOrNull { it.startMs <= atMs }.takeIf { servedAtMs }
        val later = segments.filter { it.startMs > atMs }
        segments.clear()
        if (current != null) segments.addLast(Segment(current.key, atMs, isChange = false))
        later.forEachIndexed { i, s -> segments.addLast(if (i == 0 && current == null) s.copy(isChange = false) else s) }
        if (segments.isEmpty()) previous = null
    }

    /**
     * Estado en [nowMs]. [speedMps] y [fixAgeMs] son los del último fix: el movimiento solo se
     * deduce de un fix de 60 s o menos ([MobileCellIdFixAge]); si no, UNKNOWN.
     */
    @Synchronized
    fun snapshot(nowMs: Long, speedMps: Float?, fixAgeMs: Long?): ChurnSnapshot {
        prune(nowMs)
        val since = nowMs - windowMs
        // Una lectura recibida después de [nowMs] (mientras se analizaba esta) no entra en su foto.
        val upToNow = segments.filter { it.startMs <= nowMs }
        return ChurnSnapshot(
            distinctCells15m = upToNow.map { it.key }.toSet().size,
            changes15m = upToNow.count { it.isChange && it.startMs >= since },
            motion = motionOf(speedMps, fixAgeMs)
        )
    }

    /** Quita las servidoras que dejaron de servir antes de la ventana (la actual siempre queda). */
    private fun prune(nowMs: Long) {
        val since = nowMs - windowMs
        while (segments.size > 1 && segments[1].startMs < since) segments.removeFirst()
    }

    companion object {
        const val WINDOW_MS = 15L * 60L * 1000L

        /** Igual que el umbral de movimiento de Stable-Site: por encima, el teléfono se desplaza. */
        const val MOVING_MIN_SPEED_MPS = 2.5f

        fun motionOf(speedMps: Float?, fixAgeMs: Long?): ChurnMotion = when {
            !MobileCellIdFixAge.usable(fixAgeMs) -> ChurnMotion.UNKNOWN
            speedMps == null || !speedMps.isFinite() || speedMps < 0f -> ChurnMotion.UNKNOWN
            speedMps >= MOVING_MIN_SPEED_MPS -> ChurnMotion.MOVING
            else -> ChurnMotion.STILL
        }
    }
}
