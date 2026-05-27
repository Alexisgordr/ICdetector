package com.example.miniic.models

data class HeuristicReport(
    val isolatedCellPassed: Boolean = true,
    val powerJumpPassed: Boolean = true,
    val mccConsistencyPassed: Boolean = true,
    val mncCountPassed: Boolean = true,
    val tacDeviationPassed: Boolean = true,
    val taDistancePassed: Boolean = true,
    val ghostNeighborsPassed: Boolean = true,
    val hardwareCipheringPassed: Boolean = true,
    val pingPongPassed: Boolean = true
)
