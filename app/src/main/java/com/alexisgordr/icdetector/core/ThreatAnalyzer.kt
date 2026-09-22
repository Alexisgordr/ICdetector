package com.alexisgordr.icdetector.core

import android.location.Location
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.TransitionCoherenceResult
import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.models.SignalBaseline
import com.alexisgordr.icdetector.models.CellRfStability
import com.alexisgordr.icdetector.models.CellReputation
import com.alexisgordr.icdetector.models.CellRfFingerprint
import com.alexisgordr.icdetector.models.VerificationStatus
import com.alexisgordr.icdetector.models.RadioTech

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
    internal fun getDynamicLocationThreshold(
        neighbors: List<CellData>,
        radioTech: RadioTech
    ): Double {
        val count = neighbors.size
        return when {
            count >= 12 -> 2000.0
            count >= 6  -> 4000.0
            count >= 3  -> 8000.0
            radioTech == RadioTech.NR -> 5000.0
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

        val threshold = getDynamicLocationThreshold(neighbors, active.radioTech)

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
        isNetworkLatencyAvailable: Boolean = true,
        signalBaseline: SignalBaseline? = null,
        previousBand: Int? = null,
        previousDbm: Int? = null,
        recentRegisteredDbm: List<Int> = emptyList(),
        rfStability: CellRfStability? = null,
        reputation: CellReputation? = null,
        rfFingerprint: CellRfFingerprint? = null,
        transitionCoherence: TransitionCoherenceResult = TransitionCoherenceResult(),
        isolatedCellConfirmed: Boolean = true
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
        var hLatencyCorrelation = true
        var hTransitionCoherence = true

        var eIsolated = !isWifiActive
        var ePowerJump = false
        var eMcc = false
        var eMncCount = false
        var eTac = false
        var eTa = false
        var eGhost = false
        var eArfcn = false
        val eCiphering = isHardwareCipheringAvailable
        val ePingPong = true
        var eMobileCellId = false
        var eLatencyCorrelation = false
        var eSignalBaseline = false
        var eBandDowngrade = false
        var eRfStability = false
        val eTransitionCoherence = transitionCoherence.status != HeuristicStatus.NOT_EVALUATED

        // 1. Neighbor analysis
        if (!isWifiActive && neighbors.isEmpty() && active.dbm >= -80 && isolatedCellConfirmed) {
            hIsolated = false
            reasons.add("Celda aislada")
            score -= 15
        }

        // 2. Signal Gap analysis
        val nextStrongest = neighbors.maxOfOrNull { it.dbm }
        ePowerJump = !isWifiActive && nextStrongest != null
        if (!isWifiActive && nextStrongest != null && active.dbm >= -75 && (active.dbm - nextStrongest > 35)) {
            hPowerJump = false
            reasons.add("Salto potencia (>35dB)")
            score -= 20
        }

        // 3. MNC/MCC Inconsistency
        eMcc = active.mcc != "N/A" && neighbors.any { it.mcc != "N/A" }
        val differentMcc = neighbors.filter { it.mcc != "N/A" && active.mcc != "N/A" && it.mcc != active.mcc }
        if (differentMcc.isNotEmpty()) {
            hMcc = false
            reasons.add("Inconsistencia MCC")
            score -= 30
        }

        // 4. Multiple MNCs in area
        val uniqueMncs = (neighbors.asSequence().map { it.mnc } + active.mnc)
            .filter { it != "N/A" }.distinct().toList()
        eMncCount = active.mnc != "N/A" && neighbors.any { it.mnc != "N/A" }
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
            eTac = neighborTacs.isNotEmpty()
            if (neighborTacs.isNotEmpty() && !neighborTacs.contains(active.tac)) {
                hTac = false
                reasons.add("Desviación TAC")
                score -= 20
            }
        }

        // 6. Timing Advance Audit
        //
        // v2.1 — La distancia implícita se calcula a partir de la UNIDAD que viaja con el valor
        // (CellData.timingAdvanceUnit, fijada por CellParser), no del nombre de la red.
        //
        // Antes el multiplicador se elegía con `networkType.contains("5G") && !contains("NSA")`.
        // Esa cadena viene de TelephonyDisplayInfo: describe el icono que enseña el móvil, no la
        // clase de CellInfo de la que se leyó el TA. En el historial de campo hay 54 celdas que
        // aparecen etiquetadas unas veces como 4G y otras como 5G sin cambiar de identidad, así
        // que las dos cosas se desincronizan de forma rutinaria — y un índice LTE tratado como
        // microsegundos (o al revés) desplaza la distancia por un factor cercano a 2. H6 lleva la
        // penalización más alta del sistema (-40) y la LR más alta tras el cifrado (7.0): es el
        // último sitio donde conviene deducir una unidad.
        //
        // Si la unidad no es determinable (valor raspado del toString() del fabricante), no hay
        // conversión y H6 NO JUZGA. Abstenerse es correcto: una geometría inventada aquí es peor
        // que no tener geometría.
        val taMeters = active.timingAdvance?.let { active.timingAdvanceUnit.toMeters(it) }
        eTa = taMeters != null
        if (taMeters != null) {
            if (active.lat != null && active.lon != null && currentLocation != null) {
                if (taMeters > 0) {
                    val results = FloatArray(1)
                    Location.distanceBetween(
                        currentLocation.latitude, currentLocation.longitude,
                        active.lat, active.lon, results
                    )
                    // La antena dice estar a >2 km pero el TA implica <500 m: incoherencia física.
                    if (results[0] > 2000 && taMeters < 500) {
                        hTa = false
                        reasons.add("Suplantación TA")
                        score -= 40
                    }
                }
            } else {
                // Sin posición de la antena: solo el caso "estoy pegado al emisor". El umbral de
                // 100 m equivale exactamente al `ta <= 1` anterior en LTE (0 m y 78 m), y ahora
                // se expresa en metros para que signifique lo mismo en cualquier tecnología.
                //
                // No hace falta excluir NR aquí: si hemos llegado a este punto es porque la unidad
                // produjo metros, y NR nunca los produce (ver TimingAdvanceUnit.NR_RAW).
                //
                // Aquí había una excepción: la celda se libraba de la penalización si estaba
                // VERIFIED. Se ha quitado — y no por poco importante, sino por lo contrario. Era la
                // ÚLTIMA vía por la que el estado de verificación entraba en la puntuación, y la
                // hacía entrar en silencio: misma celda, mismo TA, misma señal, distinto score
                // según lo que contestara una base pública. Con ella dentro, la pregunta que la
                // fase de recolección tiene que responder —¿aporta señal la verificación externa?—
                // se contestaba en parte a sí misma.
                //
                // Desde v2.1 `ThreatAnalyzer` **no lee `active.verified` en ninguna heurística**.
                // El detector y la etiqueta externa son ortogonales, y por eso podrán cruzarse.
                if (taMeters <= 100 && active.dbm >= -60) {
                    hTa = false
                    reasons.add("Proximidad anómala (TA)")
                    score -= 15
                }
            }
        }

        // 7. Ghost Cells Check
        // Umbral subido a -65dBm y vecinas a -110dBm para reducir
        // falsos positivos en zonas rurales con macroceldas
        eGhost = !isWifiActive && neighbors.isNotEmpty()
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
            if (active.radioTech == RadioTech.NR) {
                eArfcn = true
                if (arfcn > 3279165 || arfcn == 0) {
                    hArfcn = false
                    reasons.add("Frecuencia (ARFCN) 5G sospechosa")
                    score -= 15
                }
            } else if (active.radioTech == RadioTech.LTE) {
                eArfcn = true
                if (arfcn > 262143) {  // EARFCN 0 es válido (Banda 1, 2110 MHz); el "no disponible" llega como > 262143
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
        eMobileCellId = currentLocation != null && active.cellId != "N/A" &&
            preloadedHistory.any { it.lat != null && it.lon != null }
        val (hMobileOk, hMobilePenalty, hMobileReason) = analyzeMobileCellId(
            active, currentLocation, preloadedHistory, neighbors
        )
        if (!hMobileOk) {
            hMobileCellId = false
            reasons.add(hMobileReason ?: "Consistencia geográfica fallida")
            score -= hMobilePenalty
        }

        // 12. RF Quality + Latency Cross-Layer Correlation (Experimental)
        eLatencyCorrelation = !isWifiActive && isNetworkLatencyAvailable &&
            (active.rsrq != null || active.sinr != null)
        if (!isWifiActive && isNetworkLatencyAvailable && isNetworkLatencyAnomalous && active.dbm >= -70) {
            val rsrqAnomalous = active.rsrq != null && active.rsrq <= -15
            val sinrAnomalous = active.sinr != null && active.sinr <= 0
            if (rsrqAnomalous || sinrAnomalous) {
                hLatencyCorrelation = false
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
        eSignalBaseline = !isWifiActive && (signalBaseline != null ||
            (rfFingerprint != null && active.rsrq != null && active.sinr != null))
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
            val isLte = active.radioTech == RadioTech.LTE
            val curBand = active.band ?: active.arfcn?.let { BandPlan.earfcnToBandLte(it) }
            eBandDowngrade = isLte && curBand != null && previousBand != null && previousDbm != null
            if (isLte && curBand != null && previousBand != null
                && BandPlan.isHighBand(previousBand) && BandPlan.isLowBand(curBand)) {
                // ¿Veníamos con buena señal en la banda alta? (downgrade injustificado)
                val prevStrong = previousDbm != null && previousDbm >= -90
                // ¿Estaba la señal degradándose progresivamente? -> movimiento físico legítimo.
                val degrading = isSignalDegrading(recentRegisteredDbm)

                // v2.1 — DOS CONDICIONES NUEVAS, a partir de datos de campo.
                //
                // En 59 días, 12 de las 34 penalizaciones del historial fueron transiciones de
                // banda alta a sub-GHz con la celda nueva a -101, -108, -111, incluso -116 dBm.
                // Eso no es un downgrade forzado: es salir de la cobertura de una microcelda
                // urbana y caer a la capa sub-GHz, el handover más corriente que existe en LTE.
                //
                // El modelo de ataque que esta heurística persigue es otro: un transmisor táctico
                // CERCANO que te arrastra a una banda de gran alcance. Si te arrastra, es porque
                // te ofrece una señal buena — no una agonizante. De ahí:
                //
                //   newStrong           -> la celda nueva se ve FUERTE en términos absolutos.
                //   notCoverageFallback -> y no es más débil que la que tenías, que es la firma
                //                          inequívoca de "he perdido la celda anterior".
                //
                // Ambas son condiciones NECESARIAS del ataque, así que no se pierde detección
                // real; lo que se elimina es el handover rutinario a la capa de cobertura.
                val newStrong = active.dbm >= -95
                val notCoverageFallback = previousDbm != null && active.dbm >= previousDbm

                if (prevStrong && newStrong && notCoverageFallback && !degrading) {
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
                eRfStability = true
                // SOLO PCI, y SOLO DENTRO DE LA MISMA PORTADORA (ARFCN).
                //
                // v2.0 ya había descartado el ARFCN como señal de identidad porque parpadea de
                // forma benigna con AGREGACIÓN DE PORTADORAS: el módem atribuye a la celda
                // servidora el ARFCN de una portadora secundaria. v2.1 corrige el hueco que
                // quedaba: el módem hace lo MISMO con el PCI. En 59 días de datos reales, las
                // celdas con varios PCI mostraban correlación PERFECTA entre PCI y ARFCN (PCI 200
                // siempre en ARFCN 6400, PCI 473 siempre en 3600, sin un solo cruce). No eran dos
                // identidades alternándose: era una antena vista por dos portadoras. La regla
                // global disparaba ahí un -30 que era un falso positivo puro.
                //
                // Comparando dentro de cada portadora, ese caso desaparece y la detección real no
                // se pierde: un clon que reconfigura su PCI lo hace en su propia portadora, así
                // que sigue apareciendo como dos PCI sólidos en el MISMO ARFCN.
                val MIN_SHARE = 0.15
                fun solidSet(values: List<Pair<Int, Int>>): Set<Int> {
                    val total = values.sumOf { it.second }
                    if (total == 0) return emptySet()
                    return values.filter { it.second >= 2 && it.second.toDouble() / total >= MIN_SHARE }
                        .map { it.first }.toSet()
                }

                // Sospechoso solo si >=2 PCI sólidos siguen activos recientemente (parpadeo real).
                val pciFlapping = if (st.pciByArfcn.isNotEmpty()) {
                    st.pciByArfcn.any { (carrier, counts) ->
                        val solid = solidSet(counts)
                        if (solid.size < 2) return@any false
                        val recent = st.recentPciByArfcn[carrier].orEmpty().map { it.first }.toSet()
                        solid.intersect(recent).size >= 2
                    }
                } else {
                    // Compatibilidad: historial sin desglose por portadora (p. ej. tests o filas
                    // muy antiguas sin ARFCN). Se mantiene la regla global de v2.0.
                    val solidPci = solidSet(st.distinctPci)
                    val recentPci = st.recentDistinctPci.map { it.first }.toSet()
                    solidPci.intersect(recentPci).size >= 2
                }

                if (pciFlapping) {
                    hRfStability = false
                    reasons.add("Identidad RF inestable: misma Cell ID alternando PCI en la misma portadora (posible clon)")
                    score -= 30
                }
            }
        }

        // 16. Coherencia de transición celular. La geometría y la madurez se calculan fuera del
        // motor para mantener este analizador determinista. Peso bajo: nunca acusa por sí sola;
        // solo refuerza otras señales si un handover contradice GPS, vecinas e historial local.
        if (transitionCoherence.status == HeuristicStatus.FAILED) {
            hTransitionCoherence = false
            reasons.add("Transición celular incoherente")
            score -= 15
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
            if (!hTransitionCoherence) add("transitionCoherence")
        }

        // El estado de verificación se le sigue pasando al bayesiano, pero sus razones de
        // verosimilitud están todas en 1.0 (neutro) desde v2.1: el parámetro queda como el hueco
        // donde encajarán las razones MEDIDAS cuando la fase de recolección permita estimarlas.
        val anomalyConfidence = BayesianScorer.calculate(
            failedList,
            active.verified.name,
            isNetworkLatencyAnomalous,
            neighborCount = neighbors.size,
            trustScore = reputation?.trustScore ?: -1
        )

        // La verificación externa NO puntúa. Se observa y se registra — v2.1.
        //
        // Hasta aquí una celda verificada se llevaba +15 y una no encontrada -10. Las dos cifras
        // han caído por el mismo motivo: **no medían la antena, medían la base de datos**.
        //
        //  - El -10 se aplicaba a cualquier celda que WiGLE/OpenCellID no conocieran, y esas bases
        //    son colaborativas e irregulares: en una zona poco mapeada lo cobraban TODAS las
        //    antenas, legítimas incluidas. Una penalización que le toca a todo el mundo no
        //    distingue a nadie.
        //  - El +15, en una celda limpia, no subía nada (ya estaba en 100): lo que hacía era dar un
        //    colchón de 15 puntos contra penalizaciones reales de las heurísticas. Es decir, estar
        //    en una base colaborativa excusaba un comportamiento de radio anómalo. Al revés de como
        //    debería ser.
        //
        // Y hay una razón metodológica que pesa más que las dos: durante la fase de recolección
        // queremos AVERIGUAR si el estado de verificación aporta señal. Eso no se puede medir si ya
        // está metido dentro de la puntuación que sirve de referencia — se estaría contrastando el
        // dato consigo mismo. Queda como etiqueta independiente (columna `Verified` del CSV) junto
        // a un score que sale solo de las heurísticas locales. Al final de los tres meses se podrá
        // cruzar una cosa con la otra y contestar la pregunta con datos.
        //
        // La ortogonalidad es completa: ninguna heurística lee `active.verified`. La última que lo
        // hacía era H6 —una celda verificada no disparaba la rama de proximidad del TA— y se quitó
        // por esto mismo. `VerificationNeutralityTest` lo comprueba con una celda que SÍ dispara
        // esa rama, que es el caso donde el acoplamiento se escondía.
        //
        // El motivo tampoco se añade ya a `reasons`: sin penalización no es una observación sobre
        // la antena, y aparecería en pantalla como si algo fuera mal. Dónde consta: en la columna
        // `Verified` del historial y en la cabecera, que lo dice con todas las letras.

        val finalScore = score.coerceIn(0, 100)
        val isSuspicious = finalScore < 70

        fun status(evaluated: Boolean, passed: Boolean): HeuristicStatus = when {
            !evaluated -> HeuristicStatus.NOT_EVALUATED
            passed -> HeuristicStatus.PASSED
            else -> HeuristicStatus.FAILED
        }

        val report = HeuristicReport(
            isolatedCell = status(eIsolated, hIsolated),
            powerJump = status(ePowerJump, hPowerJump),
            mccConsistency = status(eMcc, hMcc),
            mncCount = status(eMncCount, hMncCount),
            tacDeviation = status(eTac, hTac),
            taDistance = status(eTa, hTa),
            ghostNeighbors = status(eGhost, hGhost),
            arfcnSanity = status(eArfcn, hArfcn),
            hardwareCiphering = status(eCiphering, isHardwareCipheringActive),
            pingPong = status(ePingPong, hPingPong),
            mobileCellId = status(eMobileCellId, hMobileCellId),
            latencyCorrelation = status(eLatencyCorrelation, hLatencyCorrelation),
            signalBaseline = status(eSignalBaseline, hSignalBaseline),
            bandDowngrade = status(eBandDowngrade, hBandDowngrade),
            rfStability = status(eRfStability, hRfStability),
            transitionCoherence = status(eTransitionCoherence, hTransitionCoherence)
        )

        return active.copy(
            isSuspicious = isSuspicious,
            suspiciousReason = if (reasons.isNotEmpty()) reasons.joinToString(" | ") else null,
            heuristicReport = report,
            securityScore = finalScore,
            anomalyConfidence = anomalyConfidence,
            transitionCoherence = transitionCoherence
        )
    }
}
