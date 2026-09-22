package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.*
import org.junit.Assert.*
import org.junit.Test

class LocalCellTrustEngineTest {
    @Test fun `quarantine query pattern follows persisted constants`() {
        assertEquals(
            "$SUBTHRESHOLD_PREFIX $LOCAL_TRUST_RECONFIGURATION_REASON (%)",
            LOCAL_TRUST_RECONFIGURATION_HISTORY_LIKE
        )
    }

    private fun cell(
        pci: Int = 10,
        arfcn: Int = 2850,
        report: HeuristicReport = HeuristicReport(),
        verified: VerificationStatus = VerificationStatus.VERIFIED
    ) = CellData(true, "4G LTE", "100", "07", "31601", -90, "214",
        verified = verified, pci = pci, arfcn = arfcn, radioTech = RadioTech.LTE,
        heuristicReport = report)

    private fun mature() = LocalCellTrustEvidence(
        cleanObservations = 80, cappedCleanObservations = 35, distinctDays = 14,
        ageHours = 14L * 24, locatedObservations = 30,
        knownPcis = setOf(10), knownArfcns = setOf(2850), rfObservations = 60,
        knownPcisByArfcn = mapOf(2850 to setOf(10)),
        trustedTransitions = 20, trustedRoutes = 4
    )

    @Test fun `confidence never claims certainty`() {
        assertEquals(98, LocalCellTrustEngine.confidence(cell(), mature()))
        assertEquals(98, LocalCellTrustEngine.confidence(
            cell(verified = VerificationStatus.NOT_FOUND), mature()))
    }

    @Test fun `mature coherent identity becomes established`() {
        val out = LocalCellTrustEngine.apply(cell(), mature())
        assertEquals(LocalCellTrustState.ESTABLISHED, out.localCellTrust.state)
        assertFalse(out.isSuspicious)
    }

    @Test fun `single operator parameter change is visible but does not alarm`() {
        val out = LocalCellTrustEngine.apply(cell(pci = 311), mature())
        assertEquals(LocalCellTrustState.CHANGED, out.localCellTrust.state)
        assertEquals(setOf("PCI"), out.localCellTrust.contradictions)
        assertFalse(out.localCellTrust.alarmCandidate)
        assertFalse(out.isSuspicious)
        assertNotNull(out.suspiciousReason)
    }

    @Test fun `rf change plus incoherent handover enters temporal alarm path`() {
        val report = HeuristicReport(transitionCoherence = HeuristicStatus.FAILED)
        val out = LocalCellTrustEngine.apply(cell(pci = 311, report = report), mature())
        assertTrue(out.localCellTrust.alarmCandidate)
        assertTrue(out.isSuspicious)
        assertEquals(69, out.securityScore)
    }

    @Test fun `failed observation is quarantined and cannot look established`() {
        val report = HeuristicReport(signalBaseline = HeuristicStatus.FAILED)
        val out = LocalCellTrustEngine.apply(cell(report = report), mature())
        assertEquals(LocalCellTrustState.QUARANTINED, out.localCellTrust.state)
    }

    @Test fun `two days remain learning`() {
        val evidence = mature().copy(distinctDays = 2, ageHours = 30, cappedCleanObservations = 6)
        val out = LocalCellTrustEngine.apply(cell(), evidence)
        assertEquals(LocalCellTrustState.LEARNING, out.localCellTrust.state)
    }

    @Test fun `pci established on its own carrier is not a change`() {
        val evidence = mature().copy(
            knownPcis = setOf(10, 311),
            knownArfcns = setOf(2850, 6400),
            knownPcisByArfcn = mapOf(2850 to setOf(10), 6400 to setOf(311))
        )
        val out = LocalCellTrustEngine.apply(cell(pci = 311, arfcn = 6400), evidence)
        assertEquals(LocalCellTrustState.ESTABLISHED, out.localCellTrust.state)
        assertNull(out.suspiciousReason)
    }

    @Test fun `persistent coherent reconfiguration can leave quarantine`() {
        val candidate = LocalRfReconfiguration(
            pci = 311, arfcn = 2850, distinctDays = 14, cappedObservations = 30,
            locatedObservations = 20, ageHours = 13L * 24L, oldPairSeenRecently = false
        )
        val out = LocalCellTrustEngine.apply(
            cell(pci = 311), mature().copy(reconfigurationCandidate = candidate)
        )
        assertEquals(LocalCellTrustState.ESTABLISHED, out.localCellTrust.state)
        assertNull(out.suspiciousReason)
    }

    @Test fun `sudden clone pci stays changed while old pair remains visible`() {
        val candidate = LocalRfReconfiguration(
            pci = 311, arfcn = 2850, distinctDays = 14, cappedObservations = 30,
            locatedObservations = 20, ageHours = 13L * 24L, oldPairSeenRecently = true
        )
        val out = LocalCellTrustEngine.apply(
            cell(pci = 311), mature().copy(reconfigurationCandidate = candidate)
        )
        assertEquals(LocalCellTrustState.CHANGED, out.localCellTrust.state)
        assertEquals(setOf("PCI"), out.localCellTrust.contradictions)
        assertNotNull(out.suspiciousReason)
    }
}
