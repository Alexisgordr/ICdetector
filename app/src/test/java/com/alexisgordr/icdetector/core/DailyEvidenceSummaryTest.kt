package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyEvidenceSummaryTest {
    @Test
    fun `frequent cell can accumulate fourteen independent days beyond five hundred rows`() {
        val buckets = (0 until 14).map { day ->
            DailyEvidenceBucket(97, day * DAY_MS, day * DAY_MS + 1_000)
        }

        val summary = DailyEvidenceSummary.from(buckets)

        assertEquals(1_358, summary.observations)
        assertEquals(42, summary.cappedObservations)
        assertEquals(14, summary.distinctDays)
        assertEquals(13 * 24L, summary.ageHours)
    }

    @Test
    fun `more samples in an existing day cannot reduce temporal evidence`() {
        val before = DailyEvidenceSummary.from(listOf(
            DailyEvidenceBucket(1, 0, 1_000),
            DailyEvidenceBucket(2, DAY_MS, DAY_MS + 1_000)
        ))
        val after = DailyEvidenceSummary.from(listOf(
            DailyEvidenceBucket(500, 0, 1_000),
            DailyEvidenceBucket(2, DAY_MS, DAY_MS + 1_000)
        ))

        assertEquals(before.distinctDays, after.distinctDays)
        assertEquals(3, before.cappedObservations)
        assertEquals(5, after.cappedObservations)
    }

    @Test
    fun `summary is independent of bucket order`() {
        val buckets = listOf(
            DailyEvidenceBucket(4, 2 * DAY_MS, 2 * DAY_MS + 2_000),
            DailyEvidenceBucket(3, 0, 2_000),
            DailyEvidenceBucket(8, DAY_MS, DAY_MS + 2_000)
        )

        assertEquals(DailyEvidenceSummary.from(buckets), DailyEvidenceSummary.from(buckets.reversed()))
    }

    @Test
    fun `empty evidence returns zero summary`() {
        assertEquals(DailyEvidenceSummary(), DailyEvidenceSummary.from(emptyList()))
    }

    @Test
    fun `large total does not dilute identities from bounded detailed scan`() {
        val buckets = (0 until 90).map { day ->
            DailyEvidenceBucket(if (day < 60) 97 else 96, day * DAY_MS, day * DAY_MS + 1_000)
        }
        val temporal = DailyEvidenceSummary.from(buckets)
        val knownPcis = DetailedRfEvidence.establishedValues(mapOf(179 to 500), 500)
        val knownArfcns = DetailedRfEvidence.establishedValues(mapOf(2850 to 500), 500)

        assertEquals(8_700, temporal.observations)
        assertEquals(90, temporal.distinctDays)
        assertEquals(setOf(179), knownPcis)
        assertEquals(setOf(2850), knownArfcns)
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1_000
    }
}
