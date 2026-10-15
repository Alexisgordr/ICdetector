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
    fun `two independent strong failures confirm in two observations`() {
        val temporal = TemporalConfidence(3)
        val report = HeuristicReport(
            mobileCellId = HeuristicStatus.FAILED,
            rfStability = HeuristicStatus.FAILED
        )
        val suspicious = cell(report).copy(isSuspicious = true, suspiciousReason = "strong pair")

        val first = temporal.apply(suspicious, observationToken = 10_000L)
        val second = temporal.apply(suspicious, observationToken = 12_000L)

        assertEquals(2, first.temporalProgress.required)
        assertFalse(first.isSuspicious)
        assertTrue(second.isSuspicious)
    }

    @Test
    fun `one strong failure still requires three observations`() {
        val temporal = TemporalConfidence(3)
        val report = HeuristicReport(mobileCellId = HeuristicStatus.FAILED)
        val suspicious = cell(report).copy(isSuspicious = true, suspiciousReason = "single strong")

        temporal.apply(suspicious, observationToken = 10_000L)
        val second = temporal.apply(suspicious, observationToken = 12_000L)

        assertEquals(3, second.temporalProgress.required)
        assertFalse(second.isSuspicious)
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
    fun `cached deliveries without a token never advance the streak`() {
        val temporal = TemporalConfidence(3)
        val suspicious = cell().copy(isSuspicious = true, suspiciousReason = "test")

        val results = (1..3).map {
            temporal.apply(suspicious, observationToken = null, isFreshDelivery = false)
        }

        results.forEach { assertEquals(0, it.temporalProgress.phase) }
        assertFalse(results.last().isSuspicious)
    }

    @Test
    fun `cached repeats without a token do not complete a fresh streak`() {
        val temporal = TemporalConfidence(3)
        val suspicious = cell().copy(isSuspicious = true, suspiciousReason = "test")

        temporal.apply(suspicious, observationToken = null)
        temporal.apply(suspicious, observationToken = null, isFreshDelivery = false)
        val cached = temporal.apply(suspicious, observationToken = null, isFreshDelivery = false)
        assertEquals(1, cached.temporalProgress.phase)
        assertFalse(cached.isSuspicious)

        temporal.apply(suspicious, observationToken = null)
        val third = temporal.apply(suspicious, observationToken = null)
        assertEquals(3, third.temporalProgress.phase)
        assertTrue(third.isSuspicious)
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
            previousBandAvailable = false,
            signalBaseline = null, rfFingerprint = null, rfStability = null, reputation = null
        )
        val diagnostic = DiagnosticEngine.explain(cell(), inputs).first { it.id == 6 }
        assertEquals(HeuristicStatus.NOT_EVALUATED, diagnostic.status)
        assertTrue(diagnostic.explanation.contains("Timing Advance"))
    }

    @Test
    fun `H3 diagnostics say why it passed, failed or abstained`() {
        val inputs = DiagnosticEngine.Inputs(
            neighborCount = 2, wifiActive = false, locationAvailable = false,
            historyWithLocation = 0, latencyAvailable = false,
            previousBandAvailable = false,
            signalBaseline = null, rfFingerprint = null, rfStability = null, reputation = null
        )
        fun h3(report: HeuristicReport) = DiagnosticEngine.explain(cell(report), inputs).first { it.id == 3 }.explanation
        assertEquals(DiagnosticEngine.H3_PASSED_EXPLANATION, h3(HeuristicReport(mccConsistency = HeuristicStatus.PASSED)))
        assertEquals(DiagnosticEngine.H3_FAILED_EXPLANATION, h3(HeuristicReport(mccConsistency = HeuristicStatus.FAILED)))
        assertEquals(
            "N/A: PLMN extranjero con vecinas coherentes (frontera).",
            h3(HeuristicReport(mccAbstention = MccAbstention.BORDER))
        )
        assertEquals("N/A: ${DiagnosticEngine.H3_UNKNOWN_ABSTENTION}.", h3(HeuristicReport()))
        // 3.0 (corte de campaña) — Celda CSG: H1/H2/H5/H6/H7 explican la abstención por la CSG, las demás no.
        val csg = DiagnosticEngine.explain(cell(HeuristicReport(csgCell = true)), inputs)
        listOf(1, 2, 5, 6, 7).forEach { id -> assertEquals(DiagnosticEngine.CSG_NA_EXPLANATION, csg.first { it.id == id }.explanation) }
        assertTrue(csg.first { it.id == 4 }.explanation != DiagnosticEngine.CSG_NA_EXPLANATION)
        // 3.0 (corte de campaña) — H11 con un fix viejo dice por qué se abstuvo.
        assertEquals(DiagnosticEngine.H11_FIX_TOO_OLD_EXPLANATION,
            DiagnosticEngine.explain(cell(HeuristicReport(mobileCellIdFixTooOld = true)), inputs).first { it.id == 11 }.explanation)
        // 3.0 (corte de campaña) — H9 reservada: siempre la misma explicación.
        val h9 = DiagnosticEngine.explain(cell(HeuristicReport()), inputs).first { it.id == 9 }
        assertEquals(HeuristicStatus.NOT_EVALUATED, h9.status)
        assertEquals(DiagnosticEngine.H9_NA_EXPLANATION, h9.explanation)
    }

    @Test
    fun `maturity reflects actual analyzer thresholds`() {
        val inputs = DiagnosticEngine.Inputs(
            neighborCount = 1, wifiActive = false, locationAvailable = true,
            historyWithLocation = 1, latencyAvailable = true, previousBandAvailable = true,
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

    // #18 — La pantalla distingue "faltan días" de "falta GPS o muestras cerca". El dato solo se
    // pasa a la madurez; los niveles y las líneas base no cambian.
    @Test
    fun `maturity carries the trust gate for display only`() {
        fun inputs(gate: Boolean?) = DiagnosticEngine.Inputs(
            neighborCount = 0, wifiActive = false, locationAvailable = false,
            historyWithLocation = 0, latencyAvailable = false,
            previousBandAvailable = false,
            signalBaseline = null, rfFingerprint = null, rfStability = null, reputation = null,
            trustGateMet = gate
        )
        assertEquals(false, DiagnosticEngine.maturity(inputs(false)).trustGateMet)
        assertEquals(true, DiagnosticEngine.maturity(inputs(true)).trustGateMet)
        assertNull(DiagnosticEngine.maturity(inputs(null)).trustGateMet)
        val waiting = DiagnosticEngine.maturity(inputs(true))
        assertEquals(BaselineLevel.EMPTY, waiting.signalLevel)
        assertEquals(DiagnosticEngine.maturity(inputs(false)).copy(trustGateMet = true), waiting)
    }

    @Test
    fun `alternating identities reset the streak unless they share a confirmation key`() {
        val a = cell().copy(isSuspicious = true, suspiciousReason = "test")
        val b = a.copy(cellId = "124")

        val perCell = TemporalConfidence(3)
        val plain = listOf(a, b, a, b).map { perCell.apply(it) }
        assertTrue(plain.all { it.temporalProgress.phase == 1 })

        val grouped = TemporalConfidence(3)
        val key = PingPongRule.CONFIRMATION_KEY_PREFIX + "A,B"
        val results = listOf(a, b, a).map { grouped.apply(it, confirmationKey = key) }
        assertEquals(listOf(1, 2, 3), results.map { it.temporalProgress.phase })
        assertTrue(results.last().isSuspicious)
    }
}
