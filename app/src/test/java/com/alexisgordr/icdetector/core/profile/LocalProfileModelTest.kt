package com.alexisgordr.icdetector.core.profile

import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0 — Perfil local: contexto, métricas y compatibilidad. */
class LocalProfileModelTest {

    private fun profile(device: String = "google/pixel 8", radio: RadioTech = RadioTech.LTE, version: Int = LocalProfile.ALGORITHM_VERSION) =
        MetricProfile("214-07-1-100-LTE", ProfileMetric.SIGNAL_DBM, radio, device, version, 1, -80.0, 9.0,
            40, 0, 40, 0, 5, "2026-10-10", 3, -80.0, 1L, 2L, 2L, ProfileMaturity.MATURE, null, 2L)

    @Test fun `the context is the full identity plus the radio`() {
        val c = ProfileContext("214", "07", "1", "100", RadioTech.LTE)
        assertEquals("214-07-1-100-LTE", c.key)
        assertTrue(c.isComplete)
        assertFalse(c.copy(cellId = "N/A").isComplete)
        assertFalse(c.copy(tac = "").isComplete)
        assertFalse(c.copy(radio = RadioTech.UNKNOWN).isComplete)
        // LTE y NR de la misma celda son contextos distintos.
        assertTrue(c.key != c.copy(radio = RadioTech.NR).key)
    }

    @Test fun `a profile learned on another phone, radio or algorithm abstains`() {
        val p = profile()
        assertNull(ProfileCompatibility.check(p, "google/pixel 8", RadioTech.LTE))
        assertEquals(AbstainReason.INCOMPATIBLE_DEVICE, ProfileCompatibility.check(p, "samsung/sm-s911b", RadioTech.LTE))
        assertEquals(AbstainReason.INCOMPATIBLE_RADIO, ProfileCompatibility.check(p, "google/pixel 8", RadioTech.NR))
        assertEquals(AbstainReason.INCOMPATIBLE_ALGORITHM,
            ProfileCompatibility.check(profile(version = LocalProfile.ALGORITHM_VERSION + 1), "google/pixel 8", RadioTech.LTE))
    }

    @Test fun `the device model is normalised and carries no personal data`() {
        assertEquals("google/pixel 8", ProfileCompatibility.deviceModel("Google", " Pixel 8 "))
        assertEquals("/", ProfileCompatibility.deviceModel(null, null))
    }

    @Test fun `metrics reject absent and impossible values`() {
        assertTrue(ProfileMetric.SIGNAL_DBM.isValid(-90))
        assertFalse(ProfileMetric.SIGNAL_DBM.isValid(null))
        assertFalse(ProfileMetric.SIGNAL_DBM.isValid(Int.MAX_VALUE))
        assertFalse(ProfileMetric.SIGNAL_DBM.isValid(-999))
        assertTrue(ProfileMetric.SIGNAL_RSRQ.isValid(-11))
        assertFalse(ProfileMetric.SIGNAL_RSRQ.isValid(-60))
    }

    @Test fun `maturity never validates the algorithm`() {
        assertFalse(LocalProfile.ALGORITHM_VALIDATED)
    }
}
