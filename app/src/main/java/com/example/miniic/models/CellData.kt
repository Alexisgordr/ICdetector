package com.example.miniic.models

data class CellData(
    val isRegistered: Boolean,
    val networkType: String,
    val cellId: String,
    val mnc: String,
    val tac: String,
    val dbm: Int,
    val mcc: String = "N/A",
    val verified: VerificationStatus = VerificationStatus.PENDING,
    val isSuspicious: Boolean = false,
    val suspiciousReason: String? = null,
    val timingAdvance: Int? = null,
    val arfcn: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val heuristicReport: HeuristicReport = HeuristicReport(),
    val securityScore: Int = 100
)
