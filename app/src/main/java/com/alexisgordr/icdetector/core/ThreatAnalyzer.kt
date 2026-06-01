package com.alexisgordr.icdetector.core

import android.location.Location
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.models.SignalBaseline
import com.alexisgordr.icdetector.models.VerificationStatus

object ThreatAnalyzer {

    /**
     * Calcula el umbral dinámico según el contexto de densidad de celdas (vecinos)
     * Optimizamos para reducir falsos positivos en zonas rurales.
     */
    private fun getDynamicLocationThreshold(
        neighbors: List<CellData>,
        networkType: String
    ): Double {
        val count = neighbors.size
        return when {
            count >= 12 -> 2000.0
            count >= 6  -> 4000.0
            count >= 3  -> 8000.0
            networkType.contains("5G") -> 5000.0
            count == 0  -> 25000.0  // Sin vecinas = zona muy rural o macrocelda
            else        -> 15000.0
        }
    }

    fun analyzeMobileCellId(
        active: CellData,
        currentLocation: Location?,
        history: List<HistoryRecord>,
        neighbors: List<CellData>
    ): Triple<Boolean, Int, String?> {
        if (currentLocation == null || active.cellId == "N/A") {
            return Triple(true, 0, null)
        }

        if (history.isEmpty()) return Triple(true, 0, null)

        val threshold = getDynamicLocationThreshold(neighbors, active.networkType)

        val validRecords = history.filter { it.lat != null && it.lon != null }
        if (validRecords.isEmpty()) return Triple(true, 0, null)

        data class RecordAnalysis(
            val isAnomalous: Boolean,
            val severity: Int,
            val threatDetail: String?
        )

        val analyses = validRecords.map { record ->
            val results = FloatArray(1)
            Location.distanceBetween(
                currentLocation.latitude, currentLocation.longitude,
                record.lat!!, record.lon!!, results
            )
            val distance = results[0].toDouble()

            if (distance > threshold) {
                val pciMismatch = record.pci != null && active.pci != null && record.pci != active.pci
                val arfcnMismatch = record.arfcn != null && active.arfcn != null && record.arfcn != active.arfcn

                val distanceFactor = (distance / threshold).coerceAtMost(3.0)
                var severity = when {
                    distanceFactor > 2.5 -> 40
                    distanceFactor > 1.8 -> 30
                    distanceFactor > 1.2 -> 20
                    else -> 10
                }

                val detail = if (pciMismatch || arfcnMismatch) {
                    severity += 25
                    "Inconsistencia RF (PCI/ARFCN) detectada a gran distancia"
                } else {
                    "Celda detectada a distancia anómala (> ${threshold.toInt()}m)"
                }

                RecordAnalysis(true, severity, detail)
            } else {
                RecordAnalysis(false, 0, null)
            }
        }

        val anomalousRecords = analyses.filter { it.isAnomalous }
        val anomalyRatio = anomalousRecords.size.toFloat() / validRecords.size

        if (anomalousRecords.size < 2 || anomalyRatio < 0.30f) {
            return Triple(true, 0, null)
        }

        val worst = anomalousRecords.maxByOrNull { it.severity }!!
        return Triple(false, worst.severity.coerceAtMost(100), worst.threatDetail)
    }

    fun analyzeThreats(
        active: CellData,
        neighbors: List<CellData>,
        isHardwareCipheringActive: Boolean,
        isHardwareCipheringAvailable: Boolean = false,
        cellChangeHistory: List<Pair<String, Long>>,
        currentLocation: Location?,
        preloadedHistory: List<HistoryRecord> = emptyList(),
        isWifiActive: Boolean = false,
        isNetworkLatencyAnomalous: Boolean = false,
        signalBaseline: SignalBaseline? = null
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
        var hArfcn = true
        var hPingPong = true
        var hMobileCellId = true
        var hSignalBaseline = true

        // 1. Neighbor analysis
        if (!isWifiActive && neighbors.isEmpty() && active.dbm >= -80) {
            hIsolated = false
            reasons.add("Celda aislada")
            score -= 15
        }

        // 2. Signal Gap analysis
        val nextStrongest = neighbors.maxOfOrNull { it.dbm }
        if (!isWifiActive && nextStrongest != null && active.dbm >= -75 && (active.dbm - nextStrongest > 35)) {
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
        val uniqueMncs = (neighbors.asSequence().map { it.mnc } + active.mnc)
            .filter { it != "N/A" }.distinct().toList()
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
        // Fix: 5G NSA usa celda LTE ancla → multiplicador 78m/TA igual que LTE
        // Solo 5G SA puro usa 150m/TA
        active.timingAdvance?.let { ta ->
            val is5gSA = active.networkType.contains("5G") &&
                         !active.networkType.contains("NSA")
            val taMultiplier = if (is5gSA) 150 else 78
            val taDistanceMeters = ta * taMultiplier

            if (active.lat != null && active.lon != null && currentLocation != null) {
                if (ta > 0) {
                    val results = FloatArray(1)
                    Location.distanceBetween(
                        currentLocation.latitude, currentLocation.longitude,
                        active.lat, active.lon, results
                    )
                    if (results[0] > 2000 && taDistanceMeters < 500) {
                        hTa = false
                        reasons.add("Suplantación TA")
                        score -= 40
                    }
                }
            } else {
                if (!is5gSA && ta <= 1 && active.dbm >= -60
                    && active.verified != VerificationStatus.VERIFIED) {
                    hTa = false
                    reasons.add("Proximidad anómala (TA)")
                    score -= 15
                }
            }
        }

        // 7. Ghost Cells Check
        // Umbral subido a -65dBm y vecinas a -110dBm para reducir
        // falsos positivos en zonas rurales con macroceldas
        if (!isWifiActive && active.dbm >= -65 && neighbors.isNotEmpty()
            && neighbors.all { it.dbm <= -110 }) {
            hGhost = false
            reasons.add("Vecinos fantasma")
            score -= 25
        }

        // 8. ARFCN Sanity Check
        // Fix: máximo teórico LTE es 262143, no 70645
        // 70645 es válido para Band 252/255 (CBRS)
        active.arfcn?.let { arfcn ->
            if (active.networkType.contains("5G")) {
                if (arfcn > 3279165 || arfcn == 0) {
                    hArfcn = false
                    reasons.add("Frecuencia (ARFCN) 5G sospechosa")
                    score -= 15
                }
            } else if (active.networkType.contains("4G") || active.networkType.contains("LTE")) {
                if (arfcn > 262143 || arfcn == 0) {  // ← máximo teórico 3GPP
                    hArfcn = false
                    reasons.add("Frecuencia (EARFCN) 4G sospechosa")
                    score -= 15
                }
            }
        }

        // 9. Hardware Ciphering Check (Android 14+)
        if (isHardwareCipheringAvailable && !isHardwareCipheringActive) {
            reasons.add("Cifrado de red anulado (A5/0)")
            score -= 50
        }

        // 10. Ping-Pong Effect
        if (cellChangeHistory.size >= 3) {
            val speedMps = currentLocation?.speed ?: 0f
            val isMovingFast = speedMps > 8f
            if (!isMovingFast) {
                hPingPong = false
                reasons.add("Efecto Ping-Pong (Cambios rápidos en parado)")
                score -= 25
            }
        }

        // 11. Consistencia Geográfica y RF (PCI/ARFCN)
        val (hMobileOk, hMobilePenalty, hMobileReason) = analyzeMobileCellId(
            active, currentLocation, preloadedHistory, neighbors
        )
        if (!hMobileOk) {
            hMobileCellId = false
            reasons.add(hMobileReason ?: "Consistencia geográfica fallida")
            score -= hMobilePenalty
        }

        // 12. RF Quality + Latency Cross-Layer Correlation (Experimental)
        if (!isWifiActive && isNetworkLatencyAnomalous && active.dbm >= -70) {
            val rsrqAnomalous = active.rsrq != null && active.rsrq <= -15
            val sinrAnomalous = active.sinr != null && active.sinr <= 0
            if (rsrqAnomalous || sinrAnomalous) {
                reasons.add("RF anómalo + latencia: posible MITM (Experimental)")
                score -= 20
            }
        }

        // 13. Anomalía de potencia vs línea base propia (baseline geográfico)
        signalBaseline?.let { base ->
            val effectiveStd = maxOf(base.stdDevDbm, 4.0)
            val excessDb = active.dbm - base.meanDbm  // positivo = más fuerte de lo habitual
            val significant = excessDb >= 18.0 && excessDb >= 3.0 * effectiveStd
            if (!isWifiActive && significant && active.dbm >= -95) {
                hSignalBaseline = false
                reasons.add("Potencia anómala vs historial (+${excessDb.toInt()}dB)")
                score -= when {
                    excessDb >= 30 -> 25
                    excessDb >= 24 -> 18
                    else -> 12
                }
            }
        }

        // Probabilidad Bayesiana de amenaza
        val failedList = buildList {
            if (!hIsolated) add("isolated")
            if (!hPowerJump) add("powerJump")
            if (!hMcc) add("mccMismatch")
            if (!hMncCount) add("mncCount")
            if (!hTac) add("tacDev")
            if (!hTa) add("taDistance")
            if (!hGhost) add("ghostCells")
            if (!hArfcn) add("arfcn")
            if (isHardwareCipheringAvailable && !isHardwareCipheringActive) add("ciphering")
            if (!hPingPong) add("pingPong")
            if (!hMobileCellId) add("h11")
            if (!hSignalBaseline) add("signalBaseline")
        }

        val threatProbability = BayesianScorer.calculate(
            failedList,
            active.verified.name,
            isNetworkLatencyAnomalous
        )

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
            arfcnSanityPassed = hArfcn,
            hardwareCipheringPassed = isHardwareCipheringActive,
            hardwareCipheringAvailable = isHardwareCipheringAvailable,
            pingPongPassed = hPingPong,
            mobileCellIdPassed = hMobileCellId,
            signalBaselinePassed = hSignalBaseline
        )

        return active.copy(
            isSuspicious = isSuspicious,
            suspiciousReason = if (reasons.isNotEmpty()) reasons.joinToString(" | ") else null,
            heuristicReport = report,
            securityScore = finalScore,
            threatProbability = threatProbability
        )
    }
}
