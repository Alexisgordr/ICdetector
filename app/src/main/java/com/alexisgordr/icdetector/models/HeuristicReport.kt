package com.alexisgordr.icdetector.models

/**
 * Resultado observable de una heurística en el ciclo actual.
 *
 * NOT_EVALUATED no equivale a PASSED: significa que Android, el módem o el historial todavía no
 * han aportado los datos necesarios. Separar ambos estados evita mostrar un check verde para una
 * regla que realmente se abstuvo.
 */
enum class HeuristicStatus {
    PASSED,
    FAILED,
    NOT_EVALUATED;

    val passed: Boolean
        get() = this != FAILED
}

data class HeuristicReport(
    val isolatedCell: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val powerJump: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val mccConsistency: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val mncCount: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val tacDeviation: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val taDistance: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val ghostNeighbors: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val arfcnSanity: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val hardwareCiphering: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val pingPong: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val mobileCellId: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val latencyCorrelation: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val signalBaseline: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val bandDowngrade: HeuristicStatus = HeuristicStatus.NOT_EVALUATED,
    val rfStability: HeuristicStatus = HeuristicStatus.NOT_EVALUATED
) {
    // Compatibilidad: una regla que se abstiene no debe activar una alarma.
    val isolatedCellPassed get() = isolatedCell.passed
    val powerJumpPassed get() = powerJump.passed
    val mccConsistencyPassed get() = mccConsistency.passed
    val mncCountPassed get() = mncCount.passed
    val tacDeviationPassed get() = tacDeviation.passed
    val taDistancePassed get() = taDistance.passed
    val ghostNeighborsPassed get() = ghostNeighbors.passed
    val arfcnSanityPassed get() = arfcnSanity.passed
    val hardwareCipheringPassed get() = hardwareCiphering.passed
    val hardwareCipheringAvailable get() = hardwareCiphering != HeuristicStatus.NOT_EVALUATED
    val pingPongPassed get() = pingPong.passed
    val mobileCellIdPassed get() = mobileCellId.passed
    val latencyCorrelationPassed get() = latencyCorrelation.passed
    val signalBaselinePassed get() = signalBaseline.passed
    val bandDowngradePassed get() = bandDowngrade.passed
    val rfStabilityPassed get() = rfStability.passed

    val evaluatedCount: Int
        get() = statuses.count { it != HeuristicStatus.NOT_EVALUATED }

    val failedCount: Int
        get() = statuses.count { it == HeuristicStatus.FAILED }

    val totalCount: Int
        get() = statuses.size

    private val statuses: List<HeuristicStatus>
        get() = listOf(
            isolatedCell, powerJump, mccConsistency, mncCount, tacDeviation,
            taDistance, ghostNeighbors, arfcnSanity, hardwareCiphering, pingPong,
            mobileCellId, latencyCorrelation, signalBaseline, bandDowngrade, rfStability
        )
}
