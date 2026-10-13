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

    // 3.0 — La reconfiguración local se AÑADE a los motivos de otras reglas; antes los sustituía.

    @Test fun `a non-alarm reconfiguration keeps the reasons of other rules`() {
        val report = HeuristicReport(powerJump = HeuristicStatus.FAILED)
        val out = LocalCellTrustEngine.apply(
            cell(pci = 311, report = report).copy(suspiciousReason = "Salto potencia (>35dB)"), mature())
        assertFalse(out.localCellTrust.alarmCandidate)
        assertEquals("Salto potencia (>35dB) | $LOCAL_TRUST_RECONFIGURATION_REASON (PCI)", out.suspiciousReason)
    }

    @Test fun `several earlier reasons are all kept, in order`() {
        val earlier = "Celda aislada${StoredReasons.SEPARATOR}Desviación TAC"
        val out = LocalCellTrustEngine.apply(cell(pci = 311).copy(suspiciousReason = earlier), mature())
        assertEquals("Celda aislada | Desviación TAC | $LOCAL_TRUST_RECONFIGURATION_REASON (PCI)", out.suspiciousReason)
    }

    @Test fun `no previous reason, blank reason or duplicate gives one clean marker`() {
        val marker = "$LOCAL_TRUST_RECONFIGURATION_REASON (PCI)"
        assertEquals(marker, LocalCellTrustEngine.apply(cell(pci = 311), mature()).suspiciousReason)
        assertEquals(marker, LocalCellTrustEngine.apply(cell(pci = 311).copy(suspiciousReason = "  "), mature()).suspiciousReason)
        assertEquals("Celda aislada | $marker",
            LocalCellTrustEngine.apply(cell(pci = 311).copy(suspiciousReason = "Celda aislada | $marker"), mature()).suspiciousReason)
    }

    @Test fun `the alarm branch keeps earlier reasons too`() {
        val report = HeuristicReport(transitionCoherence = HeuristicStatus.FAILED)
        val out = LocalCellTrustEngine.apply(
            cell(pci = 311, report = report).copy(suspiciousReason = "Transición celular incoherente"), mature())
        assertTrue(out.isSuspicious)
        assertEquals("Transición celular incoherente | $LOCAL_TRUST_RECONFIGURATION_REASON (PCI+HANDOVER)", out.suspiciousReason)
    }

    @Test fun `append helper never empties or duplicates`() {
        assertNull(StoredReasons.append(null, null))
        assertNull(StoredReasons.append(" ", ""))
        assertEquals("A", StoredReasons.append(null, "A"))
        assertEquals("A", StoredReasons.append("A", "A"))
        assertEquals("A | B", StoredReasons.append("A |  | ", "B"))
        assertEquals("A | B", StoredReasons.append("A | B", " B "))
    }
}
