package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la heurística 14 (Intra-LTE Band Downgrade) en ThreatAnalyzer.
 *
 * Como en ThreatAnalyzerTest, se pasa currentLocation = null para no ejecutar
 * ninguna API de Android: la heurística 14 no usa Location, así que corre en la
 * JVM con JUnit (sin Robolectric). El mapeo de banda usa BandPlan (lógica pura).
 *
 * Ubicación:  app/src/test/java/com/alexisgordr/icdetector/core/BandDowngradeTest.kt
 * Ejecutar:   ./gradlew testDebugUnitTest
 */
class BandDowngradeTest {

    // Celda activa LTE en banda baja B20 (EARFCN 6300 -> 800 MHz), derivada vía BandPlan.
    private fun lowBandCell() = CellData(
        isRegistered = true,
        networkType = "4G LTE",
        cellId = "12345",
        mnc = "01",
        tac = "100",
        dbm = -75,
        mcc = "214",
        arfcn = 6300   // B20 (banda baja sub-GHz)
    )

    private fun analyze(
        active: CellData,
        previousBand: Int?,
        previousDbm: Int?,
        recentDbm: List<Int>
    ) = ThreatAnalyzer.analyzeThreats(
        active = active,
        neighbors = emptyList(),
        isHardwareCipheringActive = false,
        cellChangeHistory = emptyList(),
        currentLocation = null,
        previousBand = previousBand,
        previousDbm = previousDbm,
        recentRegisteredDbm = recentDbm
    )

    @Test
    fun `dispara en downgrade forzado de banda alta a baja con senal fuerte`() {
        // Veníamos de B7 (alta, 2600 MHz) con -80 dBm (buena señal) y acabamos en B20 (baja)
        // a -75 dBm: la celda nueva es FUERTE y no más débil que la anterior, que es la firma
        // del "tirón" de un transmisor cercano. Señal previa no degradándose (lista vacía).
        val result = analyze(
            active = lowBandCell(),      // -75 dBm
            previousBand = 7,
            previousDbm = -80,
            recentDbm = emptyList()
        )
        assertFalse(
            "un salto alta->baja con señal previa buena y celda nueva fuerte debe marcar la heurística como fallida",
            result.heuristicReport.bandDowngradePassed
        )
    }

    @Test
    fun `NO dispara si la senal venia degradandose (excepcion del sotano)`() {
        // Mismo salto B7 -> B20 y mismas condiciones de potencia que el test anterior, pero la
        // señal venía cayendo progresivamente (entrando a un garaje/sótano): movimiento físico
        // legítimo. Aísla la excepción de degradación como única diferencia.
        val degrading = listOf(-60, -72, -84, -96)
        val result = analyze(
            active = lowBandCell(),
            previousBand = 7,
            previousDbm = -80,
            recentDbm = degrading
        )
        assertTrue(
            "con degradación progresiva el salto es legítimo y NO debe penalizarse",
            result.heuristicReport.bandDowngradePassed
        )
    }

    // ---------- v2.1: condiciones nuevas contra el handover rutinario a la capa de cobertura ----

    @Test
    fun `NO dispara si la celda nueva se ve debil (caida a la capa de cobertura)`() {
        // Caso REAL del historial de campo: -85 dBm en B7 -> B20 a -108 dBm. Eso no es un
        // transmisor cercano tirando de ti: es quedarte sin la microcelda y caer a la macro.
        // Un catcher que te arrastra a banda baja se ve FUERTE, porque está cerca.
        val weakLowBand = lowBandCell().copy(dbm = -108)
        val result = analyze(
            active = weakLowBand,
            previousBand = 7,
            previousDbm = -85,
            recentDbm = emptyList()
        )
        assertTrue(
            "una celda nueva a -108 dBm no puede ser un transmisor táctico cercano",
            result.heuristicReport.bandDowngradePassed
        )
    }

    @Test
    fun `NO dispara si la celda nueva es mas debil que la anterior aunque sea fuerte`() {
        // -84 dBm -> -90 dBm: la celda nueva supera el mínimo absoluto (-95) pero es MÁS DÉBIL
        // que la que teníamos. Perder potencia al cambiar de banda es la firma de perder la
        // celda anterior, no la de que alguien te capture.
        val result = analyze(
            active = lowBandCell().copy(dbm = -90),
            previousBand = 7,
            previousDbm = -84,
            recentDbm = emptyList()
        )
        assertTrue(
            "si la nueva banda baja se ve peor que la anterior, es repliegue a cobertura",
            result.heuristicReport.bandDowngradePassed
        )
    }

    @Test
    fun `dispara si la celda nueva en banda baja se ve mas fuerte que la anterior`() {
        // -85 dBm en B3 -> -70 dBm en B20: ganas 15 dB bajando a sub-GHz. Ese "tirón" de
        // potencia es exactamente el modelo de ataque que la heurística persigue.
        val result = analyze(
            active = lowBandCell().copy(dbm = -70),
            previousBand = 3,
            previousDbm = -85,
            recentDbm = emptyList()
        )
        assertFalse(
            "ganar potencia al bajar a sub-GHz sí es el patrón sospechoso",
            result.heuristicReport.bandDowngradePassed
        )
    }

    @Test
    fun `NO dispara sin banda previa (primer ciclo, sin referencia)`() {
        val result = analyze(
            active = lowBandCell(),
            previousBand = null,
            previousDbm = null,
            recentDbm = emptyList()
        )
        assertTrue(result.heuristicReport.bandDowngradePassed)
    }

    @Test
    fun `NO dispara si la senal previa era debil (downgrade probablemente legitimo)`() {
        // previousDbm = -100 (< -90): no veníamos con buena señal, el salto a banda
        // baja por cobertura es esperable, no sospechoso.
        val result = analyze(
            active = lowBandCell(),
            previousBand = 7,
            previousDbm = -100,
            recentDbm = emptyList()
        )
        assertTrue(result.heuristicReport.bandDowngradePassed)
    }

    @Test
    fun `NO dispara si la banda no baja (alta a alta)`() {
        // Banda previa B3 (alta) y actual B20 es baja -> esto SÍ dispararía;
        // para el caso negativo usamos una activa que sigue en banda alta (B7, arfcn 3000).
        val highBandActive = lowBandCell().copy(arfcn = 3000) // B7 (alta)
        val result = analyze(
            active = highBandActive,
            previousBand = 3,           // B3 (alta) -> B7 (alta): no es downgrade
            previousDbm = -70,
            recentDbm = emptyList()
        )
        assertTrue(result.heuristicReport.bandDowngradePassed)
    }
}
