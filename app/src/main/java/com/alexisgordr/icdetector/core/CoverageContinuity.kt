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

/**
 * 3.0 (G4) — Qué es un handover OBSERVADO, para H16/Topología y para los viajes de movilidad.
 *
 * Rompe la continuidad celular, y por tanto la siguiente celda es una referencia nueva y no un
 * salto observado desde la anterior:
 *  - una pérdida de servicio: lista sin celdas, modo avión o primaria declarada sin señal
 *    utilizable ([CollectionGeneration.lose] con pérdida de señal);
 *  - un hueco de más de [CoverageContinuity.MAX_GAP_MS] sin observaciones publicadas;
 *  - un reinicio del proceso o del teléfono sin continuidad demostrable.
 * NO la rompe una celda activa válida sin GPS, con movimiento desconocido o con Wi-Fi: el cambio de
 * celda sí se observó; solo la geometría (H16, distancia) no se puede evaluar.
 */
object HandoverReference {
    enum class Step {
        /** Todavía no había referencia. */
        FIRST,
        /** La misma servidora: sin transición. */
        SAME_CELL,
        /** Cambio de servidora observado sin interrupción: transición A→B real. */
        HANDOVER,
        /** Tras una interrupción: esta celda es la nueva referencia; no hay arista desde la anterior. */
        NEW_REFERENCE_AFTER_INTERRUPTION
    }

    fun step(previous: String?, current: String, continuous: Boolean): Step = when {
        previous == null -> Step.FIRST
        !continuous -> Step.NEW_REFERENCE_AFTER_INTERRUPTION
        previous == current -> Step.SAME_CELL
        else -> Step.HANDOVER
    }

    const val INTERRUPTION_EXPLANATION =
        "N/A: hubo una interrupción del servicio celular; esta celda es la nueva referencia, no un handover observado."
}
