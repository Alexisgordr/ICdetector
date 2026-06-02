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
        // Veníamos de B7 (alta, 2600 MHz) con -70 dBm (fuerte) y nos tiran a B20 (baja).
        // Señal previa no degradándose (lista vacía => sin evidencia de caída).
        val result = analyze(
            active = lowBandCell(),
            previousBand = 7,
            previousDbm = -70,
            recentDbm = emptyList()
        )
        assertFalse(
            "un salto alta->baja con señal previa fuerte debe marcar la heurística como fallida",
            result.heuristicReport.bandDowngradePassed
        )
    }

    @Test
    fun `NO dispara si la senal venia degradandose (excepcion del sotano)`() {
        // Mismo salto B7 -> B20, pero la señal venía cayendo progresivamente
        // (entrando a un garaje/sótano): es un movimiento físico legítimo.
        val degrading = listOf(-60, -72, -84, -96)
        val result = analyze(
            active = lowBandCell(),
            previousBand = 7,
            previousDbm = -70,
            recentDbm = degrading
        )
        assertTrue(
            "con degradación progresiva el salto es legítimo y NO debe penalizarse",
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
