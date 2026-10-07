package com.alexisgordr.icdetector.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.10.9 — Comprobaciones sobre el código del servicio, que depende de Android y no se puede
 * ejecutar en la JVM. Cada test fija un fallo encontrado en las pruebas para que no vuelva.
 */
class ServiceRegressionTest {
    private fun source(path: String): String =
        listOf(File("src/main/java/com/alexisgordr/icdetector/$path"), File("app/src/main/java/com/alexisgordr/icdetector/$path"))
            .first { it.exists() }.readText()

    @Test fun `no empty ciphering callback pretends ciphering is monitored`() {
        val telephony = source("service/TelephonyCollectionController.kt")
        assertFalse(telephony.contains("SecurityPlaceholder"))
        assertFalse(telephony.contains("securityCallback"))
    }

    @Test fun `rf fingerprint cache is refreshed after moving`() {
        val service = source("service/MiniICService.kt")
        assertTrue(service.contains("if (fingerprintNeedsRefresh(currentLocation))"))
        assertTrue(service.contains("RfFingerprintRefresh.needed("))
    }

    @Test fun `latency result from a previous cell is discarded`() {
        // La lógica se prueba ejecutándola en NetworkLatencyMonitorTest; aquí, solo el cableado
        // del servicio, que depende de Android.
        val service = source("service/MiniICService.kt")
        assertTrue(service.contains("?.identityKey ?: return"))
        // El reset compara la identidad completa, no solo el CID.
        assertTrue(service.contains("val identity = cell.identityKey"))
        assertTrue(service.contains("latencyMonitor.onCellChanged(identity, idleLatencyState())"))
        assertFalse(service.contains("activeRaw.cellId != prevCid) {\n                latencyMonitor.reset"))
        assertTrue(service.contains("latencyMonitor.check(activeCellKey)"))
    }

    @Test fun `ping-pong window is shared by the service and the analyzer`() {
        val service = source("service/MiniICService.kt")
        assertTrue(service.contains("ThreatAnalyzer.PING_PONG_WINDOW_MS"))
        assertFalse(service.contains("it.second > 10000L"))
    }
}
