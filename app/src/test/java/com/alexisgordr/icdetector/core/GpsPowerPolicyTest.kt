package com.alexisgordr.icdetector.core

import org.junit.Assert.*
import org.junit.Test

class GpsPowerPolicyTest {
    @Test fun `smart schedules from a successful fix and the periodic tick cannot run early`() {
        val p = GpsPowerPolicy()
        assertTrue(p.periodicProbeDue(LocationMode.INTELLIGENT, 0L))
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 0L))
        p.onFreshFix(1_000L)
        assertFalse(p.periodicProbeDue(LocationMode.INTELLIGENT, 45_999L))
        assertTrue(p.periodicProbeDue(LocationMode.INTELLIGENT, 46_000L))
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 46_000L))
    }

    @Test fun `three failed bounded probes pause then retries grow to two five and ten minutes`() {
        val p = GpsPowerPolicy()
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 0L))
        p.onProbeFailed(45_000L)
        assertEquals(90_000L, p.nextProbeAtMs)
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 90_000L))
        p.onProbeFailed(110_000L)
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 155_000L))
        p.onProbeFailed(175_000L)
        assertTrue(p.receptionDegraded)
        assertEquals(295_000L, p.nextProbeAtMs)
        assertFalse(p.periodicProbeDue(LocationMode.INTELLIGENT, 294_999L))
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 295_000L))
        p.onProbeFailed(315_000L)
        assertEquals(615_000L, p.nextProbeAtMs)
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 615_000L))
        p.onProbeFailed(635_000L)
        assertEquals(1_235_000L, p.nextProbeAtMs)
        p.onProbeFailed(1_255_000L)
        assertEquals(1_855_000L, p.nextProbeAtMs)
        assertEquals(5, p.consecutiveFailures)
    }

    @Test fun `repeated handovers and alarms cannot clear the degraded state or keep GPS on`() {
        val p = GpsPowerPolicy()
        p.onStreamUnavailable(180_000L)
        assertTrue(p.tryStartProbe(LocationMode.ADAPTIVE, 190_000L))
        p.onProbeFailed(210_000L)
        for (time in 211_000L..309_000L step 1_000L) {
            assertFalse(p.tryStartProbe(LocationMode.INTELLIGENT, time))
        }
        assertTrue(p.receptionDegraded)
        assertEquals(510_000L, p.nextProbeAtMs)
        assertTrue(p.tryStartProbe(LocationMode.INTELLIGENT, 310_000L))
        assertEquals(4, p.consecutiveFailures)
        assertEquals(510_000L, p.nextProbeAtMs)
    }

    @Test fun `adaptive pauses even with screen on then resumes only after a usable fresh fix`() {
        val p = GpsPowerPolicy()
        assertTrue(p.streamWanted(LocationMode.ADAPTIVE, true))
        p.onStreamUnavailable(180_000L)
        assertFalse(p.streamWanted(LocationMode.ADAPTIVE, true))
        assertTrue(p.periodicProbeDue(LocationMode.ADAPTIVE, 300_000L))
        p.onFreshFix(301_000L)
        assertFalse(p.receptionDegraded)
        assertTrue(p.streamWanted(LocationMode.ADAPTIVE, true))
        assertFalse(p.streamWanted(LocationMode.ADAPTIVE, false))
        assertFalse(p.periodicProbeDue(LocationMode.ADAPTIVE, 400_000L))
        assertTrue(p.periodicProbeDue(LocationMode.INTELLIGENT, 346_000L))
    }

    @Test fun `continuous retains the permanent stream regardless of degraded reception`() {
        val p = GpsPowerPolicy()
        p.onStreamUnavailable(180_000L)
        assertTrue(p.streamWanted(LocationMode.CONTINUOUS, false))
        assertTrue(p.streamWanted(LocationMode.CONTINUOUS, true))
        assertFalse(p.periodicProbeDue(LocationMode.CONTINUOUS, 10_000_000L))
    }

    @Test fun `screen and alarm requests share the saving mode gate`() {
        val p = GpsPowerPolicy()
        assertTrue(p.tryStartProbe(LocationMode.ADAPTIVE, 0L))
        assertFalse(p.tryStartProbe(LocationMode.ADAPTIVE, 30_000L))
        assertFalse(p.tryStartProbe(LocationMode.ADAPTIVE, 59_999L))
        assertTrue(p.tryStartProbe(LocationMode.ADAPTIVE, 60_000L))
    }

    @Test fun `stream watchdog includes device sleep and uses the last real fix`() {
        val p = GpsPowerPolicy()
        assertFalse(p.streamHasStalled(0L, null, 179_999L))
        assertTrue(p.streamHasStalled(0L, null, 180_000L))
        assertFalse(p.streamHasStalled(0L, 170_000L, 180_000L))
        assertTrue(p.streamHasStalled(0L, 170_000L, 350_000L))
        assertFalse(p.streamHasStalled(500_000L, 170_000L, 510_000L))
    }

    @Test fun `age is measured monotonically and stale or future fixes cannot pass`() {
        assertTrue(GpsFixAge.isRecent(1_000_000L, 999_000L, 120_000L))
        assertFalse(GpsFixAge.isRecent(1_000_000L, 880_000L, 120_000L))
        assertFalse(GpsFixAge.isRecent(1_000_000L, 1_001_000L, 120_000L))
        assertFalse(GpsFixAge.isRecent(1_000_000L, 950_000L, 10_000L))
    }
}
