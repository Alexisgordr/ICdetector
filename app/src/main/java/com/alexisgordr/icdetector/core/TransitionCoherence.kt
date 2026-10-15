package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellLocationSample
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.TransitionCoherenceResult
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * H16 — Coherencia de transición celular.
 *
 * No presupone una distancia máxima entre antenas. Decide si el salto observado es compatible
 * con el desplazamiento REAL del teléfono y con las zonas donde el propio dispositivo aprendió
 * ambas celdas. Ante GPS impreciso, poco historial o una pausa larga, se abstiene.
 *
 * ── v2.5: LOS ATAJOS YA NO GANAN A LA GEOMETRÍA ─────────────────────────────────────────────
 *
 * Hasta v2.4 la primera comprobación de `evaluate()` era esta:
 *
 * ```
 * if (input.destinationWasNeighbor) return passed(...)   // sin mirar la geometría
 * ```
 *
 * Es decir: si la celda destino aparecía en la lista de vecinas antes del salto, H16 devolvía
 * PASSED sin llegar a comparar las zonas aprendidas. El razonamiento original era sensato —una
 * celda ya visible es una transición RF normal— pero deja fuera justo el ataque que H16 existe
 * para cazar: **un IMSI-catcher local aparece en la lista de vecinas.** Emite ahí mismo, el módem
 * lo ve en `allCellInfo` antes de engancharse, y con ello se saltaba el test entero.
 *
 * Lo mismo valía para el atajo de `priorTrustedTransitions >= 2`: una ruta aprendida dejaba de
 * revisarse aunque la geometría pasara a contradecirla.
 *
 * Desde v2.5 la geometría se calcula PRIMERO, y los dos atajos solo se aplican si no hay una
 * contradicción geométrica sobre baselines maduros. Un atajo sigue sirviendo para lo que servía
 * —callar ante el trayecto de todos los días y ante celdas sin historial suficiente— pero ya no
 * puede tapar una imposibilidad física.
 *
 * El coste es nulo en la práctica: la geometría solo se calcula cuando ambos extremos tienen
 * baseline maduro, que es exactamente cuando antes también se iba a calcular en la rama larga.
 */
object TransitionCoherence {
    private const val MAX_GPS_ACCURACY_M = 75f
    private const val MAX_TRANSITION_SECONDS = 120L
    private const val MIN_SAMPLES_PER_CELL = 4
    private const val MAX_STATIONARY_MOVEMENT_M = 1_000.0
    private const val MIN_REMOTE_CENTERS_M = 10_000.0
    private const val MIN_UNEXPLAINED_GAP_M = 5_000.0

    /**
     * 3.0 — Un extremo del handover: el instante monotónico de su instantánea
     * (SystemClock.elapsedRealtime) y el del fix GPS que llevaba (Location.elapsedRealtimeNanos), o
     * null si no tenía ninguno. Los dos relojes son el mismo, así que la edad del fix es comparable.
     */
    data class Endpoint(val snapshotElapsedMs: Long, val fixElapsedRealtimeNanos: Long?)

    data class Input(
        val fromIdentity: String,
        val toIdentity: String,
        val elapsedSeconds: Long,
        /** 3.0 — Extremos con su fix: se validan antes de usar [deviceDistanceMeters]. */
        val previous: Endpoint,
        val current: Endpoint,
        val deviceDistanceMeters: Double?,
        /**
         * 3.0 — Posición del fix de la celda nueva. Separa «quieto en la zona de la celda anterior y
         * aparece una celda aprendida lejos» (lo que H16 persigue) de «a mitad de camino entre dos
         * celdas grandes» (un handover normal): el desplazamiento entre dos fixes no lo distingue.
         */
        val currentPosition: CellLocationSample?,
        val previousAccuracyMeters: Float?,
        val currentAccuracyMeters: Float?,
        val fromSamples: List<CellLocationSample>,
        val toSamples: List<CellLocationSample>,
        val priorTrustedTransitions: Int,
        val destinationWasNeighbor: Boolean
    )

    /** Geometría del salto, solo calculable cuando ambos extremos tienen baseline maduro. */
    private data class Geometry(
        val centerDistance: Double,
        val unexplainedGap: Double,
        val inconsistent: Boolean,
        /**
         * 3.0 — Salto lejano con poco movimiento, pero el teléfono NO está en la zona aprendida de
         * la celda anterior: no hay nada que contradecir. Motivo del N/A; null si no aplica.
         */
        val outsideOriginReason: String? = null
    )

    fun evaluate(input: Input): TransitionCoherenceResult {
        fun unavailable(reason: String) = TransitionCoherenceResult(
            explanation = "N/A: $reason",
            fromIdentity = input.fromIdentity,
            toIdentity = input.toIdentity,
            deviceDistanceMeters = input.deviceDistanceMeters?.roundToInt(),
            priorTrustedTransitions = input.priorTrustedTransitions
        )

        if (input.fromIdentity == input.toIdentity) return unavailable("no hubo cambio de celda.")
        if (input.elapsedSeconds !in 0..MAX_TRANSITION_SECONDS) {
            return unavailable("el intervalo entre observaciones no permite atribuir el desplazamiento al handover.")
        }
        // 3.0 — Antes de la distancia, la geometría y los atajos (vecina visible, ruta aprendida):
        // sin un fix propio y reciente en cada extremo no hay desplazamiento medido. Una posición
        // reutilizada daría «0 m» sin demostrar que el móvil estuviera quieto.
        gpsProblem(input.previous, input.current)?.let { reason ->
            return unavailable(reason).copy(deviceDistanceMeters = null)
        }
        val moved = input.deviceDistanceMeters
            ?: return unavailable("faltan dos posiciones GPS contemporáneas.")
        if ((input.previousAccuracyMeters ?: Float.MAX_VALUE) > MAX_GPS_ACCURACY_M ||
            (input.currentAccuracyMeters ?: Float.MAX_VALUE) > MAX_GPS_ACCURACY_M
        ) {
            return unavailable("la precisión GPS es insuficiente (se exige ≤ ${MAX_GPS_ACCURACY_M.toInt()} m).")
        }

        // v2.5 — la geometría va primero: ningún atajo puede saltarse una contradicción física.
        val geometry = geometryOf(input, moved)
        val contradiction = geometry?.inconsistent == true

        if (!contradiction) {
            if (input.destinationWasNeighbor) {
                return passed(
                    input, geometry?.centerDistance, moved,
                    "La celda destino ya era vecina visible antes del handover.",
                    learnable = geometry != null
                )
            }
            if (input.priorTrustedTransitions >= 2) {
                return passed(
                    input, geometry?.centerDistance, moved,
                    "Transición aprendida previamente (${input.priorTrustedTransitions} observaciones coherentes).",
                    learnable = true
                )
            }
        }

        geometry?.outsideOriginReason?.let { return unavailable(it).copy(centerDistanceMeters = geometry.centerDistance.roundToInt()) }

        if (geometry == null) {
            return unavailable(
                "baseline geográfico en aprendizaje " +
                    "(${input.fromSamples.size}/$MIN_SAMPLES_PER_CELL origen, " +
                    "${input.toSamples.size}/$MIN_SAMPLES_PER_CELL destino)."
            )
        }

        return if (geometry.inconsistent) {
            TransitionCoherenceResult(
                status = HeuristicStatus.FAILED,
                explanation = "Handover incoherente: el móvil recorrió ${moved.roundToInt()} m, " +
                    "pero las zonas aprendidas quedan a ${geometry.centerDistance.roundToInt()} m y no se solapan." +
                    if (input.destinationWasNeighbor) " La celda destino se anunciaba como vecina." else "",
                fromIdentity = input.fromIdentity,
                toIdentity = input.toIdentity,
                centerDistanceMeters = geometry.centerDistance.roundToInt(),
                deviceDistanceMeters = moved.roundToInt(),
                priorTrustedTransitions = input.priorTrustedTransitions
            )
        } else {
            passed(
                input, geometry.centerDistance, moved,
                "Desplazamiento compatible con las zonas históricas " +
                    "(${moved.roundToInt()} m recorridos; centros a ${geometry.centerDistance.roundToInt()} m).",
                learnable = true
            )
        }
    }

    /**
     * 3.0 — ¿Tiene cada extremo su propio fix GPS, reciente respecto a su instantánea? Devuelve el
     * motivo concreto para abstenerse, o null si el desplazamiento se puede medir. Edad máxima:
     * [GpsFixAge.LIVE_MAX_AGE_MS], el mismo criterio de los fixes en vivo. Dos fixes distintos con
     * las mismas coordenadas son válidos (puede ser reposo real); el mismo fix dos veces no.
     */
    fun gpsProblem(previous: Endpoint, current: Endpoint): String? {
        fun check(end: Endpoint, side: String): String? {
            val nanos = end.fixElapsedRealtimeNanos ?: return "no hay fix GPS en la observación de $side."
            val fixMs = nanos / 1_000_000L
            if (nanos <= 0L || fixMs > end.snapshotElapsedMs) return "el fix GPS de $side tiene una marca de tiempo inválida o futura."
            if (!GpsFixAge.isRecent(end.snapshotElapsedMs, fixMs, GpsFixAge.LIVE_MAX_AGE_MS)) {
                return "el fix GPS de $side tiene más de ${GpsFixAge.LIVE_MAX_AGE_MS / 1_000} s; una posición reutilizada no demuestra que el móvil estuviera quieto."
            }
            return null
        }
        check(previous, SIDE_PREVIOUS)?.let { return it }
        check(current, SIDE_CURRENT)?.let { return it }
        val before = previous.fixElapsedRealtimeNanos!!
        val after = current.fixElapsedRealtimeNanos!!
        if (after == before) return "las dos observaciones usan el mismo fix GPS; no miden ningún desplazamiento."
        if (after < before) return "el fix GPS de la celda nueva es anterior al de la celda anterior."
        return null
    }

    private const val SIDE_PREVIOUS = "la celda anterior"
    private const val SIDE_CURRENT = "la celda nueva"

    /**
     * Cuánto suma una transición al aprendizaje de rutas fiables: solo un PASSED apto para aprender.
     * Un N/A (incluido el de GPS insuficiente) nunca suma.
     */
    fun trustedIncrement(result: TransitionCoherenceResult): Int =
        if (result.status == HeuristicStatus.PASSED && result.eligibleForLearning) 1 else 0

    /**
     * Contraste entre las zonas aprendidas de ambos extremos y el desplazamiento real medido.
     * Devuelve null cuando alguno de los dos extremos no tiene muestras suficientes: sin baseline
     * no hay nada que contradecir, y abstenerse es lo correcto.
     */
    private fun geometryOf(input: Input, moved: Double): Geometry? {
        if (input.fromSamples.size < MIN_SAMPLES_PER_CELL ||
            input.toSamples.size < MIN_SAMPLES_PER_CELL
        ) return null

        // La matemática del centroide vive en CellGeometry y solo allí: la pestaña de geometría
        // enseña exactamente los mismos números que usa esta heurística para decidir.
        val from = CellGeometry.profile(input.fromSamples)
        val to = CellGeometry.profile(input.toSamples)
        val centers = CellGeometry.distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        // Distancia que queda sin explicar tras descontar el radio P90 aprendido de ambas zonas.
        val unexplainedGap = (centers - from.radiusP90 - to.radiusP90).coerceAtLeast(0.0)
        // Margen deliberadamente generoso: movimiento medido + errores GPS + 1,5 km de borde RF.
        val measurementMargin = moved +
            (input.previousAccuracyMeters ?: 0f) + (input.currentAccuracyMeters ?: 0f) + 1_500.0
        val farGap = max(MIN_UNEXPLAINED_GAP_M, measurementMargin * 3.0)
        val remoteJump = moved <= MAX_STATIONARY_MOVEMENT_M &&
            centers >= MIN_REMOTE_CENTERS_M &&
            unexplainedGap > farGap
        if (!remoteJump) return Geometry(centers, unexplainedGap, inconsistent = false)

        // 3.0 — Dónde está el teléfono respecto a las dos zonas. Las zonas son centros de
        // OBSERVACIÓN (donde el móvil estuvo enganchado), no la cobertura: dos macroceldas vistas
        // solo desde casa y desde el trabajo cubren juntas la carretera, y un handover a mitad de
        // camino tiene poco movimiento y centros lejanos igual que el ataque. Solo es incoherente
        // si el teléfono está en (o junto a) la zona de la celda anterior y la de la nueva queda
        // lejos de él: quieto en casa y aparece una celda aprendida a kilómetros.
        val here = input.currentPosition
            ?: return Geometry(centers, unexplainedGap, inconsistent = false, outsideOriginReason = NO_POSITION)
        val fromOutside = CellGeometry.distanceMeters(here.latitude, here.longitude, from.latitude, from.longitude) - from.radiusP90
        val toOutside = CellGeometry.distanceMeters(here.latitude, here.longitude, to.latitude, to.longitude) - to.radiusP90
        val atOrigin = fromOutside <= measurementMargin
        if (atOrigin && toOutside > farGap) return Geometry(centers, unexplainedGap, inconsistent = true)
        val reason = when {
            !atOrigin && toOutside > measurementMargin -> OUTSIDE_BOTH
            !atOrigin -> AT_DESTINATION
            else -> DESTINATION_NOT_FAR
        }
        return Geometry(centers, unexplainedGap, inconsistent = false, outsideOriginReason = reason)
    }

    internal const val NO_POSITION = "falta la posición del fix de la celda nueva."
    internal const val OUTSIDE_BOTH =
        "la posición está fuera de las zonas aprendidas de ambas celdas; entre dos celdas grandes ese salto puede ser normal."
    internal const val AT_DESTINATION =
        "la posición está en la zona aprendida de la celda nueva, no en la de la anterior."
    internal const val DESTINATION_NOT_FAR =
        "la zona aprendida de la celda nueva no queda lo bastante lejos de la posición."

    private fun passed(input: Input, centers: Double?, moved: Double, detail: String, learnable: Boolean) =
        TransitionCoherenceResult(
            status = HeuristicStatus.PASSED,
            explanation = detail,
            fromIdentity = input.fromIdentity,
            toIdentity = input.toIdentity,
            centerDistanceMeters = centers?.roundToInt(),
            deviceDistanceMeters = moved.roundToInt(),
            priorTrustedTransitions = input.priorTrustedTransitions,
            eligibleForLearning = learnable
        )
}
