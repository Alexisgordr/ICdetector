package com.alexisgordr.icdetector.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsFixContinuityTest {
    private fun fix(lat: Double, lon: Double, seconds: Long, accuracy: Float = 20f) =
        GpsFixContinuity.Fix(lat, lon, seconds * 1_000L, accuracy)

    @Test
    fun `isolated 18 km excursion from forensic case is rejected`() {
        val gate = GpsFixContinuity()
        val home = fix(42.826849152334034, -1.6458487138152122, 0)
        val outlier = fix(42.74115408305079, -1.8401442468166351, 350, 69.38776f)
        val backHome = fix(42.82696830108762, -1.6458604484796524, 511)

        assertFalse(gate.accept(home, outlier))
        assertTrue(gate.accept(home, backHome))
    }

    @Test
    fun `cached outlier cannot confirm itself`() {
        val gate = GpsFixContinuity()
        val home = fix(42.826849, -1.645849, 0)
        val outlier = fix(42.741154, -1.840144, 350, 69f)

        assertFalse(gate.accept(home, outlier))
        assertFalse(gate.accept(home, outlier))
        assertFalse(gate.accept(home, outlier))
    }

    @Test
    fun `two distinct coherent fixes allow high speed train`() {
        val gate = GpsFixContinuity()
        val reference = fix(42.826849, -1.645849, 0)
        val trainFirst = fix(42.826849, -1.560800, 100)
        val trainSecond = fix(42.826849, -1.552300, 110)

        assertFalse(gate.accept(reference, trainFirst))
        assertTrue(gate.accept(reference, trainSecond))
    }

    @Test
    fun `ordinary road movement remains immediate`() {
        val gate = GpsFixContinuity()
        val reference = fix(42.826849, -1.645849, 0)
        val road = fix(42.826849, -1.628000, 60)

        assertTrue(gate.accept(reference, road))
    }
}
