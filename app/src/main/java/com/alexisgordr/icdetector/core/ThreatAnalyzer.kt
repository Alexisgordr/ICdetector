package com.alexisgordr.icdetector.core

import android.location.Location
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.models.SignalBaseline
import com.alexisgordr.icdetector.models.CellRfStability
import com.alexisgordr.icdetector.models.CellReputation
import com.alexisgordr.icdetector.models.CellRfFingerprint
import com.alexisgordr.icdetector.models.VerificationStatus

object ThreatAnalyzer {

    /**
     * ¿La señal venía degradándose de forma progresiva en los últimos ciclos?
     * Excepción física para la heurística 14: si la potencia caía poco a poco
     * (estás entrando a un garaje/sótano/ascensor), un salto a banda baja es legítimo
     * y no debe penalizarse. Sin datos suficientes devuelve false (no podemos afirmar
     * que se degradaba), de modo que la decisión recae en si la señal previa era fuerte.
     */
    private fun isSignalDegrading(recent: List<Int>): Boolean {
        if (recent.size < 4) return false
        val window = recent.takeLast(4)
        val firstHalf = window.take(2).average()
        val secondHalf = window.takeLast(2).average()
        val sustainedDrop = (firstHalf - secondHalf) >= 6.0
        val steps = window.zipWithNext().count { (a, b) -> b < a }
        val mostlyDecreasing = steps >= window.size - 2 && (window.first() - window.last()) >= 6
        return sustainedDrop || mostlyDecreasing
    }

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
        signalBaseline: SignalBaseline? = null,
        previousBand: Int? = null,
        previousDbm: Int? = null,
        recentRegisteredDbm: List<Int> = emptyList(),
        rfStability: CellRfStability? = null,
        reputation: CellReputation? = null,
        rfFingerprint: CellRfFingerprint? = null
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
        var hBandDowngrade = true
        var hRfStability = true

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
        // Umbral >4 (antes >3): es habitual ver 3-4 MNC de forma legítima
        // (MVNOs, estaciones de tren, roaming, zonas fronterizas). Subir el umbral
        // reduce falsos positivos sin necesidad de tocar la LR (que sigue baja a propósito).
        if (uniqueMncs.size > 4) {
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
        // Aprende del propio historial: esta celda, en este punto, suele verse a X dBm.
        // Un transmisor cercano (catcher) suplantando una celda que aquí es habitualmente
        // más débil aparece con una potencia anómalamente ALTA. Se anchea a la geografía
        // (mismo sitio) y al historial del usuario, no a parámetros de red falseables.
        // Solo la dirección "más fuerte de lo normal" es sospechosa; más débil puede ser
        // simple obstrucción o distancia. Requiere historial (si no, no juzga nada).
        signalBaseline?.let { base ->
            // Acotamos el stddev a un mínimo para no disparar con historiales muy planos,
            // y exigimos una desviación grande Y estadísticamente significativa.
            val effectiveStd = maxOf(base.stdDevDbm, 4.0)
            val excessDb = active.dbm - base.meanDbm  // positivo = más fuerte de lo habitual
            var significant = excessDb >= 18.0 && excessDb >= 3.0 * effectiveStd
            // Refuerzo por percentil (P99): con historial suficiente, exigimos ADEMÁS que la
            // lectura supere el percentil 99 histórico de esta celda. Endurece H13 frente a
            // celdas con cola alta legítima (que ocasionalmente se ven fuertes sin ataque),
            // reduciendo falsos positivos. Con pocas muestras el percentil no es fiable, así
            // que NO se aplica y el comportamiento es idéntico al anterior.
            if (significant && base.sampleCount >= 20 && base.p99Dbm != 0) {
                significant = active.dbm > base.p99Dbm
            }
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

        // Huella RF (RSRQ/SINR) — extensión del baseline de señal (familia H13). Una celda
        // suplantada por otro transmisor puede presentar una calidad de señal incoherente con
        // su firma histórica. MUY conservador: RSRQ y SINR son ruidosos, así que exige muchas
        // muestras (>=30, garantizado por la consulta) y desviación GRANDE en AMBAS métricas a
        // la vez. Solo penaliza si H13-RSRP no disparó ya (no doble-cuenta: comparten flag).
        // Nace dormido (columnas rsrq/sinr vacías tras la migración) hasta acumular semanas.
        if (!isWifiActive && hSignalBaseline) {
            rfFingerprint?.let { fp ->
                val curRsrq = active.rsrq
                val curSinr = active.sinr
                if (curRsrq != null && curSinr != null) {
                    val rsrqOff = kotlin.math.abs(curRsrq - fp.rsrqMean)
                    val sinrOff = kotlin.math.abs(curSinr - fp.sinrMean)
                    val rsrqAnom = rsrqOff > maxOf(6.0, 4.0 * fp.rsrqStd)
                    val sinrAnom = sinrOff > maxOf(8.0, 4.0 * fp.sinrStd)
                    if (rsrqAnom && sinrAnom) {
                        hSignalBaseline = false
                        reasons.add("Huella RF incoherente con el historial (RSRQ/SINR muy desviados)")
                        score -= 15
                    }
                }
            }
        }

        // 14. Band Downgrade intra-tecnología (salto forzado a banda sub-GHz)
        // Defensivo: el orquestador ya bloquea el downgrade inter-tecnología (4G->2G/3G).
        // Esto cubre el hueco INTRA-LTE: un salto vertical injustificado desde una banda
        // alta urbana (1800/2100/2600 MHz) a una banda baja sub-GHz (800/900/700 MHz).
        // Las bandas sub-GHz penetran muros y cubren mucho radio con poca potencia, así
        // que un transmisor cercano que te "tira" a una de ellas mientras venías con
        // señal excelente en alta frecuencia es una anomalía. Solo se evalúa entre
        // celdas LTE/4G (la tabla de bandas es de LTE; el NR usa otra y no se mezcla).
        // Excepción física: si la señal venía cayendo de forma progresiva (estás entrando
        // a un garaje/sótano), el salto a banda baja es legítimo y NO se penaliza.
        // Usa BandPlan para mapear EARFCN->banda (el EARFCN NO es monótono con la frecuencia).
        run {
            val isLte = active.networkType.contains("4G") || active.networkType.contains("LTE")
            val curBand = active.band ?: active.arfcn?.let { BandPlan.earfcnToBandLte(it) }
            if (isLte && curBand != null && previousBand != null
                && BandPlan.isHighBand(previousBand) && BandPlan.isLowBand(curBand)) {
                // ¿Veníamos con buena señal en la banda alta? (downgrade injustificado)
                val prevStrong = previousDbm != null && previousDbm >= -90
                // ¿Estaba la señal degradándose progresivamente? -> movimiento físico legítimo.
                val degrading = isSignalDegrading(recentRegisteredDbm)
                if (prevStrong && !degrading) {
                    hBandDowngrade = false
                    val from = BandPlan.approxFreqMhz(previousBand) ?: 0
                    val to = BandPlan.approxFreqMhz(curBand) ?: 0
                    reasons.add("Downgrade de banda forzado (${from}MHz→${to}MHz, B$previousBand→B$curBand)")
                    score -= 25
                }
            }
        }

        // 15. Estabilidad de identidad RF (lifecycle): misma Cell ID con PCI/ARFCN mutados.
        // Una antena legítima mantiene su PCI y su ARFCN FIJOS. Si la misma Cell ID
        // (CID+MNC+TAC+MCC) presenta varios PCI o ARFCN, puede ser un clon reconfigurándose.
        // Se detecta aunque estés parado (no usa distancia, solo el historial de la celda),
        // cubriendo el hueco de H11 (que necesita cambio de posición).
        //
        // CLAVE anti-falsos-positivos (recencia): NO basta con que existan dos valores en el
        // historial de 30 días — una RECONFIGURACIÓN permanente del operador deja el valor
        // viejo en registros antiguos y el nuevo en los recientes (benigno). Solo es sospechoso
        // si DOS valores sólidos (>=2 apariciones en 30d, lo que descarta glitches puntuales)
        // SIGUEN apareciendo en la ventana reciente (48h): eso es la firma de un clon parpadeando
        // entre identidades AHORA, no de un cambio puntual ya asentado.
        rfStability?.let { st ->
            if (st.totalObservations >= 4) {
                // SOLO PCI. Los datos de campo demuestran que el ARFCN parpadea de forma benigna
                // por AGREGACIÓN DE PORTADORAS (el móvil atribuye a la celda servidora el ARFCN de
                // una portadora vecina): se vieron 7 celdas legítimas con el mismo puñado de ARFCN
                // (1301/2850/3600/6400) mezclados, mientras el PCI permanecía FIJO en cada una. El
                // PCI es la identidad real de capa física y nunca parpadea en una celda legítima,
                // así que es la única señal fiable para esta heurística. Un clon con PCI distinto se
                // detecta igual; uno que copie el PCI exacto cae fuera (limitación asumida a cambio
                // de eliminar de raíz los falsos positivos por ARFCN).
                val MIN_SHARE = 0.15
                fun solidSet(values: List<Pair<Int, Int>>): Set<Int> {
                    val total = values.sumOf { it.second }
                    if (total == 0) return emptySet()
                    return values.filter { it.second >= 2 && it.second.toDouble() / total >= MIN_SHARE }
                        .map { it.first }.toSet()
                }
                val solidPci = solidSet(st.distinctPci)
                val recentPci = st.recentDistinctPci.map { it.first }.toSet()
                // Sospechoso solo si >=2 PCI sólidos siguen activos recientemente (parpadeo real).
                val pciFlapping = solidPci.intersect(recentPci).size >= 2
                if (pciFlapping) {
                    hRfStability = false
                    reasons.add("Identidad RF inestable: misma Cell ID alternando PCI recientemente (posible clon)")
                    score -= 30
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
            if (!hBandDowngrade) add("bandDowngrade")
            if (!hRfStability) add("rfStability")
        }

        val threatProbability = BayesianScorer.calculate(
            failedList,
            active.verified.name,
            isNetworkLatencyAnomalous,
            neighborCount = neighbors.size,
            trustScore = reputation?.trustScore ?: -1
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
            signalBaselinePassed = hSignalBaseline,
            bandDowngradePassed = hBandDowngrade,
            rfStabilityPassed = hRfStability
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
