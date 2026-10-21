package com.alexisgordr.icdetector.core

import kotlin.math.*

/**
 * Keeps isolated GNSS jumps away from geographic heuristics without rejecting sustained
 * high-speed travel. Fast movement is provisional until a second, distinct fix confirms a
 * continuous trajectory. Re-reading the same cached fix never advances confirmation.
 */
class GpsFixContinuity(
    private val immediateSpeedKmh: Double = 180.0,
    private val maximumConfirmedSpeedKmh: Double = 400.0,
    private val confirmationsRequired: Int = 2
) {
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val timeMillis: Long,
        val accuracyMeters: Float
    )

    private data class Pending(val first: Fix, val latest: Fix, val confirmations: Int)

    private var pending: Pending? = null

    fun accept(reference: Fix?, candidate: Fix): Boolean {
        if (reference == null) {
            pending = null
            return true
        }

        val distanceFromReference = distanceMeters(reference, candidate)
        val referenceEnvelope = maxOf(100.0, (reference.accuracyMeters + candidate.accuracyMeters) * 2.0)
        if (candidate.timeMillis <= reference.timeMillis) {
            return distanceFromReference <= referenceEnvelope
        }

        val secondsFromReference = (candidate.timeMillis - reference.timeMillis) / 1_000.0
        val speedFromReference = distanceFromReference / secondsFromReference * 3.6

        // Returning to the accepted area disproves an outstanding excursion immediately.
        if (distanceFromReference <= referenceEnvelope || speedFromReference <= immediateSpeedKmh) {
            pending = null
            return true
        }

        val current = pending
        if (current == null) {
            pending = Pending(candidate, candidate, 1)
            return false
        }

        // getLastKnownLocation may return the exact same fix repeatedly.
        if (candidate.timeMillis <= current.latest.timeMillis) return false

        val secondsFromPending = (candidate.timeMillis - current.latest.timeMillis) / 1_000.0
        val segmentDistance = distanceMeters(current.latest, candidate)
        val segmentSpeed = segmentDistance / secondsFromPending * 3.6
        val firstJump = distanceMeters(reference, current.first)
        val stillAwayFromReference = distanceFromReference >= firstJump * 0.5
        val accuracyAllowance = (current.latest.accuracyMeters + candidate.accuracyMeters) * 2.0
        val maximumSegment = maximumConfirmedSpeedKmh / 3.6 * secondsFromPending + accuracyAllowance
        val coherent = stillAwayFromReference && segmentDistance <= maximumSegment &&
            segmentSpeed <= maximumConfirmedSpeedKmh

        if (!coherent) {
            pending = Pending(candidate, candidate, 1)
            return false
        }

        val confirmations = current.confirmations + 1
        if (confirmations >= confirmationsRequired) {
            pending = null
            return true
        }
        pending = Pending(current.first, candidate, confirmations)
        return false
    }

    /** 3.1.0 (#47) — El haversine de [CellGeometry.distanceMeters], sin copia propia. */
    private fun distanceMeters(a: Fix, b: Fix): Double =
        CellGeometry.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
}
