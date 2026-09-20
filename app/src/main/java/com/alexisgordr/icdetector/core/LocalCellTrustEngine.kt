package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.LocalCellTrust
import com.alexisgordr.icdetector.models.LocalCellTrustEvidence
import com.alexisgordr.icdetector.models.LocalCellTrustState
import com.alexisgordr.icdetector.models.LOCAL_TRUST_RECONFIGURATION_REASON
import com.alexisgordr.icdetector.models.RadioTech

/** Builds a slow, revocable local confidence profile with a deliberate 98% epistemic ceiling. */
object LocalCellTrustEngine {
    const val MAX_CONFIDENCE = 98
    const val REQUIRED_DAYS = 14
    const val REQUIRED_CLEAN_OBSERVATIONS = 20
    private fun acceptedReconfiguration(cell: CellData, evidence: LocalCellTrustEvidence): Boolean {
        val candidate = evidence.reconfigurationCandidate ?: return false
        return cell.pci == candidate.pci && cell.arfcn == candidate.arfcn &&
            candidate.distinctDays >= REQUIRED_DAYS &&
            candidate.cappedObservations >= REQUIRED_CLEAN_OBSERVATIONS &&
            candidate.locatedObservations >= 8 && candidate.ageHours >= 13L * 24L &&
            evidence.trustedTransitions >= 3 && !candidate.oldPairSeenRecently
    }

    fun apply(cell: CellData, evidence: LocalCellTrustEvidence): CellData {
        val confidence = confidence(cell, evidence)
        val rfIdentityReady = when (cell.radioTech) {
            RadioTech.LTE -> evidence.knownPcisByArfcn.isNotEmpty()
            RadioTech.NR -> evidence.knownPcisByArfcn.isNotEmpty()
            RadioTech.GSM -> evidence.knownPcis.isNotEmpty() || evidence.knownArfcns.isNotEmpty()
            RadioTech.UMTS -> evidence.knownPcis.isNotEmpty() || evidence.knownArfcns.isNotEmpty()
            RadioTech.UNKNOWN -> false
        }
        val eligible = evidence.distinctDays >= REQUIRED_DAYS &&
            evidence.cappedCleanObservations >= REQUIRED_CLEAN_OBSERVATIONS &&
            evidence.locatedObservations >= 8 && evidence.rfObservations >= 10 &&
            evidence.trustedTransitions >= 3 && rfIdentityReady

        val contradictions = linkedSetOf<String>()
        if (eligible) {
            if (cell.radioTech == RadioTech.LTE || cell.radioTech == RadioTech.NR) {
                val accepted = acceptedReconfiguration(cell, evidence)
                val carrierPcis = cell.arfcn?.let(evidence.knownPcisByArfcn::get)
                if (!accepted && cell.arfcn != null && carrierPcis == null) contradictions += "ARFCN"
                if (!accepted && cell.pci != null && carrierPcis != null && cell.pci !in carrierPcis) {
                    contradictions += "PCI"
                }
            } else {
                if (cell.pci != null && evidence.knownPcis.isNotEmpty() && cell.pci !in evidence.knownPcis) {
                    contradictions += "PCI"
                }
                if (cell.arfcn != null && evidence.knownArfcns.isNotEmpty() && cell.arfcn !in evidence.knownArfcns) {
                    contradictions += "ARFCN"
                }
            }
            if (cell.heuristicReport.mobileCellId == HeuristicStatus.FAILED) contradictions += "GEOMETRY"
            if (cell.heuristicReport.transitionCoherence == HeuristicStatus.FAILED) contradictions += "HANDOVER"
        }

        val rfChanged = "PCI" in contradictions || "ARFCN" in contradictions
        val independentContradiction = "GEOMETRY" in contradictions || "HANDOVER" in contradictions
        val alarmCandidate = eligible && rfChanged && independentContradiction
        val hasFailures = cell.heuristicReport.failedCount > 0
        val state = when {
            contradictions.isNotEmpty() -> LocalCellTrustState.CHANGED
            hasFailures -> LocalCellTrustState.QUARANTINED
            eligible -> LocalCellTrustState.ESTABLISHED
            evidence.cleanObservations == 0 -> LocalCellTrustState.NEW
            else -> LocalCellTrustState.LEARNING
        }
        val trust = LocalCellTrust(
            state = state,
            confidencePercent = confidence,
            distinctDays = evidence.distinctDays,
            ageHours = evidence.ageHours,
            cleanObservations = evidence.cappedCleanObservations,
            requiredDays = REQUIRED_DAYS,
            requiredObservations = REQUIRED_CLEAN_OBSERVATIONS,
            contradictions = contradictions,
            alarmCandidate = alarmCandidate
        )
        val reason = "$LOCAL_TRUST_RECONFIGURATION_REASON (${contradictions.joinToString("+")})"
        if (!alarmCandidate) return cell.copy(
            localCellTrust = trust,
            // TemporalConfidence marks this as sub-threshold before persistence. Keeping a reason
            // is what freezes CHANGED observations out of every trusted-learning query.
            suspiciousReason = if (contradictions.isNotEmpty()) reason else cell.suspiciousReason
        )

        return cell.copy(
            localCellTrust = trust,
            isSuspicious = true,
            securityScore = minOf(cell.securityScore, 69),
            suspiciousReason = listOfNotNull(cell.suspiciousReason, reason).joinToString(" | ")
        )
    }

    fun confidence(cell: CellData, e: LocalCellTrustEvidence): Int {
        fun portion(value: Double, target: Double, weight: Int) =
            (value / target).coerceIn(0.0, 1.0) * weight
        val days = portion(e.distinctDays.toDouble(), REQUIRED_DAYS.toDouble(), 25)
        val samples = portion(e.cappedCleanObservations.toDouble(), 30.0, 20)
        val age = portion(e.ageHours.toDouble(), 14.0 * 24.0, 10)
        val location = portion(e.locatedObservations.toDouble(), 12.0, 15)
        val rf = if (e.rfObservations <= 0) 0.0 else {
            val identities = if (e.knownPcisByArfcn.isNotEmpty() ||
                (e.knownPcis.isNotEmpty() && e.knownArfcns.isNotEmpty())) 1.0 else 0.5
            portion(e.rfObservations.toDouble(), 20.0, 18) * identities
        }
        val topology = portion(e.trustedTransitions.toDouble(), 8.0, 10)
        return (days + samples + age + location + rf + topology)
            .toInt().coerceIn(0, MAX_CONFIDENCE)
    }
}
