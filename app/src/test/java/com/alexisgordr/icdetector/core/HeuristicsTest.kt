package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicReport
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.MccAbstention
import com.alexisgordr.icdetector.models.ServiceRegistrationState
import com.alexisgordr.icdetector.models.ServiceStateSnapshot
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
    private val NOW = 1_700_000_000_000L


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

    private fun neighbor(dbm: Int, mnc: String = "01", tac: String = "100", mcc: String = "214", radio: RadioTech = RadioTech.LTE) =
        CellData(
            isRegistered = false,
            networkType = "4G LTE",
            cellId = "2000",
            mnc = mnc,
            tac = tac,
            dbm = dbm,
            mcc = mcc,
            radioTech = radio
        )

    private fun analyze(
        active: CellData,
        neighbors: List<CellData> = emptyList(),
        cellChangeHistory: List<Pair<String, Long>> = emptyList(),
        isWifiActive: Boolean = false,
        nowMs: Long = NOW
    ) = ThreatAnalyzer.analyzeThreats(
        active = active,
        neighbors = neighbors,
        cellChangeHistory = cellChangeHistory,
        currentLocation = null,
        isWifiActive = isWifiActive,
        nowMs = nowMs
    ).heuristicReport

    @Test fun `el estado se recalcula de failed a passed en la siguiente lectura`() {
        val failed = analyze(active(dbm = -70), neighbors = emptyList())
        val passedFiveSecondsLater = analyze(active(dbm = -70), neighbors = listOf(neighbor(-85)))

        assertEquals(HeuristicStatus.FAILED, failed.isolatedCell)
        assertEquals(HeuristicStatus.PASSED, passedFiveSecondsLater.isolatedCell)
    }

    // ---------- 3.0 (#28): H1 pendiente de confirmar ----------
    private fun analyzeIsolation(dbm: Int, confirmed: Boolean, neighbors: List<CellData> = emptyList(), wifi: Boolean = false) =
        ThreatAnalyzer.analyzeThreats(
            active = active(dbm = dbm), neighbors = neighbors, cellChangeHistory = emptyList(), currentLocation = null, isWifiActive = wifi,
            isolatedCellConfirmed = confirmed, nowMs = NOW
        ).heuristicReport.isolatedCell

    @Test fun `H1 sin vecinas y senal fuerte queda N-A mientras no se confirma`() {
        assertEquals(HeuristicStatus.NOT_EVALUATED, analyzeIsolation(-70, confirmed = false))
    }

    @Test fun `H1 falla en la tercera entrega confirmada`() {
        assertEquals(HeuristicStatus.FAILED, analyzeIsolation(-70, confirmed = true))
    }

    @Test fun `H1 con vecinas se supera y con Wi-Fi sigue N-A`() {
        assertEquals(HeuristicStatus.PASSED, analyzeIsolation(-70, confirmed = false, neighbors = listOf(neighbor(-85))))
        assertEquals(HeuristicStatus.NOT_EVALUATED, analyzeIsolation(-70, confirmed = true, wifi = true))
    }

    @Test fun `H1 sin vecinas y senal debil se evalua y se supera`() {
        // La condición que busca la regla es una celda FUERTE y sola; débil y sola es normal en zona rural.
        assertEquals(HeuristicStatus.PASSED, analyzeIsolation(-100, confirmed = false))
    }

    @Test fun `el diagnostico explica el progreso de la confirmacion`() {
        val confidence = IsolatedCellConfidence()
        confidence.observe("214-01-100-1000-LTE", candidate = true, observationToken = 1L)
        val report = ThreatAnalyzer.analyzeThreats(
            active = active(dbm = -70), neighbors = emptyList(), cellChangeHistory = emptyList(), currentLocation = null, isolatedCellConfirmed = false, nowMs = NOW
        )
        val diagnostics = DiagnosticEngine.explain(report, DiagnosticEngine.Inputs(
            neighborCount = 0, wifiActive = false, locationAvailable = false, historyWithLocation = 0,
            isolationProgress = confidence.progress to confidence.required,
            previousBandAvailable = false, signalBaseline = null,
            rfFingerprint = null, rfStability = null, reputation = null
        ))
        val h1 = diagnostics.first { it.id == 1 }
        assertEquals("N/A: sin vecinas, pendiente de confirmar (1/3 entregas).", h1.explanation)
    }

    @Test fun `una regla sin datos figura como no evaluada y no como passed`() {
        val report = analyze(active(), neighbors = emptyList())

        assertEquals(HeuristicStatus.NOT_EVALUATED, report.powerJump)
        assertEquals(HeuristicStatus.NOT_EVALUATED, report.taDistance)
        assertEquals(HeuristicStatus.NOT_EVALUATED, report.hardwareCiphering)
    }

    // 3.1.0 — H12 retirada: siempre N/A, sin puntos ni motivo, también con RF malo y señal fuerte
    // (lo que antes, con latencia anómala, la hacía fallar).
    @Test fun `H12 retirada nunca se evalua ni penaliza`() {
        listOf(active(dbm = -65, rsrq = -18), active(dbm = -60, rsrq = -18, sinr = -2), active(dbm = -60, rsrq = -8, sinr = 15)).forEach { cell ->
            val result = ThreatAnalyzer.analyzeThreats(active = cell, neighbors = listOf(neighbor(-95)),
                cellChangeHistory = emptyList(), currentLocation = null)
            assertEquals(HeuristicStatus.NOT_EVALUATED, result.heuristicReport.latencyCorrelation)
            assertFalse(result.suspiciousReason.orEmpty().contains("latencia"))
        }
    }

    // ---------- H11: Geográfica (solo casos "no dispara" en JUnit puro) ----------
    @Test fun `H11 pasa cuando no hay ubicacion ni historial`() {
        // currentLocation = null en analyze() -> analyzeMobileCellId devuelve passed
        assertTrue(analyze(active()).mobileCellIdPassed)
    }

    // ---------- H15: Estabilidad de identidad RF (lifecycle) ----------
    private fun analyzeRf(rf: com.alexisgordr.icdetector.models.CellRfStability?) =
        ThreatAnalyzer.analyzeThreats(
            active = active(dbm = -90),
            neighbors = listOf(neighbor(-95)),
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
            recentDistinctArfcn = listOf(1500 to 5),
            recentPciEpisodes = listOf(50 to 2, 120 to 2)
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
            recentDistinctArfcn = listOf(2850 to 10),
            recentPciEpisodes = listOf(178 to 2, 290 to 2)
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
            recentPciByArfcn = mapOf(6400 to listOf(200 to 3, 473 to 3)),
            recentPciEpisodesByArfcn = mapOf(6400 to listOf(200 to 2, 473 to 2))
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
            recentPciByArfcn = mapOf(unknown to listOf(50 to 3, 120 to 2)),
            recentPciEpisodesByArfcn = mapOf(unknown to listOf(50 to 2, 120 to 2))
        )
        assertFalse(analyzeRf(rf).rfStabilityPassed)
    }


    @Test fun `H15 CID 79591690 no encadena un handover defectuoso como alternancia`() {
        // Traza real reducida: PCI 48 dominante; dos lecturas consecutivas de PCI 200 forman
        // UN episodio, aunque sean dos muestras. Volver a 48 no convierte ese handover en latch.
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 14,
            distinctPci = listOf(48 to 12, 200 to 2),
            distinctArfcn = listOf(2850 to 14),
            recentDistinctPci = listOf(48 to 6, 200 to 2),
            recentDistinctArfcn = listOf(2850 to 8),
            pciByArfcn = mapOf(2850 to listOf(48 to 12, 200 to 2)),
            recentPciByArfcn = mapOf(2850 to listOf(48 to 6, 200 to 2)),
            recentPciEpisodesByArfcn = mapOf(2850 to listOf(48 to 2, 200 to 1))
        )
        assertTrue(analyzeRf(rf).rfStabilityPassed)
    }

    @Test fun `H15 conserva alternancia genuina en episodios independientes`() {
        // 48 -> 200 -> 48 -> 200: cada PCI reaparece tras el contrario; son dos episodios
        // independientes de ambos valores dentro de la misma portadora.
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 16,
            distinctPci = listOf(48 to 10, 200 to 6),
            distinctArfcn = listOf(2850 to 16),
            recentDistinctPci = listOf(48 to 5, 200 to 3),
            recentDistinctArfcn = listOf(2850 to 8),
            pciByArfcn = mapOf(2850 to listOf(48 to 10, 200 to 6)),
            recentPciByArfcn = mapOf(2850 to listOf(48 to 5, 200 to 3)),
            recentPciEpisodesByArfcn = mapOf(2850 to listOf(48 to 2, 200 to 2))
        )
        assertFalse(analyzeRf(rf).rfStabilityPassed)
    }

    @Test fun `H15 trata un cambio persistente de seis horas como reconfiguracion estable`() {
        // 48 durante semanas -> 200 durante seis horas sigue siendo un solo episodio de 200.
        // H15 mide alternancia, no un reemplazo estable; la cuarentena histórica trata ese caso.
        val rf = com.alexisgordr.icdetector.models.CellRfStability(
            totalObservations = 40,
            distinctPci = listOf(48 to 30, 200 to 10),
            distinctArfcn = listOf(2850 to 40),
            recentDistinctPci = listOf(48 to 5, 200 to 10),
            recentDistinctArfcn = listOf(2850 to 15),
            pciByArfcn = mapOf(2850 to listOf(48 to 30, 200 to 10)),
            recentPciByArfcn = mapOf(2850 to listOf(48 to 5, 200 to 10)),
            recentPciEpisodesByArfcn = mapOf(2850 to listOf(48 to 1, 200 to 1))
        )
        assertTrue(analyzeRf(rf).rfStabilityPassed)
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
