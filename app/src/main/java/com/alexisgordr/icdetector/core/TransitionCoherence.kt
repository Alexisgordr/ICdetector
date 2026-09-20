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

    data class Input(
        val fromIdentity: String,
        val toIdentity: String,
        val elapsedSeconds: Long,
        val deviceDistanceMeters: Double?,
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
        val inconsistent: Boolean
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
        val inconsistent = moved <= MAX_STATIONARY_MOVEMENT_M &&
            centers >= MIN_REMOTE_CENTERS_M &&
            unexplainedGap > max(MIN_UNEXPLAINED_GAP_M, measurementMargin * 3.0)
        return Geometry(centers, unexplainedGap, inconsistent)
    }

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
