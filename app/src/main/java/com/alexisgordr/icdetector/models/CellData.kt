package com.alexisgordr.icdetector.models

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
    val pci: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val rsrq: Int? = null,   // ← NUEVO: calidad de señal LTE/NR (-3 a -20 dB)
    val sinr: Int? = null,   // ← NUEVO: relación señal/ruido
    val band: Int? = null,   // ← NUEVO: banda física LTE derivada del EARFCN (heurística 14)
    val heuristicReport: HeuristicReport = HeuristicReport(),
    val securityScore: Int = 100,
    val threatProbability: Float = 0f  // ← NUEVO: probabilidad Bayesiana de IMSI catcher
)
