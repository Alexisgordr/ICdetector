package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.CellRfStability
import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import com.alexisgordr.icdetector.models.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BANCO DE ESCENARIOS REPRODUCIBLE.
 *
 * Los demás tests comprueban heurísticas sueltas: "con esta entrada, ¿falla H14?". Este comprueba
 * lo único que le importa a quien usa la app: **recorrer una secuencia temporal completa y contar
 * cuántas alarmas confirmadas salen**. Es la diferencia entre "creo que funciona" y "en N ciclos
 * de escenarios benignos el sistema produjo X alarmas".
 *
 * Cada escenario es una secuencia de ciclos que atraviesa la cadena real de decisión:
 *
 *     CellData -> ThreatAnalyzer.analyzeThreats() -> TemporalConfidence.apply() -> ¿alarma?
 *
 * incluida la confirmación de 3 ciclos, que es justo la parte que antes no se podía testear
 * porque vivía dentro del servicio.
 *
 * Los escenarios BENIGNOS están construidos a partir de patrones observados en los 59 días de
 * datos de campo reales (agregación de portadoras, repliegue a la capa de cobertura, etiqueta
 * NSA que va y viene sobre la misma celda). Los ADVERSARIOS son sintéticos: no hay forma honesta
 * de "grabar" un IMSI-catcher, así que se construyen desde el modelo de ataque declarado.
 *
 * LÍMITE HONESTO DE ESTE BANCO: no valida la detección real contra un atacante real. Valida que
 * el motor no se dispara con lo que sí sabemos que es normal, y que sí se dispara con lo que el
 * diseño dice que debe detectar. Es una condición necesaria, no suficiente.
 *
 * Ejecutar:  ./gradlew testDebugUnitTest
 */
class ScenarioTest {

    // ------------------------------------------------------------------ infraestructura

    /** Un ciclo de observación: celda servidora + contexto de vecinas + historial RF. */
    private data class Cycle(
        val active: CellData,
        val neighbors: List<CellData> = emptyList(),
        val rfStability: CellRfStability? = null,
        val previousBand: Int? = null,
        val previousDbm: Int? = null,
        val recentDbm: List<Int> = emptyList()
    )

    private data class Outcome(val alarms: Int, val cycles: Int, val reasons: List<String>)

    /** Recorre un escenario por la cadena completa y cuenta alarmas CONFIRMADAS. */
    private fun run(cycles: List<Cycle>): Outcome {
        val confirmation = TemporalConfidence()
        var alarms = 0
        val reasons = mutableListOf<String>()
        cycles.forEach { c ->
            val analyzed = ThreatAnalyzer.analyzeThreats(
                active = c.active,
                neighbors = c.neighbors,
                isHardwareCipheringActive = true,
                isHardwareCipheringAvailable = false,
                cellChangeHistory = emptyList(),
                currentLocation = null,
                rfStability = c.rfStability,
                previousBand = c.previousBand,
                previousDbm = c.previousDbm,
                recentRegisteredDbm = c.recentDbm
            )
            val confirmed = confirmation.apply(analyzed)
            if (confirmed.isSuspicious) {
                alarms++
                reasons.add(confirmed.suspiciousReason ?: "sin motivo")
            }
        }
        return Outcome(alarms, cycles.size, reasons)
    }

    private fun cell(
        cid: String = "1000",
        dbm: Int = -90,
        netType: String = "4G LTE",
        arfcn: Int? = 1301,                    // B3 (1800 MHz, banda alta)
        pci: Int? = 200,
        mnc: String = "07",
        tac: String = "31601",
        mcc: String = "214",
        verified: VerificationStatus = VerificationStatus.VERIFIED,
        ta: Int? = null,
        taUnit: TimingAdvanceUnit = TimingAdvanceUnit.UNKNOWN
    ) = CellData(
        isRegistered = true, networkType = netType, cellId = cid, mnc = mnc, tac = tac,
        dbm = dbm, mcc = mcc, verified = verified, arfcn = arfcn, pci = pci,
        timingAdvance = ta, timingAdvanceUnit = taUnit
    )

    private fun neighbor(dbm: Int, cid: String = "2000", mnc: String = "07",
                         tac: String = "31601", mcc: String = "214") = CellData(
        isRegistered = false, networkType = "4G LTE", cellId = cid, mnc = mnc, tac = tac,
        dbm = dbm, mcc = mcc, arfcn = 1301, pci = 300
    )

    private fun expectNoAlarm(name: String, cycles: List<Cycle>): Int {
        val r = run(cycles)
        assertEquals(
            "ESCENARIO BENIGNO '$name' produjo ${r.alarms} alarma(s) en ${r.cycles} ciclos: ${r.reasons}",
            0, r.alarms
        )
        return r.cycles
    }

    private fun expectAlarm(name: String, cycles: List<Cycle>) {
        val r = run(cycles)
        assertTrue(
            "ESCENARIO ADVERSARIO '$name' NO produjo ninguna alarma en ${r.cycles} ciclos",
            r.alarms > 0
        )
    }

    // ------------------------------------------------------------------ escenarios benignos

    /** Ciudad: celda estable, vecinas visibles, señal normal. El caso del 95 % del tiempo. */
    private fun normalCity() = (1..10).map {
        Cycle(cell(dbm = -85), neighbors = listOf(neighbor(-95), neighbor(-99), neighbor(-103)))
    }

    /**
     * Rural / macrocelda: sin vecinas visibles. H1 exige señal >= -80 precisamente para no
     * disparar aquí, y el bayesiano además amortigua por densidad.
     */
    private fun normalRural() = (1..10).map { Cycle(cell(dbm = -97), neighbors = emptyList()) }

    /**
     * AGREGACIÓN DE PORTADORAS — caso real, celda 79482913 del historial de campo.
     * La misma antena se ve alternativamente en dos portadoras, y el módem le atribuye el PCI de
     * cada una. Con la regla global de v2.0 esto costaba -30 por "identidad RF inestable".
     */
    private fun carrierAggregation(): List<Cycle> {
        val stability = CellRfStability(
            totalObservations = 13,
            distinctPci = listOf(200 to 6, 473 to 7),
            distinctArfcn = listOf(6400 to 6, 3600 to 7),
            recentDistinctPci = listOf(200 to 3, 473 to 3),
            recentDistinctArfcn = listOf(6400 to 3, 3600 to 3),
            pciByArfcn = mapOf(6400 to listOf(200 to 6), 3600 to listOf(473 to 7)),
            recentPciByArfcn = mapOf(6400 to listOf(200 to 3), 3600 to listOf(473 to 3))
        )
        return (1..8).map { i ->
            val onSecondary = i % 2 == 0
            Cycle(
                active = cell(
                    cid = "79482913",
                    dbm = -100,
                    arfcn = if (onSecondary) 3600 else 6400,
                    pci = if (onSecondary) 473 else 200
                ),
                neighbors = listOf(neighbor(-105), neighbor(-108)),
                rfStability = stability
            )
        }
    }

    /**
     * REPLIEGUE A LA CAPA DE COBERTURA — caso real: sales de una microcelda B7 a -85 dBm y caes
     * a una macro B20 a -108 dBm. El handover más corriente que existe en LTE.
     */
    private fun coverageFallback() = listOf(
        Cycle(cell(cid = "79362070", dbm = -85, arfcn = 2850), neighbors = listOf(neighbor(-100))),
        Cycle(
            active = cell(cid = "79362069", dbm = -108, arfcn = 6400),
            neighbors = listOf(neighbor(-110)),
            previousBand = 7, previousDbm = -85
        ),
        Cycle(
            active = cell(cid = "79362069", dbm = -107, arfcn = 6400),
            neighbors = listOf(neighbor(-110)),
            previousBand = 20, previousDbm = -108
        )
    )

    /** Sótano/garaje: la señal cae progresivamente y acabas en banda baja. Excepción física. */
    private fun indoorDegrading() = listOf(
        Cycle(
            active = cell(dbm = -88, arfcn = 3600),
            neighbors = listOf(neighbor(-100)),
            previousBand = 3, previousDbm = -70,
            recentDbm = listOf(-60, -68, -78, -86)
        )
    )

    /**
     * La etiqueta NSA va y viene sobre la misma celda (54 celdas del historial lo hacen).
     * El veredicto no debe depender de una cadena de presentación.
     */
    private fun nsaLabelFlap() = (1..8).map { i ->
        Cycle(
            active = cell(dbm = -88, netType = if (i % 2 == 0) "5G NR (NSA)" else "4G LTE"),
            neighbors = listOf(neighbor(-98), neighbor(-101))
        )
    }

    /** Zona de frontera / MVNOs: cuatro MNC distintos a la vista. El umbral es >4 a propósito. */
    private fun borderArea() = (1..6).map {
        Cycle(
            active = cell(dbm = -92, mnc = "07"),
            neighbors = listOf(neighbor(-96, mnc = "01"), neighbor(-99, mnc = "03"),
                neighbor(-101, mnc = "04"))
        )
    }

    /**
     * TA raspado del toString() del fabricante: unidad desconocida. Señal muy fuerte y celda sin
     * verificar — exactamente la combinación que dispararía "Proximidad anómala (TA)" si se
     * asumiera que el número es un índice LTE.
     */
    private fun scrapedTimingAdvance() = (1..5).map {
        Cycle(
            active = cell(dbm = -55, verified = VerificationStatus.PENDING, ta = 0,
                taUnit = TimingAdvanceUnit.UNKNOWN),
            neighbors = listOf(neighbor(-70), neighbor(-75))
        )
    }

    /** Sospecha que aparece y desaparece: nunca llega a 3 ciclos seguidos, no debe alarmar. */
    private fun intermittentSuspicion() = (1..9).map { i ->
        if (i % 3 == 0) {
            Cycle(cell(dbm = -60), neighbors = listOf(neighbor(-112)))   // ghost + powerJump
        } else {
            Cycle(cell(dbm = -85), neighbors = listOf(neighbor(-95), neighbor(-99)))
        }
    }

    // ------------------------------------------------------------------ escenarios adversarios

    /** Transmisor dominante: señal muy fuerte, entorno RF artificialmente muerto, sostenido. */
    private fun dominantTransmitter() = (1..5).map {
        Cycle(
            active = cell(cid = "999999", dbm = -60, verified = VerificationStatus.PENDING),
            neighbors = listOf(neighbor(-112), neighbor(-115))
        )
    }

    /** Clon: la misma Cell ID alternando dos PCI sólidos DENTRO de la misma portadora. */
    private fun clonedIdentitySameCarrier(): List<Cycle> {
        val stability = CellRfStability(
            totalObservations = 13,
            distinctPci = listOf(200 to 6, 473 to 7),
            distinctArfcn = listOf(6400 to 13),
            recentDistinctPci = listOf(200 to 3, 473 to 3),
            recentDistinctArfcn = listOf(6400 to 6),
            pciByArfcn = mapOf(6400 to listOf(200 to 6, 473 to 7)),
            recentPciByArfcn = mapOf(6400 to listOf(200 to 3, 473 to 3))
        )
        return (1..5).map { i ->
            Cycle(
                active = cell(cid = "555555", dbm = -60, arfcn = 6400,
                    pci = if (i % 2 == 0) 473 else 200,
                    verified = VerificationStatus.PENDING),
                neighbors = listOf(neighbor(-112)),
                rfStability = stability
            )
        }
    }

    /** Red incoherente: vecinas de otro MCC y TAC que no encaja con ninguna. */
    private fun inconsistentNetwork() = (1..5).map {
        Cycle(
            active = cell(dbm = -80, tac = "99999", verified = VerificationStatus.PENDING),
            neighbors = listOf(neighbor(-95, mcc = "208", tac = "31601"))
        )
    }

    /** Downgrade forzado: te tiran a sub-GHz y encima ganas potencia. */
    private fun forcedBandDowngrade() = (1..5).map {
        Cycle(
            active = cell(cid = "888888", dbm = -60, arfcn = 6400,
                verified = VerificationStatus.PENDING),
            neighbors = listOf(neighbor(-112)),
            previousBand = 3, previousDbm = -85
        )
    }

    // ------------------------------------------------------------------ tests

    @Test fun `escenario benigno - ciudad normal`() { expectNoAlarm("NORMAL_CITY", normalCity()) }
    @Test fun `escenario benigno - rural sin vecinas`() { expectNoAlarm("NORMAL_RURAL", normalRural()) }
    @Test fun `escenario benigno - agregacion de portadoras (caso real)`() { expectNoAlarm("CARRIER_AGGREGATION", carrierAggregation()) }
    @Test fun `escenario benigno - repliegue a la capa de cobertura (caso real)`() { expectNoAlarm("COVERAGE_FALLBACK", coverageFallback()) }
    @Test fun `escenario benigno - interior con senal degradandose`() { expectNoAlarm("INDOOR_DEGRADING", indoorDegrading()) }
    @Test fun `escenario benigno - etiqueta NSA intermitente`() { expectNoAlarm("NSA_LABEL_FLAP", nsaLabelFlap()) }
    @Test fun `escenario benigno - zona de frontera con varios MNC`() { expectNoAlarm("BORDER_AREA", borderArea()) }
    @Test fun `escenario benigno - TA de unidad desconocida`() { expectNoAlarm("TA_SCRAPED", scrapedTimingAdvance()) }
    @Test fun `escenario benigno - sospecha intermitente sin 3 ciclos seguidos`() { expectNoAlarm("INTERMITTENT", intermittentSuspicion()) }

    @Test fun `escenario adversario - transmisor dominante`() { expectAlarm("DOMINANT_TX", dominantTransmitter()) }
    @Test fun `escenario adversario - clon con PCI alternante en la misma portadora`() { expectAlarm("CLONE_SAME_CARRIER", clonedIdentitySameCarrier()) }
    @Test fun `escenario adversario - red incoherente (MCC y TAC)`() { expectAlarm("INCONSISTENT_NET", inconsistentNetwork()) }
    @Test fun `escenario adversario - downgrade forzado con tiron de potencia`() { expectAlarm("FORCED_DOWNGRADE", forcedBandDowngrade()) }

    /**
     * La métrica que de verdad se puede citar: tasa de falsos positivos sobre el banco benigno.
     * Si algún día un cambio la rompe, este test lo dice con un número, no con una sensación.
     */
    @Test fun `tasa de falsos positivos sobre todo el banco benigno`() {
        val benign = listOf(
            "NORMAL_CITY" to normalCity(),
            "NORMAL_RURAL" to normalRural(),
            "CARRIER_AGGREGATION" to carrierAggregation(),
            "COVERAGE_FALLBACK" to coverageFallback(),
            "INDOOR_DEGRADING" to indoorDegrading(),
            "NSA_LABEL_FLAP" to nsaLabelFlap(),
            "BORDER_AREA" to borderArea(),
            "TA_SCRAPED" to scrapedTimingAdvance(),
            "INTERMITTENT" to intermittentSuspicion()
        )
        var totalCycles = 0
        var totalAlarms = 0
        val offenders = mutableListOf<String>()
        benign.forEach { (name, cycles) ->
            val r = run(cycles)
            totalCycles += r.cycles
            totalAlarms += r.alarms
            if (r.alarms > 0) offenders.add("$name(${r.alarms}): ${r.reasons}")
        }
        println("[BANCO BENIGNO] ${benign.size} escenarios, $totalCycles ciclos, $totalAlarms alarmas confirmadas")
        assertEquals(
            "falsos positivos en el banco benigno: $offenders",
            0, totalAlarms
        )
    }

    /** Y la simétrica: el banco adversario no debe quedarse mudo. */
    @Test fun `tasa de deteccion sobre el banco adversario`() {
        val adversarial = listOf(
            "DOMINANT_TX" to dominantTransmitter(),
            "CLONE_SAME_CARRIER" to clonedIdentitySameCarrier(),
            "INCONSISTENT_NET" to inconsistentNetwork(),
            "FORCED_DOWNGRADE" to forcedBandDowngrade()
        )
        val detected = adversarial.count { (_, cycles) -> run(cycles).alarms > 0 }
        println("[BANCO ADVERSARIO] detectados $detected de ${adversarial.size}")
        assertEquals("escenarios adversarios no detectados", adversarial.size, detected)
    }
}
