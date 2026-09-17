package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellLocationSample
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.TransitionCoherenceResult
import kotlin.math.*

/**
 * H16 — Coherencia de transición celular.
 *
 * No presupone una distancia máxima entre antenas. Decide si el salto observado es compatible
 * con el desplazamiento REAL del teléfono y con las zonas donde el propio dispositivo aprendió
 * ambas celdas. Ante GPS impreciso, poco historial o una pausa larga, se abstiene.
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
            (input.currentAccuracyMeters ?: Float.MAX_VALUE) > MAX_GPS_ACCURACY_M) {
            return unavailable("la precisión GPS es insuficiente (se exige ≤ ${MAX_GPS_ACCURACY_M.toInt()} m).")
        }

        if (input.destinationWasNeighbor) {
            return passed(
                input, null, moved, "La celda destino ya era vecina visible antes del handover.",
                learnable = input.fromSamples.size >= MIN_SAMPLES_PER_CELL && input.toSamples.size >= MIN_SAMPLES_PER_CELL
            )
        }
        if (input.priorTrustedTransitions >= 2) {
            return passed(
                input, null, moved,
                "Transición aprendida previamente (${input.priorTrustedTransitions} observaciones coherentes).",
                learnable = true
            )
        }
        if (input.fromSamples.size < MIN_SAMPLES_PER_CELL || input.toSamples.size < MIN_SAMPLES_PER_CELL) {
            return unavailable(
                "baseline geográfico en aprendizaje " +
                    "(${input.fromSamples.size}/$MIN_SAMPLES_PER_CELL origen, " +
                    "${input.toSamples.size}/$MIN_SAMPLES_PER_CELL destino)."
            )
        }

        val from = profile(input.fromSamples)
        val to = profile(input.toSamples)
        val centers = distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        // Distancia que queda sin explicar tras descontar el radio P90 aprendido de ambas zonas.
        val unexplainedGap = (centers - from.radiusP90 - to.radiusP90).coerceAtLeast(0.0)
        // Margen deliberadamente generoso: movimiento medido + errores GPS + 1,5 km de borde RF.
        val measurementMargin = moved +
            (input.previousAccuracyMeters ?: 0f) + (input.currentAccuracyMeters ?: 0f) + 1_500.0
        val inconsistent = moved <= MAX_STATIONARY_MOVEMENT_M &&
            centers >= MIN_REMOTE_CENTERS_M &&
            unexplainedGap > max(MIN_UNEXPLAINED_GAP_M, measurementMargin * 3.0)

        return if (inconsistent) {
            TransitionCoherenceResult(
                status = HeuristicStatus.FAILED,
                explanation = "Handover incoherente: el móvil recorrió ${moved.roundToInt()} m, " +
                    "pero las zonas aprendidas quedan a ${centers.roundToInt()} m y no se solapan.",
                fromIdentity = input.fromIdentity,
                toIdentity = input.toIdentity,
                centerDistanceMeters = centers.roundToInt(),
                deviceDistanceMeters = moved.roundToInt(),
                priorTrustedTransitions = input.priorTrustedTransitions
            )
        } else {
            passed(
                input, centers, moved,
                "Desplazamiento compatible con las zonas históricas " +
                    "(${moved.roundToInt()} m recorridos; centros a ${centers.roundToInt()} m).",
                learnable = true
            )
        }
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

    private data class Profile(val latitude: Double, val longitude: Double, val radiusP90: Double)

    private fun profile(samples: List<CellLocationSample>): Profile {
        // La mediana resiste mucho mejor un fix aislado erróneo que AVG/MIN/MAX en SQL.
        val lat = median(samples.map { it.latitude })
        val lon = median(samples.map { it.longitude })
        val radii = samples.map { distanceMeters(lat, lon, it.latitude, it.longitude) }.sorted()
        val p90Index = ceil(radii.size * 0.90).toInt().coerceIn(1, radii.size) - 1
        return Profile(lat, lon, radii[p90Index])
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) *
            cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
