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
    fun `duplicate modem observation cannot advance confirmation`() {
        val temporal = TemporalConfidence(3)
        val suspicious = cell().copy(isSuspicious = true, suspiciousReason = "test")

        val first = temporal.apply(suspicious, observationToken = 10_000L)
        val duplicate = temporal.apply(suspicious, observationToken = 10_000L)
        val older = temporal.apply(suspicious, observationToken = 9_000L)
        val tooSoon = temporal.apply(suspicious, observationToken = 11_000L)
        val second = temporal.apply(suspicious, observationToken = 12_000L)

        assertEquals(1, first.temporalProgress.phase)
        assertEquals(1, duplicate.temporalProgress.phase)
        assertEquals(1, older.temporalProgress.phase)
        assertEquals(1, tooSoon.temporalProgress.phase)
        assertEquals(2, second.temporalProgress.phase)
        assertFalse(duplicate.isSuspicious)
        assertFalse(second.isSuspicious)
    }

    @Test
    fun `three distinct modem observations confirm anomaly`() {
        val temporal = TemporalConfidence(3)
        val suspicious = cell().copy(isSuspicious = true, suspiciousReason = "test")

        temporal.apply(suspicious, observationToken = 10_000L)
        temporal.apply(suspicious, observationToken = 12_000L)
        val confirmed = temporal.apply(suspicious, observationToken = 14_000L)

        assertEquals(3, confirmed.temporalProgress.phase)
        assertTrue(confirmed.isSuspicious)
    }

    @Test
    fun `fresh delivery escapes a frozen modem timestamp after thirty seconds`() {
        var elapsed = 0L
        val temporal = TemporalConfidence(3, elapsedRealtimeMs = { elapsed })
        val suspicious = cell().copy(isSuspicious = true, suspiciousReason = "test")

        val first = temporal.apply(suspicious, observationToken = 10_000L)
        elapsed = 29_999L
        val stillFrozen = temporal.apply(suspicious, observationToken = 10_000L)
        elapsed = 30_000L
        val escaped = temporal.apply(suspicious, observationToken = 10_000L)

        assertEquals(1, first.temporalProgress.phase)
        assertEquals(1, stillFrozen.temporalProgress.phase)
        assertEquals(2, escaped.temporalProgress.phase)
    }

    @Test
    fun `cached fallback never escapes a frozen modem timestamp`() {
        var elapsed = 0L
        val temporal = TemporalConfidence(3, elapsedRealtimeMs = { elapsed })
        val suspicious = cell().copy(isSuspicious = true, suspiciousReason = "test")

        temporal.apply(suspicious, observationToken = 10_000L)
        elapsed = 120_000L
        val cached = temporal.apply(
            suspicious,
            observationToken = 10_000L,
            isFreshDelivery = false
        )

        assertEquals(1, cached.temporalProgress.phase)
        assertFalse(cached.isSuspicious)
    }

    @Test
    fun `emergency acceptance never lowers the token watermark`() {
        var elapsed = 0L
        val temporal = TemporalConfidence(3, elapsedRealtimeMs = { elapsed })
        val suspicious = cell().copy(isSuspicious = true, suspiciousReason = "test")

        temporal.apply(suspicious, observationToken = 10_000L)
        elapsed = 30_000L
        val escaped = temporal.apply(suspicious, observationToken = 1_000L)
        elapsed = 30_001L
        val apparentAdvance = temporal.apply(suspicious, observationToken = 2_000L)

        assertEquals(2, escaped.temporalProgress.phase)
        assertEquals(2, apparentAdvance.temporalProgress.phase)
        assertFalse(apparentAdvance.isSuspicious)
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
