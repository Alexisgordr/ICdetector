package com.alexisgordr.icdetector.models

data class HistoryRecord(
    val timestamp: String,
    val netType: String,
    val cid: String,
    val mnc: String,
    val tac: String,
    val mcc: String,
    val dbm: Int,
    val verified: VerificationStatus = VerificationStatus.PENDING,
    val score: Int = 100,
    val failedHeuristics: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val pci: Int? = null,
    val arfcn: Int? = null,
    val rsrq: Int? = null,
    val sinr: Int? = null
)
