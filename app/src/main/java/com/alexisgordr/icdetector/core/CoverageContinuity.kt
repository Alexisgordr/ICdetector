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
 * hueco. La racha de confirmación de [TemporalConfidence] ya caducaba tras un hueco
 * (`maxContinuityGapMs`); ahora el resto del estado que depende de la continuidad usa el mismo
 * límite, [MAX_GAP_MS], que es la única definición del valor para los dos.
 *
 * [TemporalConfidence] tiene además `maxObservationStallMs` (30 s). No es un límite de
 * continuidad: es la salida de emergencia para aceptar una lectura cuando el módem repite un
 * timestamp congelado, y no rompe ninguna racha.
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
        /**
         * Hueco máximo sin lecturas para seguir considerando seguidas dos observaciones, también
         * para la racha de [TemporalConfidence]. Con la pantalla apagada se lee cada 10 s: 2
         * minutos son doce lecturas perdidas seguidas, mucho más que un fallo puntual del módem,
         * y bastante menos que un reposo de Doze, que es justo lo que hay que detectar.
         */
        const val MAX_GAP_MS = 120_000L
    }
}
