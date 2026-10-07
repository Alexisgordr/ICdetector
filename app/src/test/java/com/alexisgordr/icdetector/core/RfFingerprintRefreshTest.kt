package com.alexisgordr.icdetector.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.10.9 — La huella cacheada se recalcula al moverse, como el baseline de potencia. */
class RfFingerprintRefreshTest {
    @Test fun `same area keeps the cached fingerprint`() {
        assertFalse(RfFingerprintRefresh.needed(computedWithFix = true, movedMeters = 0f))
        assertFalse(RfFingerprintRefresh.needed(computedWithFix = true, movedMeters = 149f))
    }

    @Test fun `moving 150 m recalculates it`() {
        assertTrue(RfFingerprintRefresh.needed(computedWithFix = true, movedMeters = 150f))
        assertTrue(RfFingerprintRefresh.needed(computedWithFix = true, movedMeters = 2_000f))
    }

    @Test fun `a fingerprint computed without GPS is recalculated once a fix appears`() {
        assertTrue(RfFingerprintRefresh.needed(computedWithFix = false, movedMeters = 0f))
    }

    @Test fun `losing GPS keeps the last fingerprint`() {
        assertFalse(RfFingerprintRefresh.needed(computedWithFix = true, movedMeters = null))
        assertFalse(RfFingerprintRefresh.needed(computedWithFix = false, movedMeters = null))
    }
}
