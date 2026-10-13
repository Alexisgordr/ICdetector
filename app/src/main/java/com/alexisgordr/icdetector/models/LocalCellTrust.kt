package com.alexisgordr.icdetector.models

/** Stable persisted marker used by both the detector and the quarantine query. */
const val LOCAL_TRUST_RECONFIGURATION_REASON = "Cambio en identidad celular consolidada"
const val LOCAL_TRUST_RECONFIGURATION_HISTORY_LIKE =
    "$SUBTHRESHOLD_PREFIX $LOCAL_TRUST_RECONFIGURATION_REASON (%)"

/** Revocable local confidence; never claims cryptographic or operator-backed authenticity. */
enum class LocalCellTrustState { NEW, LEARNING, ESTABLISHED, CHANGED, QUARANTINED, SITE_UNVERIFIED }

data class LocalCellTrustEvidence(
    val cleanObservations: Int = 0,
    val cappedCleanObservations: Int = 0,
    val distinctDays: Int = 0,
    val ageHours: Long = 0,
    val locatedObservations: Int = 0,
    val knownPcis: Set<Int> = emptySet(),
    val knownArfcns: Set<Int> = emptySet(),
    /** Established PCI values scoped to their carrier; avoids mixing carrier aggregation. */
    val knownPcisByArfcn: Map<Int, Set<Int>> = emptyMap(),
    /** Quarantined evidence for the currently observed, not-yet-established RF pair. */
    val reconfigurationCandidate: LocalRfReconfiguration? = null,
    val rfObservations: Int = 0,
    val trustedTransitions: Int = 0,
    val trustedRoutes: Int = 0
)

data class LocalRfReconfiguration(
    val pci: Int,
    val arfcn: Int,
    val distinctDays: Int = 0,
    val cappedObservations: Int = 0,
    val locatedObservations: Int = 0,
    val ageHours: Long = 0,
    /**
     * Another PCI was seen in a clean row on the SAME carrier ([arfcn]) in the last 48 h: the old
     * pair is still alive there, which is the clone pattern, so the candidate is not accepted. An
     * established pair on ANOTHER carrier never sets it: a carrier added beside the old one (carrier
     * aggregation, a new carrier from the operator) is accepted once mature, while both stay in use.
     * Computed per carrier in CellDbHelper.getLocalCellTrustEvidence. Defaults to true (veto) so
     * that evidence built without that query never accepts a reconfiguration.
     */
    val oldPairSeenRecently: Boolean = true
)

data class LocalCellTrust(
    val state: LocalCellTrustState = LocalCellTrustState.NEW,
    val confidencePercent: Int = 0,
    val distinctDays: Int = 0,
    val ageHours: Long = 0,
    val cleanObservations: Int = 0,
    val requiredDays: Int = 14,
    val requiredObservations: Int = 20,
    val contradictions: Set<String> = emptySet(),
    val alarmCandidate: Boolean = false
)
