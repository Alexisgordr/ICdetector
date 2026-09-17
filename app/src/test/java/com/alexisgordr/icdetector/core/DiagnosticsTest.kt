package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.*
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    private fun cell(report: HeuristicReport = HeuristicReport()) = CellData(
        isRegistered = true, networkType = "4G", cellId = "123", mnc = "07",
        tac = "42", dbm = -85, mcc = "214", radioTech = RadioTech.LTE,
        heuristicReport = report
    )

    @Test
    fun `temporal progress exposes all phases without early alarm`() {
        val temporal = TemporalConfidence(3)
        val suspicious = cell().copy(isSuspicious = true, suspiciousReason = "test")
        val one = temporal.apply(suspicious)
        val two = temporal.apply(suspicious)
        val three = temporal.apply(suspicious)

        assertEquals(1, one.temporalProgress.phase)
        assertEquals(2, two.temporalProgress.phase)
        assertEquals(3, three.temporalProgress.phase)
        assertFalse(one.isSuspicious)
        assertFalse(two.isSuspicious)
        assertTrue(three.isSuspicious)
    }

    @Test
    fun `clean cycle resets visible phase`() {
        val temporal = TemporalConfidence(3)
        temporal.apply(cell().copy(isSuspicious = true, suspiciousReason = "test"))
        val clean = temporal.apply(cell())
        assertEquals(0, clean.temporalProgress.phase)
        assertFalse(clean.temporalProgress.active)
    }

    @Test
    fun `diagnostics explain unavailable timing advance`() {
        val inputs = DiagnosticEngine.Inputs(
            neighborCount = 0, wifiActive = false, locationAvailable = false,
            historyWithLocation = 0, latencyAvailable = false,
            cipheringAvailable = false, previousBandAvailable = false,
            signalBaseline = null, rfFingerprint = null, rfStability = null, reputation = null
        )
        val diagnostic = DiagnosticEngine.explain(cell(), inputs).first { it.id == 6 }
        assertEquals(HeuristicStatus.NOT_EVALUATED, diagnostic.status)
        assertTrue(diagnostic.explanation.contains("Timing Advance"))
    }

    @Test
    fun `maturity reflects actual analyzer thresholds`() {
        val inputs = DiagnosticEngine.Inputs(
            neighborCount = 1, wifiActive = false, locationAvailable = true,
            historyWithLocation = 1, latencyAvailable = true, cipheringAvailable = true,
            previousBandAvailable = true,
            signalBaseline = SignalBaseline(20, -90.0, 3.0, -100, -80),
            rfFingerprint = CellRfFingerprint(30, -10.0, 1.0, 12.0, 2.0),
            rfStability = CellRfStability(4, emptyList(), emptyList()),
            reputation = CellReputation(5, 2, 1.0, 70)
        )
        val maturity = DiagnosticEngine.maturity(inputs)
        assertEquals(BaselineLevel.MATURE, maturity.signalLevel)
        assertEquals(BaselineLevel.USABLE, maturity.fingerprintLevel)
        assertEquals(BaselineLevel.USABLE, maturity.rfIdentityLevel)
        assertEquals(BaselineLevel.USABLE, maturity.reputationLevel)
    }
}
