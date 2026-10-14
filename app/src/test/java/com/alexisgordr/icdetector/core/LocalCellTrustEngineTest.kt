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

    /** 3.0 (corte de campaña) — H8 es calidad de dato y H9 está reservada: no ponen en cuarentena. */
    @Test fun `a failed H8 alone is not quarantine`() {
        val report = HeuristicReport(arfcnSanity = HeuristicStatus.FAILED)
        assertEquals(0, report.failedCount)
        assertEquals(1, report.dataQualityFailedCount)
        assertEquals(LocalCellTrustState.ESTABLISHED, LocalCellTrustEngine.apply(cell(report = report), mature()).localCellTrust.state)
        assertEquals(LocalCellTrustState.QUARANTINED, LocalCellTrustEngine.apply(
            cell(report = report.copy(tacDeviation = HeuristicStatus.FAILED)), mature()).localCellTrust.state)
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

    // 3.0 — El marcador de sitio sin verificar también se AÑADE; antes pisaba el motivo de H2.

    private val unverifiedSite = StableSiteDecision(enforced = true, reason = "NEW_CELL_AT_KNOWN_SITE")

    @Test fun `an unverified site keeps the reasons of other rules`() {
        val report = HeuristicReport(powerJump = HeuristicStatus.FAILED)
        val out = LocalCellTrustEngine.apply(
            cell(report = report).copy(suspiciousReason = "Salto potencia (>35dB)"),
            LocalCellTrustEvidence(),
            unverifiedSite
        )
        assertEquals(LocalCellTrustState.QUARANTINED, out.localCellTrust.state)
        assertEquals("Salto potencia (>35dB) | $SITE_UNVERIFIED_PREFIX NEW_CELL_AT_KNOWN_SITE", out.suspiciousReason)
    }

    @Test fun `an unverified site without other reasons stores only its marker`() {
        val out = LocalCellTrustEngine.apply(cell(), LocalCellTrustEvidence(), unverifiedSite)
        assertEquals(LocalCellTrustState.SITE_UNVERIFIED, out.localCellTrust.state)
        assertEquals("$SITE_UNVERIFIED_PREFIX NEW_CELL_AT_KNOWN_SITE", out.suspiciousReason)
        val again = LocalCellTrustEngine.apply(out, LocalCellTrustEvidence(), unverifiedSite)
        assertEquals("sin duplicar", out.suspiciousReason, again.suspiciousReason)
    }

    // 3.0 — Una lectura con Cell ID ilegible no hereda la evidencia (en caché) de la celda anterior.

    @Test fun `a reading without a readable Cell ID never uses the previous cell's evidence`() {
        val unreadable = cell(pci = 311, arfcn = 6400).copy(cellId = "N/A")
        val out = LocalCellTrustEngine.apply(unreadable, mature())
        assertTrue(out.localCellTrust.contradictions.isEmpty())
        assertNotEquals(LocalCellTrustState.CHANGED, out.localCellTrust.state)
        assertNull("sin marcador de reconfiguración", out.suspiciousReason)
        assertEquals(0, out.localCellTrust.confidencePercent)
        val blank = LocalCellTrustEngine.apply(unreadable.copy(cellId = " "), mature())
        assertTrue(blank.localCellTrust.contradictions.isEmpty())
    }

    @Test fun `a readable Cell ID still uses its evidence`() {
        val out = LocalCellTrustEngine.apply(cell(pci = 311), mature())
        assertEquals(LocalCellTrustState.CHANGED, out.localCellTrust.state)
    }

    // 3.0 — Portadora AÑADIDA (no sustituida): la vieja sigue en uso y la nueva se acepta al madurar.

    private fun carrierAggregation(candidate: LocalRfReconfiguration?) = mature().copy(
        knownPcis = setOf(200), knownArfcns = setOf(6400),
        knownPcisByArfcn = mapOf(6400 to setOf(200)),
        reconfigurationCandidate = candidate
    )

    private val addedCarrier = LocalRfReconfiguration(
        pci = 473, arfcn = 3600, distinctDays = 30, cappedObservations = 60,
        locatedObservations = 30, ageHours = 30L * 24L,
        // Como lo calcula CellDbHelper: ninguna fila limpia de OTRO PCI en 3600 en 48 h. El par
        // 200/6400 sigue vivo, pero en otra portadora.
        oldPairSeenRecently = false
    )

    @Test fun `an added carrier is accepted once mature while the old one stays in use`() {
        val onNew = LocalCellTrustEngine.apply(cell(pci = 473, arfcn = 3600), carrierAggregation(addedCarrier))
        assertEquals(LocalCellTrustState.ESTABLISHED, onNew.localCellTrust.state)
        assertTrue(onNew.localCellTrust.contradictions.isEmpty())
        assertNull(onNew.suspiciousReason)
        val onOld = LocalCellTrustEngine.apply(cell(pci = 200, arfcn = 6400), carrierAggregation(null))
        assertEquals(LocalCellTrustState.ESTABLISHED, onOld.localCellTrust.state)
    }

    @Test fun `an added carrier stays changed only while it is immature`() {
        val young = addedCarrier.copy(distinctDays = 5, cappedObservations = 15, ageHours = 5L * 24L)
        val out = LocalCellTrustEngine.apply(cell(pci = 473, arfcn = 3600), carrierAggregation(young))
        assertEquals(setOf("ARFCN"), out.localCellTrust.contradictions)
    }

    @Test fun `old pair veto is computed on the current carrier only`() {
        val db = listOf(java.io.File("app/src/main/java"), java.io.File("src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/storage/CellDbHelper.kt").readText()
        assertTrue(db.contains("oldPairSeenRecently = recentCleanPcisByArfcn[currentArfcn].orEmpty().any { it != currentPci }"))
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
