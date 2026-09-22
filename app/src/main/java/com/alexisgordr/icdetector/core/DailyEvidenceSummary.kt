package com.alexisgordr.icdetector.core

data class DailyEvidenceBucket(
    val observations: Int,
    val firstTimestampMs: Long?,
    val lastTimestampMs: Long?
)

data class DailyEvidenceSummary(
    val observations: Int = 0,
    val cappedObservations: Int = 0,
    val distinctDays: Int = 0,
    val ageHours: Long = 0
) {
    companion object {
        fun from(buckets: Iterable<DailyEvidenceBucket>, dailyCap: Int = 3): DailyEvidenceSummary {
            require(dailyCap > 0)
            var observations = 0
            var capped = 0
            var days = 0
            var first: Long? = null
            var last: Long? = null
            buckets.forEach { bucket ->
                if (bucket.observations <= 0) return@forEach
                observations += bucket.observations
                capped += minOf(bucket.observations, dailyCap)
                days++
                bucket.firstTimestampMs?.let { first = minOf(first ?: it, it) }
                bucket.lastTimestampMs?.let { last = maxOf(last ?: it, it) }
            }
            return DailyEvidenceSummary(
                observations = observations,
                cappedObservations = capped,
                distinctDays = days,
                ageHours = if (first != null && last != null)
                    ((last!! - first!!) / 3_600_000L).coerceAtLeast(0) else 0
            )
        }
    }
}

object DetailedRfEvidence {
    fun establishedValues(counts: Map<Int, Int>, scannedObservations: Int): Set<Int> = counts
        .filterValues { count ->
            count >= 2 && (scannedObservations == 0 || count.toDouble() / scannedObservations >= 0.15)
        }
        .keys
}
