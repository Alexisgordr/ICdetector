package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.VerificationStatus
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        radioTech: RadioTech = RadioTech.LTE,
        mnc: String = "01",
        tac: String = "100",
        mcc: String = "214",
        arfcn: Int? = 1500,            // B3, EARFCN válido
        timingAdvance: Int? = null,
        // v2.1: la unidad del TA viaja con el valor. Por defecto se simula LTE, que es lo que
        // hacían implícitamente los tests anteriores (multiplicador 78 m/índice).
        timingAdvanceUnit: com.alexisgordr.icdetector.models.TimingAdvanceUnit =
            com.alexisgordr.icdetector.models.TimingAdvanceUnit.LTE_INDEX,
        verified: VerificationStatus = VerificationStatus.PENDING,
        rsrq: Int? = null,
        sinr: Int? = null
    ) = CellData(
        isRegistered = true,
        networkType = networkType,
        radioTech = radioTech,
        cellId = "1000",
        mnc = mnc,
        tac = tac,
        dbm = dbm,
        mcc = mcc,
        arfcn = arfcn,
        timingAdvance = timingAdvance,
        timingAdvanceUnit = timingAdvanceUnit,
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
        isNetworkLatencyAnomalous: Boolean = false,
        isNetworkLatencyAvailable: Boolean = true
    ) = ThreatAnalyzer.analyzeThreats(
        active = active,
        neighbors = neighbors,
        isHardwareCipheringActive = isHardwareCipheringActive,
        isHardwareCipheringAvailable = isHardwareCipheringAvailable,
        cellChangeHistory = cellChangeHistory,
        currentLocation = null,
        isWifiActive = isWifiActive,
        isNetworkLatencyAnomalous = isNetworkLatencyAnomalous,
        isNetworkLatencyAvailable = isNetworkLatencyAvailable
    ).heuristicReport

    @Test fun `el estado se recalcula de failed a passed en la siguiente lectura`() {
        val failed = analyze(active(dbm = -70), neighbors = emptyList())
        val passedFiveSecondsLater = analyze(active(dbm = -70), neighbors = listOf(neighbor(-85)))

        assertEquals(HeuristicStatus.FAILED, failed.isolatedCell)
        assertEquals(HeuristicStatus.PASSED, passedFiveSecondsLater.isolatedCell)
    }

    @Test fun `una regla sin datos figura como no evaluada y no como passed`() {
        val report = analyze(active(), neighbors = emptyList())

        assertEquals(HeuristicStatus.NOT_EVALUATED, report.powerJump)
        assertEquals(HeuristicStatus.NOT_EVALUATED, report.taDistance)
        assertEquals(HeuristicStatus.NOT_EVALUATED, report.hardwareCiphering)
    }

    @Test fun `latencia cambia entre no evaluada failed y passed`() {
        val noData = analyze(
            active(dbm = -65, rsrq = -18),
            isNetworkLatencyAnomalous = false,
            isNetworkLatencyAvailable = false
        )
        val failed = analyze(
            active(dbm = -65, rsrq = -18),
            isNetworkLatencyAnomalous = true,
            isNetworkLatencyAvailable = true
        )
        val passed = analyze(
            active(dbm = -65, rsrq = -18),
            isNetworkLatencyAnomalous = false,
            isNetworkLatencyAvailable = true
        )

        assertEquals(HeuristicStatus.NOT_EVALUATED, noData.latencyCorrelation)
        assertEquals(HeuristicStatus.FAILED, failed.latencyCorrelation)
        assertEquals(HeuristicStatus.PASSED, passed.latencyCorrelation)
    }

    @Test fun `latencia no disponible nunca penaliza aunque el flag anomalous sea contradictorio`() {
        val result = ThreatAnalyzer.analyzeThreats(
            active = active(dbm = -65, rsrq = -18),
            neighbors = listOf(neighbor(-75)),
            isHardwareCipheringActive = true,
            cellChangeHistory = emptyList(),
            currentLocation = null,
            isNetworkLatencyAnomalous = true,
            isNetworkLatencyAvailable = false
        )

        assertEquals(HeuristicStatus.NOT_EVALUATED, result.heuristicReport.latencyCorrelation)
        assertEquals(100, result.securityScore)
        assertNull(result.suspiciousReason)
    }

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
    /**
     * v2.1 — H6 dispara igual esté la celda verificada o no.
     *
     * Este test afirmaba lo contrario: que una celda VERIFIED se libraba de la penalización de
     * proximidad. Esa excepción era la última vía por la que el estado de verificación entraba en
     * la puntuación, y en silencio. Se quitó para que el detector y la etiqueta externa sean
     * ortogonales y puedan cruzarse al final de la fase de recolección sin circularidad.
     * Ver VerificationNeutralityTest.
     */
    @Test fun `H6 dispara igual aunque la celda este verificada`() {
        assertFalse(
            analyze(active(dbm = -55, timingAdvance = 0, verified = VerificationStatus.VERIFIED)).taDistancePassed
        )
    }

    // ---------- H6 y la unidad del Timing Advance ----------
    private val TA_UNKNOWN = com.alexisgordr.icdetector.models.TimingAdvanceUnit.UNKNOWN
    private val TA_NR_RAW = com.alexisgordr.icdetector.models.TimingAdvanceUnit.NR_RAW
    private val TA_LTE = com.alexisgordr.icdetector.models.TimingAdvanceUnit.LTE_INDEX

    @Test fun `H6 NO juzga si la unidad del TA es desconocida`() {
        // Valor raspado del toString() del fabricante: no se sabe si es índice o microsegundos.
        // Abstenerse es lo correcto — inventar la unidad erraba por un factor cercano a 2.
        assertTrue(
            "con unidad desconocida H6 debe abstenerse, no penalizar",
            analyze(active(dbm = -55, timingAdvance = 0, timingAdvanceUnit = TA_UNKNOWN,
                verified = VerificationStatus.PENDING)).taDistancePassed
        )
    }

    @Test fun `H6 NO juzga con un TA de NR (conversion no defendible)`() {
        // v2.1: el valor de getTimingAdvanceMicros() no admite una conversión defendible —su
        // documentación declara microsegundos pero con el rango de un índice LTE (0..1282) y
        // citando una especificación de LTE, y llega por reflexión. H6 se abstiene, igual que con
        // una unidad desconocida.
        assertTrue(
            "NR no debe producir geometría mientras la conversión no sea verificable",
            analyze(active(dbm = -55, timingAdvance = 0, timingAdvanceUnit = TA_NR_RAW,
                verified = VerificationStatus.PENDING)).taDistancePassed
        )
        assertTrue(
            analyze(active(dbm = -55, timingAdvance = 3, timingAdvanceUnit = TA_NR_RAW,
                verified = VerificationStatus.PENDING)).taDistancePassed
        )
    }

    @Test fun `H6 conserva el comportamiento LTE exacto de v2 1 (TA 0 y 1 disparan, 2 no)`() {
        // El umbral pasó de `ta <= 1` a `metros <= 100`. En LTE son equivalentes: 0 m, 78 m, 156 m.
        assertFalse(analyze(active(dbm = -55, timingAdvance = 0, timingAdvanceUnit = TA_LTE,
            verified = VerificationStatus.PENDING)).taDistancePassed)
        assertFalse(analyze(active(dbm = -55, timingAdvance = 1, timingAdvanceUnit = TA_LTE,
            verified = VerificationStatus.PENDING)).taDistancePassed)
        assertTrue(analyze(active(dbm = -55, timingAdvance = 2, timingAdvanceUnit = TA_LTE,
            verified = VerificationStatus.PENDING)).taDistancePassed)
    }

    @Test fun `solo las unidades con conversion firme producen metros`() {
        val gsm = com.alexisgordr.icdetector.models.TimingAdvanceUnit.GSM_INDEX

        // LTE: 16·Ts de ida y vuelta ≈ 78,12 m por índice. El extremo del rango (1282) da los
        // ~100 km que son el radio máximo real de una celda LTE: la física encaja.
        assertEquals(0, TA_LTE.toMeters(0)!!)
        assertEquals(78, TA_LTE.toMeters(1)!!)
        assertEquals(99_996, TA_LTE.toMeters(1282)!!)   // ≈100 km, el radio máximo de una celda LTE

        // GSM: periodo de bit de 3,69 µs ida y vuelta ≈ 554 m.
        assertEquals(554, gsm.toMeters(1)!!)

        // NR y desconocida: sin conversión defendible, no hay metros. Ninguna cantidad de
        // ingenio en la heurística arregla un número cuya unidad no se puede afirmar.
        assertNull("NR no debe convertirse mientras la unidad no sea verificable",
            TA_NR_RAW.toMeters(1))
        assertNull(TA_NR_RAW.toMeters(1282))
        assertNull(TA_UNKNOWN.toMeters(5))

        // Un TA negativo no es físico en ninguna unidad.
        assertNull("un TA negativo no es físico", TA_LTE.toMeters(-1))
        assertNull(gsm.toMeters(-1))

        // Y la propiedad que las heurísticas usan para preguntarlo de un vistazo.
        assertTrue(TA_LTE.isUsableForGeometry)
        assertTrue(gsm.isUsableForGeometry)
        assertFalse(TA_NR_RAW.isUsableForGeometry)
        assertFalse(TA_UNKNOWN.isUsableForGeometry)
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
        assertFalse(analyze(active(networkType = "5G", radioTech = RadioTech.NR, arfcn = 0)).arfcnSanityPassed)
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

    // ---------- v2.1: H15 compara PCI DENTRO de la misma portadora (ARFCN) ----------

    @Test fun `H15 NO dispara si cada PCI vive en su propia portadora (caso real 79482913)`() {
        // Caso real del historial de campo, el ÚNICO que disparaba H15 en 59 días:
        // PCI 200 visto SIEMPRE en ARFCN 6400 y PCI 473 visto SIEMPRE en ARFCN 3600, sin un solo
        // cruce. Es agregación de portadoras —una antena vista por dos portadoras—, no un clon.
        // Con la regla global de v2.0 esto costaba -30 puntos de forma injustificada.
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 13,
            distinctPci = listOf(200 to 6, 473 to 7),
            distinctArfcn = listOf(6400 to 6, 3600 to 7),
            recentDistinctPci = listOf(200 to 3, 473 to 3),
            recentDistinctArfcn = listOf(6400 to 3, 3600 to 3),
            pciByArfcn = mapOf(6400 to listOf(200 to 6), 3600 to listOf(473 to 7)),
            recentPciByArfcn = mapOf(6400 to listOf(200 to 3), 3600 to listOf(473 to 3))
        )
        assertTrue(
            "dos PCI correlacionados 1:1 con dos ARFCN son agregación de portadoras, no un clon",
            analyzeRf(rf).rfStabilityPassed
        )
    }

    @Test fun `H15 SIGUE disparando si los dos PCI alternan en la MISMA portadora`() {
        // Mismo reparto global de PCI que el test anterior, pero ambos valores conviven en la
        // misma portadora: eso ya no se explica por agregación. Es el clon que H15 busca.
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 13,
            distinctPci = listOf(200 to 6, 473 to 7),
            distinctArfcn = listOf(6400 to 13),
            recentDistinctPci = listOf(200 to 3, 473 to 3),
            recentDistinctArfcn = listOf(6400 to 6),
            pciByArfcn = mapOf(6400 to listOf(200 to 6, 473 to 7)),
            recentPciByArfcn = mapOf(6400 to listOf(200 to 3, 473 to 3))
        )
        assertFalse(
            "dos PCI sólidos y recientes en la misma portadora siguen siendo sospechosos",
            analyzeRf(rf).rfStabilityPassed
        )
    }

    @Test fun `H15 no dispara si el segundo PCI de la portadora no es reciente`() {
        // Reconfiguración asentada: el PCI viejo solo aparece en registros antiguos.
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 12,
            distinctPci = listOf(200 to 5, 473 to 7),
            distinctArfcn = listOf(6400 to 12),
            recentDistinctPci = listOf(473 to 4),
            recentDistinctArfcn = listOf(6400 to 4),
            pciByArfcn = mapOf(6400 to listOf(200 to 5, 473 to 7)),
            recentPciByArfcn = mapOf(6400 to listOf(473 to 4))
        )
        assertTrue(analyzeRf(rf).rfStabilityPassed)
    }

    @Test fun `H15 agrupa bajo portadora desconocida las filas antiguas sin ARFCN`() {
        // Historial anterior a la migración v6 (sin ARFCN): los PCI se comparan entre ellos bajo
        // UNKNOWN_ARFCN, así que un parpadeo real se sigue detectando.
        val unknown = com.alexisgordr.icdetector.models.CellRfStability.UNKNOWN_ARFCN
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 10,
            distinctPci = listOf(50 to 6, 120 to 4),
            distinctArfcn = emptyList(),
            recentDistinctPci = listOf(50 to 3, 120 to 2),
            recentDistinctArfcn = emptyList(),
            pciByArfcn = mapOf(unknown to listOf(50 to 6, 120 to 4)),
            recentPciByArfcn = mapOf(unknown to listOf(50 to 3, 120 to 2))
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
