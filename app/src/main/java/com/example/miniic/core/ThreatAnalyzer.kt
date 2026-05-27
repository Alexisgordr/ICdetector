package com.example.miniic.core

import android.location.Location
import com.example.miniic.models.CellData
import com.example.miniic.models.HeuristicReport
import com.example.miniic.models.VerificationStatus

object ThreatAnalyzer {

    fun analyzeThreats(
        active: CellData,
        neighbors: List<CellData>,
        isHardwareCipheringActive: Boolean,
        cellChangeHistory: List<Pair<String, Long>>,
        currentLocation: Location?,
    ): CellData {
        val reasons = mutableListOf<String>()
        var score = 100

        var hIsolated = true
        var hPowerJump = true
        var hMcc = true
        var hMncCount = true
        var hTac = true
        var hTa = true
        var hGhost = true
        var hPingPong = true

        // 1. Neighbor analysis
        if ((neighbors.isEmpty()) && (active.dbm >= -80)) {
            hIsolated = false
            reasons.add("Celda aislada")
            score -= 15
        }

        // 2. Signal Gap analysis
        val nextStrongest = neighbors.maxOfOrNull { it.dbm }
        if (nextStrongest != null && active.dbm >= -75 && (active.dbm - nextStrongest > 35)) {
            hPowerJump = false
            reasons.add("Salto potencia (>35dB)")
            score -= 20
        }

        // 3. MNC/MCC Inconsistency
        val differentMcc = neighbors.filter { it.mcc != "N/A" && it.mcc != active.mcc }
        if (differentMcc.isNotEmpty()) {
            hMcc = false
            reasons.add("Inconsistencia MCC")
            score -= 30
        }
        
        // 4. Multiple MNCs in area
        val uniqueMncs = (neighbors.asSequence().map { it.mnc } + active.mnc).filter { it != "N/A" }.distinct().toList()
        if (uniqueMncs.size > 3) {
            hMncCount = false
            reasons.add("Multitud de MNCs")
            score -= 15
        }

        // 5. TAC Deviation Audit
        if (neighbors.isNotEmpty() && active.tac != "N/A") {
            val neighborTacs = neighbors.map { it.tac }.filter { it != "N/A" }
            if (neighborTacs.isNotEmpty() && !neighborTacs.contains(active.tac)) {
                hTac = false
                reasons.add("Desviación TAC")
                score -= 20
            }
        }

        // 6. Timing Advance Audit
        active.timingAdvance?.let { ta ->
            val taDistanceMeters = if (active.networkType.contains("5G")) ta * 150 else ta * 78
            if (active.lat != null && active.lon != null && currentLocation != null) {
                val results = FloatArray(1)
                Location.distanceBetween(currentLocation.latitude, currentLocation.longitude, active.lat, active.lon, results)
                if (results[0] > 2000 && taDistanceMeters < 500) {
                    hTa = false
                    reasons.add("Suplantación TA")
                    score -= 40
                }
            } else {
                if (!active.networkType.contains("5G") && ta <= 1 && active.dbm >= -60 && active.verified != VerificationStatus.VERIFIED) {
                    hTa = false
                    reasons.add("Proximidad anómala (TA)")
                    score -= 15
                }
            }
        }

        // 7. Ghost Cells Check
        if (active.dbm >= -70 && neighbors.isNotEmpty() && neighbors.all { it.dbm <= -120 }) {
            hGhost = false
            reasons.add("Vecinos fantasma")
            score -= 25
        }

        // 8. Hardware Ciphering Check (Android 14+)
        if (!isHardwareCipheringActive) {
            reasons.add("Cifrado de red anulado (A5/0)")
            score -= 50
        }

        // 9. Ping-Pong Effect
        if (cellChangeHistory.size >= 3) {
            val speedMps = currentLocation?.speed ?: 0f
            val isMovingFast = speedMps > 8f 

            if (!isMovingFast) {
                hPingPong = false
                reasons.add("Efecto Ping-Pong")
                score -= 25
            }
        }

        // Bonificadores y penalizadores por Base de Datos
        if (active.verified == VerificationStatus.VERIFIED) {
            score += 15
        } else if (active.verified == VerificationStatus.NOT_FOUND) {
            score -= 10
        }

        val finalScore = score.coerceIn(0, 100)
        val isSuspicious = finalScore < 70

        val report = HeuristicReport(
            isolatedCellPassed = hIsolated,
            powerJumpPassed = hPowerJump,
            mccConsistencyPassed = hMcc,
            mncCountPassed = hMncCount,
            tacDeviationPassed = hTac,
            taDistancePassed = hTa,
            ghostNeighborsPassed = hGhost,
            hardwareCipheringPassed = isHardwareCipheringActive,
            pingPongPassed = hPingPong
        )

        return active.copy(
            isSuspicious = isSuspicious,
            suspiciousReason = if (reasons.isNotEmpty()) reasons.joinToString(" | ") else null,
            heuristicReport = report,
            securityScore = finalScore
        )
    }
}
