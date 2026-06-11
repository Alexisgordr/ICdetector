package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.VerificationStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobertura de las heurísticas 1-12 de ThreatAnalyzer que faltaban
 * (la 13 y la 14 ya están en ThreatAnalyzerTest y BandDowngradeTest).
 *
 * Todos los tests pasan currentLocation = null para correr en la JVM sin Android,
 * igual que ThreatAnalyzerTest. Nota honesta: las heurísticas 6 (rama de distancia
 * con tower coords) y 11 (geográfica) usan Location.distanceBetween (API de Android),
 * así que aquí solo se cubren sus casos "no dispara"; su disparo real requiere tests
 * instrumentados (Robolectric).
 *
 * Ubicación:  app/src/test/java/com/alexisgordr/icdetector/core/HeuristicsTest.kt
 * Ejecutar:   ./gradlew testDebugUnitTest
 */
class HeuristicsTest {

    // Celda servidora "limpia" por defecto. Cada test sobrescribe lo que necesita con copy().
    private fun active(
        dbm: Int = -90,
        networkType: String = "4G LTE",
        mnc: String = "01",
        tac: String = "100",
        mcc: String = "214",
        arfcn: Int? = 1500,            // B3, EARFCN válido
        timingAdvance: Int? = null,
        verified: VerificationStatus = VerificationStatus.PENDING,
        rsrq: Int? = null,
        sinr: Int? = null
    ) = CellData(
        isRegistered = true,
        networkType = networkType,
        cellId = "1000",
        mnc = mnc,
        tac = tac,
        dbm = dbm,
        mcc = mcc,
        arfcn = arfcn,
        timingAdvance = timingAdvance,
        verified = verified,
        rsrq = rsrq,
        sinr = sinr
    )

    private fun neighbor(dbm: Int, mnc: String = "01", tac: String = "100", mcc: String = "214") =
        CellData(
            isRegistered = false,
            networkType = "4G LTE",
            cellId = "2000",
            mnc = mnc,
            tac = tac,
            dbm = dbm,
            mcc = mcc
        )

    private fun analyze(
        active: CellData,
        neighbors: List<CellData> = emptyList(),
        isHardwareCipheringActive: Boolean = true,
        isHardwareCipheringAvailable: Boolean = false,
        cellChangeHistory: List<Pair<String, Long>> = emptyList(),
        isWifiActive: Boolean = false,
        isNetworkLatencyAnomalous: Boolean = false
    ) = ThreatAnalyzer.analyzeThreats(
        active = active,
        neighbors = neighbors,
        isHardwareCipheringActive = isHardwareCipheringActive,
        isHardwareCipheringAvailable = isHardwareCipheringAvailable,
        cellChangeHistory = cellChangeHistory,
        currentLocation = null,
        isWifiActive = isWifiActive,
        isNetworkLatencyAnomalous = isNetworkLatencyAnomalous
    ).heuristicReport

    // ---------- H1: Celda aislada ----------
    @Test fun `H1 dispara sin vecinas y senal fuerte`() {
        assertFalse(analyze(active(dbm = -70), neighbors = emptyList()).isolatedCellPassed)
    }
    @Test fun `H1 no dispara con vecinas presentes`() {
        assertTrue(analyze(active(dbm = -70), neighbors = listOf(neighbor(-95))).isolatedCellPassed)
    }
    @Test fun `H1 no dispara con senal debil aunque no haya vecinas`() {
        assertTrue(analyze(active(dbm = -100), neighbors = emptyList()).isolatedCellPassed)
    }

    // ---------- H2: Salto de potencia (>35 dB) ----------
    @Test fun `H2 dispara con salto mayor de 35dB sobre la vecina mas fuerte`() {
        // activa -60, vecina más fuerte -100 -> delta 40 > 35
        assertFalse(analyze(active(dbm = -60), neighbors = listOf(neighbor(-100))).powerJumpPassed)
    }
    @Test fun `H2 no dispara con delta moderado`() {
        // activa -60, vecina -80 -> delta 20
        assertTrue(analyze(active(dbm = -60), neighbors = listOf(neighbor(-80))).powerJumpPassed)
    }

    // ---------- H3: Inconsistencia MCC ----------
    @Test fun `H3 dispara con vecina de distinto MCC`() {
        assertFalse(analyze(active(mcc = "214"), neighbors = listOf(neighbor(-90, mcc = "208"))).mccConsistencyPassed)
    }
    @Test fun `H3 no dispara con mismo MCC`() {
        assertTrue(analyze(active(mcc = "214"), neighbors = listOf(neighbor(-90, mcc = "214"))).mccConsistencyPassed)
    }

    // ---------- H4: Multitud de MNCs (>4) ----------
    @Test fun `H4 dispara con mas de 4 MNCs distintos`() {
        val n = listOf(
            neighbor(-90, mnc = "02"), neighbor(-91, mnc = "03"),
            neighbor(-92, mnc = "04"), neighbor(-93, mnc = "05")
        ) // activa "01" + 4 distintos = 5 > 4
        assertFalse(analyze(active(mnc = "01"), neighbors = n).mncCountPassed)
    }
    @Test fun `H4 no dispara con 4 o menos MNCs`() {
        val n = listOf(neighbor(-90, mnc = "02"), neighbor(-91, mnc = "03"))
        assertTrue(analyze(active(mnc = "01"), neighbors = n).mncCountPassed)
    }

    // ---------- H5: Desviación TAC ----------
    @Test fun `H5 dispara si el TAC activo no esta entre las vecinas`() {
        assertFalse(analyze(active(tac = "100"), neighbors = listOf(neighbor(-90, tac = "200"))).tacDeviationPassed)
    }
    @Test fun `H5 no dispara si el TAC coincide con alguna vecina`() {
        assertTrue(analyze(active(tac = "100"), neighbors = listOf(neighbor(-90, tac = "100"))).tacDeviationPassed)
    }

    // ---------- H6: Timing Advance (rama de proximidad, sin tower coords) ----------
    @Test fun `H6 dispara por proximidad anomala (TA bajo, senal muy fuerte, no verificada)`() {
        assertFalse(
            analyze(active(dbm = -55, timingAdvance = 0, verified = VerificationStatus.PENDING)).taDistancePassed
        )
    }
    @Test fun `H6 no dispara si la celda esta verificada`() {
        assertTrue(
            analyze(active(dbm = -55, timingAdvance = 0, verified = VerificationStatus.VERIFIED)).taDistancePassed
        )
    }

    // ---------- H7: Vecinos fantasma ----------
    @Test fun `H7 dispara con activa fuerte y todas las vecinas casi muertas`() {
        // activa -60 (>= -65) y vecina <= -110
        assertFalse(analyze(active(dbm = -60), neighbors = listOf(neighbor(-115))).ghostNeighborsPassed)
    }
    @Test fun `H7 no dispara si alguna vecina tiene senal razonable`() {
        assertTrue(analyze(active(dbm = -60), neighbors = listOf(neighbor(-95))).ghostNeighborsPassed)
    }

    // ---------- H8: Sanity de ARFCN ----------
    @Test fun `H8 dispara con EARFCN LTE fuera del maximo teorico`() {
        assertFalse(analyze(active(networkType = "4G LTE", arfcn = 300000)).arfcnSanityPassed)
    }
    @Test fun `H8 NO dispara con EARFCN 0 en LTE (Banda 1 valida)`() {
        assertTrue(analyze(active(networkType = "4G LTE", arfcn = 0)).arfcnSanityPassed)
    }
    @Test fun `H8 dispara con NR-ARFCN 0 en 5G (no valido)`() {
        assertFalse(analyze(active(networkType = "5G", arfcn = 0)).arfcnSanityPassed)
    }
    @Test fun `H8 no dispara con EARFCN valido`() {
        assertTrue(analyze(active(networkType = "4G LTE", arfcn = 1500)).arfcnSanityPassed)
    }

    // ---------- H9: Cifrado de hardware (A5/0) ----------
    @Test fun `H9 dispara cuando el cifrado esta disponible pero inactivo`() {
        val r = analyze(active(), isHardwareCipheringActive = false, isHardwareCipheringAvailable = true)
        assertFalse(r.hardwareCipheringPassed)
    }
    @Test fun `H9 no marca fallo si el dato de cifrado no esta disponible`() {
        // No disponible -> no se penaliza; hardwareCipheringPassed refleja el estado activo (true)
        val r = analyze(active(), isHardwareCipheringActive = true, isHardwareCipheringAvailable = false)
        assertTrue(r.hardwareCipheringPassed)
    }

    // ---------- H10: Ping-Pong ----------
    @Test fun `H10 dispara con 3 o mas cambios en parado`() {
        val history = listOf("A" to 1L, "B" to 2L, "A" to 3L) // currentLocation null -> speed 0 -> parado
        assertFalse(analyze(active(), cellChangeHistory = history).pingPongPassed)
    }
    @Test fun `H10 no dispara con menos de 3 cambios`() {
        val history = listOf("A" to 1L, "B" to 2L)
        assertTrue(analyze(active(), cellChangeHistory = history).pingPongPassed)
    }

    // ---------- H11: Geográfica (solo casos "no dispara" en JUnit puro) ----------
    @Test fun `H11 pasa cuando no hay ubicacion ni historial`() {
        // currentLocation = null en analyze() -> analyzeMobileCellId devuelve passed
        assertTrue(analyze(active()).mobileCellIdPassed)
    }

    // ---------- H12: RF + latencia cross-layer ----------
    @Test fun `H12 marca anomalia con latencia anomala, senal fuerte y RSRQ malo`() {
        // No hay flag propio en el report; H12 solo resta score y añade reason.
        // Verificamos vía securityScore que la penalización (-20) se aplicó.
        val withAnomaly = ThreatAnalyzer.analyzeThreats(
            active = active(dbm = -60, rsrq = -18),
            neighbors = listOf(neighbor(-95)), // evita H1/H7 con una vecina de señal media
            isHardwareCipheringActive = true,
            cellChangeHistory = emptyList(),
            currentLocation = null,
            isNetworkLatencyAnomalous = true
        ).securityScore
        val withoutAnomaly = ThreatAnalyzer.analyzeThreats(
            active = active(dbm = -60, rsrq = -18),
            neighbors = listOf(neighbor(-95)),
            isHardwareCipheringActive = true,
            cellChangeHistory = emptyList(),
            currentLocation = null,
            isNetworkLatencyAnomalous = false
        ).securityScore
        assertTrue("la latencia anómala con RF malo debe restar score", withAnomaly < withoutAnomaly)
    }
    @Test fun `H12 no penaliza si la latencia es anomala pero el RF es bueno`() {
        // Sin rsrq/sinr malos, H12 no aplica aunque haya latencia anómala.
        val withAnomaly = ThreatAnalyzer.analyzeThreats(
            active = active(dbm = -60, rsrq = -8, sinr = 15),
            neighbors = listOf(neighbor(-95)),
            isHardwareCipheringActive = true,
            cellChangeHistory = emptyList(),
            currentLocation = null,
            isNetworkLatencyAnomalous = true
        ).securityScore
        val withoutAnomaly = ThreatAnalyzer.analyzeThreats(
            active = active(dbm = -60, rsrq = -8, sinr = 15),
            neighbors = listOf(neighbor(-95)),
            isHardwareCipheringActive = true,
            cellChangeHistory = emptyList(),
            currentLocation = null,
            isNetworkLatencyAnomalous = false
        ).securityScore
        assertTrue("con RF bueno, la latencia anómala no debe cambiar el score", withAnomaly == withoutAnomaly)
    }

    // ---------- H15: Estabilidad de identidad RF (lifecycle) ----------
    private fun analyzeRf(rf: com.alexisgordr.icdetector.models.CellRfStability?) =
        ThreatAnalyzer.analyzeThreats(
            active = active(dbm = -90),
            neighbors = listOf(neighbor(-95)),
            isHardwareCipheringActive = true,
            cellChangeHistory = emptyList(),
            currentLocation = null,
            rfStability = rf
        ).heuristicReport

    @Test fun `H15 dispara con dos PCI solidos que siguen activos recientemente (parpadeo)`() {
        // PCI 50 (x3) y PCI 120 (x2), ambos presentes en la ventana reciente -> clon parpadeando
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 5,
            distinctPci = listOf(50 to 3, 120 to 2),
            distinctArfcn = listOf(1500 to 5),
            recentDistinctPci = listOf(50 to 2, 120 to 2),
            recentDistinctArfcn = listOf(1500 to 5)
        )
        assertFalse(analyzeRf(rf).rfStabilityPassed)
    }

    @Test fun `H15 ya NO dispara por ARFCN (excluido por agregacion de portadoras)`() {
        // Dos ARFCN "sólidos" pero PCI estable. Tras excluir el ARFCN de la heurística (datos de
        // campo: el ARFCN parpadea por carrier aggregation), esto NO debe disparar.
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 6,
            distinctPci = listOf(50 to 6),
            distinctArfcn = listOf(1500 to 4, 6300 to 2),
            recentDistinctPci = listOf(50 to 3),
            recentDistinctArfcn = listOf(1500 to 2, 6300 to 2)
        )
        assertTrue(analyzeRf(rf).rfStabilityPassed)
    }

    @Test fun `H15 NO dispara en el caso real 79362081 (ARFCN 2850 al 16 por ciento)`() {
        // Caso real de campo: PCI 178 estable, ARFCN 3600 (79%) y 2850 (16%). El 2850 cruzaba el
        // 15% por muestra pequeña, pero al excluir ARFCN ya no dispara. PCI estable -> limpio.
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 19,
            distinctPci = listOf(178 to 19),
            distinctArfcn = listOf(3600 to 15, 2850 to 3, 1301 to 1),
            recentDistinctPci = listOf(178 to 8),
            recentDistinctArfcn = listOf(3600 to 6, 2850 to 2)
        )
        assertTrue(analyzeRf(rf).rfStabilityPassed)
    }

    @Test fun `H15 NO dispara tras reconfiguracion permanente (PCI viejo solo en registros antiguos)`() {
        // PCI 50 (x4, viejo, NO reciente) reemplazado por PCI 120 (x3, reciente). Solo 1 valor
        // sólido sigue activo recientemente -> reconfiguración benigna, no parpadeo.
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 7,
            distinctPci = listOf(50 to 4, 120 to 3),
            distinctArfcn = listOf(1500 to 7),
            recentDistinctPci = listOf(120 to 3),   // solo el nuevo aparece reciente
            recentDistinctArfcn = listOf(1500 to 3)
        )
        assertTrue(analyzeRf(rf).rfStabilityPassed)
    }

    @Test fun `H15 no dispara con PCI y ARFCN estables`() {
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 10,
            distinctPci = listOf(50 to 10),
            distinctArfcn = listOf(1500 to 10),
            recentDistinctPci = listOf(50 to 4),
            recentDistinctArfcn = listOf(1500 to 4)
        )
        assertTrue(analyzeRf(rf).rfStabilityPassed)
    }

    @Test fun `H15 no dispara con un PCI distinto que aparece solo una vez (glitch)`() {
        // PCI 120 aparece 1 sola vez -> no es sólido -> no debe disparar aunque sea reciente
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 6,
            distinctPci = listOf(50 to 5, 120 to 1),
            distinctArfcn = listOf(1500 to 6),
            recentDistinctPci = listOf(50 to 3, 120 to 1),
            recentDistinctArfcn = listOf(1500 to 3)
        )
        assertTrue(analyzeRf(rf).rfStabilityPassed)
    }

    @Test fun `H15 no dispara con historial insuficiente`() {
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 3,
            distinctPci = listOf(50 to 2, 120 to 1),
            distinctArfcn = listOf(1500 to 3),
            recentDistinctPci = listOf(50 to 2, 120 to 1),
            recentDistinctArfcn = listOf(1500 to 3)
        )
        assertTrue(analyzeRf(rf).rfStabilityPassed)
    }

    @Test fun `H15 NO dispara con ARFCN glitch minoritario (caso real 79362080)`() {
        // Caso real: ARFCN 2850 (101), 1301 (3 = 2.9%), 3600 (1). PCI 178 estable.
        // El 1301 aparece >=2 veces pero es solo el 2.9% -> ruido, no identidad. No debe disparar.
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 105,
            distinctPci = listOf(178 to 105),
            distinctArfcn = listOf(2850 to 101, 1301 to 3, 3600 to 1),
            recentDistinctPci = listOf(178 to 40),
            recentDistinctArfcn = listOf(2850 to 38, 1301 to 2)
        )
        assertTrue(analyzeRf(rf).rfStabilityPassed)
    }

    @Test fun `H15 dispara si hay dos PCI sustanciales y recientes (clon real)`() {
        // Dos PCI ambos sustanciales (60% y 40%) y recientes -> parpadeo de identidad real -> dispara.
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 20,
            distinctPci = listOf(178 to 12, 290 to 8),
            distinctArfcn = listOf(2850 to 20),
            recentDistinctPci = listOf(178 to 6, 290 to 4),
            recentDistinctArfcn = listOf(2850 to 10)
        )
        assertFalse(analyzeRf(rf).rfStabilityPassed)
    }

    @Test fun `H15 no dispara sin datos de estabilidad (null)`() {
        assertTrue(analyzeRf(null).rfStabilityPassed)
    }

    // --- Fingerprint RF (RSRQ/SINR), reportado por el flag de H13 ---

    private fun analyzeFp(
        rsrq: Int?, sinr: Int?,
        fp: com.alexisgordr.icdetector.models.CellRfFingerprint?
    ) = ThreatAnalyzer.analyzeThreats(
        active = active(dbm = -90, rsrq = rsrq, sinr = sinr),
        neighbors = listOf(neighbor(-95)),
        isHardwareCipheringActive = true,
        cellChangeHistory = emptyList(),
        currentLocation = null,
        rfFingerprint = fp
    ).heuristicReport

    @Test fun `fingerprint dispara si RSRQ y SINR se desvian mucho de la firma`() {
        val fp = com.alexisgordr.icdetector.models.CellRfFingerprint(
            sampleCount = 50, rsrqMean = -10.0, rsrqStd = 1.0, sinrMean = 12.0, sinrStd = 2.0
        )
        // rsrq -20 (off 10 > 6) y sinr -5 (off 17 > 8): ambos anómalos -> dispara.
        assertFalse(analyzeFp(rsrq = -20, sinr = -5, fp = fp).signalBaselinePassed)
    }

    @Test fun `fingerprint NO dispara si solo una metrica se desvia`() {
        val fp = com.alexisgordr.icdetector.models.CellRfFingerprint(
            sampleCount = 50, rsrqMean = -10.0, rsrqStd = 1.0, sinrMean = 12.0, sinrStd = 2.0
        )
        // rsrq muy desviado pero sinr normal -> exige AMBOS -> no dispara.
        assertTrue(analyzeFp(rsrq = -20, sinr = 12, fp = fp).signalBaselinePassed)
    }

    @Test fun `fingerprint NO dispara sin firma (dormido) ni con valores normales`() {
        assertTrue(analyzeFp(rsrq = -10, sinr = 12, fp = null).signalBaselinePassed)
        val fp = com.alexisgordr.icdetector.models.CellRfFingerprint(
            sampleCount = 50, rsrqMean = -10.0, rsrqStd = 1.0, sinrMean = 12.0, sinrStd = 2.0
        )
        assertTrue(analyzeFp(rsrq = -11, sinr = 11, fp = fp).signalBaselinePassed)
    }
}
