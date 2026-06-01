package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.SignalBaseline
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la heurística 12 (Signal Baseline Anomaly) en ThreatAnalyzer.
 *
 * Importante: analyzeThreats recibe un android.location.Location, pero todas las
 * llamadas a Location.distanceBetween están protegidas por `currentLocation != null`,
 * y `currentLocation?.speed` es null-safe. Pasando currentLocation = null NO se
 * ejecuta ninguna API de Android, así que estos tests corren en la JVM con JUnit
 * (sin Robolectric).
 *
 * Ubicación:  app/src/test/java/com/alexisgordr/icdetector/core/ThreatAnalyzerTest.kt
 * Ejecutar:   ./gradlew testDebugUnitTest
 */
class ThreatAnalyzerTest {

    // Celda activa registrada de referencia; el dBm se ajusta en cada test.
    private fun cell(dbm: Int) = CellData(
        isRegistered = true,
        networkType = "4G LTE",
        cellId = "12345",
        mnc = "01",
        tac = "100",
        dbm = dbm,
        mcc = "214"
    )

    private fun analyze(active: CellData, baseline: SignalBaseline?, wifi: Boolean = false) =
        ThreatAnalyzer.analyzeThreats(
            active = active,
            neighbors = emptyList(),
            isHardwareCipheringActive = false,
            cellChangeHistory = emptyList(),
            currentLocation = null,          // null => no se ejecuta ninguna API de Android
            isWifiActive = wifi,
            signalBaseline = baseline
        )

    @Test
    fun `signalBaseline detecta potencia anomalamente fuerte`() {
        // Histórico: esta celda aquí suele verse a -85 dBm (±3). Hoy: -55 (+30 dB).
        val baseline = SignalBaseline(sampleCount = 10, meanDbm = -85.0, stdDevDbm = 3.0, minDbm = -90, maxDbm = -80)
        val result = analyze(cell(dbm = -55), baseline)
        assertFalse(
            "potencia muy por encima del histórico debe marcar la heurística como fallida",
            result.heuristicReport.signalBaselinePassed
        )
    }

    @Test
    fun `signalBaseline pasa cuando la potencia es normal`() {
        val baseline = SignalBaseline(sampleCount = 10, meanDbm = -85.0, stdDevDbm = 3.0, minDbm = -90, maxDbm = -80)
        val result = analyze(cell(dbm = -83), baseline)   // +2 dB, dentro de lo habitual
        assertTrue(result.heuristicReport.signalBaselinePassed)
    }

    @Test
    fun `sin baseline no juzga nada (periodo de rodaje)`() {
        val result = analyze(cell(dbm = -55), baseline = null)
        assertTrue(
            "sin historial no hay con qué comparar: debe pasar",
            result.heuristicReport.signalBaselinePassed
        )
    }

    @Test
    fun `con WiFi activo no dispara aunque la potencia sea fuerte`() {
        val baseline = SignalBaseline(sampleCount = 10, meanDbm = -85.0, stdDevDbm = 3.0, minDbm = -90, maxDbm = -80)
        val result = analyze(cell(dbm = -55), baseline, wifi = true)
        assertTrue(
            "con WiFi la heurística se omite",
            result.heuristicReport.signalBaselinePassed
        )
    }

    @Test
    fun `potencia mas debil de lo normal no es sospechosa`() {
        // -110 dBm con histórico de -85: más débil de lo habitual NO debe disparar
        // (solo la dirección "más fuerte" es sospechosa).
        val baseline = SignalBaseline(sampleCount = 10, meanDbm = -85.0, stdDevDbm = 3.0, minDbm = -90, maxDbm = -80)
        val result = analyze(cell(dbm = -110), baseline)
        assertTrue(result.heuristicReport.signalBaselinePassed)
    }
}
