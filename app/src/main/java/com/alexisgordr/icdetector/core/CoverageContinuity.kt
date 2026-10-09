package com.alexisgordr.icdetector.core

/**
 * 3.0 (#20) — ¿Sigue habiendo continuidad de cobertura desde la última observación publicada?
 *
 * La continuidad se rompe por dos motivos:
 *  - una pérdida de señal ([CollectionGeneration.losses] cambió), o
 *  - un HUECO: más de [maxGapMs] entre dos observaciones publicadas, aunque no haya llegado
 *    ningún aviso de pérdida.
 *
 * Bug found during testing: solo contaba la pérdida. Si el sistema dejaba de entregar lecturas
 * varios minutos (Doze, módem dormido) y después volvía la misma celda sin una lista vacía de por
 * medio, la racha de aislamiento de H1 seguía contando y H14 comparaba con una banda de antes del
 * hueco. [TemporalConfidence] ya caducaba su racha a los 120 s; ahora el resto del estado que
 * depende de la continuidad usa el mismo límite.
 *
 * Los tiempos son de un reloj monotónico que cuenta el tiempo dormido
 * (`SystemClock.elapsedRealtime()`): con un reloj que se para en reposo profundo, un hueco largo
 * en Doze parecería de cero segundos.
 *
 * Dos pasos, como antes: [check] al analizar, [acknowledge] al publicar. Un ciclo que no se
 * publica no da la ruptura por atendida y el siguiente la vuelve a ver.
 */
class CoverageContinuity(private val maxGapMs: Long = MAX_GAP_MS) {

    private var acknowledgedLosses = 0L
    private var lastPublishedAtMs: Long? = null

    enum class Break { NONE, SIGNAL_LOSS, GAP }

    /** Si hubo una pérdida o un hueco desde la última observación publicada (la pérdida primero). */
    @Synchronized
    fun check(losses: Long, observedAtElapsedMs: Long): Break = when {
        losses != acknowledgedLosses -> Break.SIGNAL_LOSS
        lastPublishedAtMs?.let { observedAtElapsedMs - it > maxGapMs } == true -> Break.GAP
        else -> Break.NONE
    }

    /** Al publicar una observación: queda como referencia para la siguiente. */
    @Synchronized
    fun acknowledge(losses: Long, observedAtElapsedMs: Long) {
        acknowledgedLosses = losses
        lastPublishedAtMs = observedAtElapsedMs
    }

    companion object {
        /** El mismo hueco máximo que [TemporalConfidence]: con pantalla apagada se sondea cada 10 s. */
        const val MAX_GAP_MS = 120_000L
    }
}
