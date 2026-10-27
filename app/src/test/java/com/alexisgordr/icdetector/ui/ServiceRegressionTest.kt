package com.alexisgordr.icdetector.ui

import org.junit.Assert.assertEquals
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

    /**
     * 3.1.6 (#49) — Con un parámetro no nulo, Kotlin comprueba la nulidad al entrar en el callback y
     * una lista nula del fabricante cerraba la app en segundo plano antes de llegar al código.
     */
    @Test fun `telephony callbacks accept null from the framework`() {
        val telephony = source("service/TelephonyCollectionController.kt")
        assertTrue(telephony.contains("override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?)"))
        assertTrue(telephony.contains("override fun onCellInfo(cellInfo: MutableList<CellInfo>?)"))
        assertTrue(telephony.contains("override fun onServiceStateChanged(serviceState: ServiceState?)"))
        assertTrue(telephony.contains("override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo?)"))
        assertFalse("ningún callback con la lista no nula", telephony.contains("MutableList<CellInfo>)"))
        // Las dos vías de celdas pasan por el mismo tratamiento (CellDelivery, probado en la JVM).
        val handler = telephony.substringAfter("private fun handleCellDelivery(").substringBefore("\n    }")
        assertTrue(handler.contains("CellDelivery.countsForWatchdog(raw)"))
        assertTrue(handler.contains("nullCellsNotice.onDelivery(raw == null)"))
        assertTrue(handler.contains("CellDelivery.orEmpty(raw)"))
        assertTrue(telephony.contains("handleCellDelivery(cellInfo, fresh = true, registered = true)"))
        assertTrue(telephony.contains("handleCellDelivery(cellInfo, fresh = true, registered = false)"))
        // onError: allCellInfo puede lanzar IllegalStateException; se captura cualquier excepción.
        val onError = telephony.substringAfter("override fun onError(").substringBefore("\n                }")
        assertTrue(onError.contains("handleCellDelivery(manager.allCellInfo, fresh = false, registered = false)"))
        assertTrue(onError.contains("} catch (_: Exception) {}"))
    }

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

    // 3.1.0 — La sonda de latencia (H12) se retiró: el servicio ya no mide ni guarda latencia.
    @Test fun `the latency probe is gone from the service`() {
        val service = source("service/MiniICService.kt")
        listOf("latencyMonitor", "NetworkLatencyMonitor", "isLatencyDetectionEnabled", "networkLatencyState", "showLatencyAlert")
            .forEach { assertFalse(it, service.contains(it)) }
        assertFalse(listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/service/NetworkLatencyMonitor.kt").exists())
    }

    @Test fun `ping-pong window is shared by the service and the analyzer`() {
        val service = source("service/MiniICService.kt")
        assertTrue(service.contains("ThreatAnalyzer.PING_PONG_WINDOW_MS"))
        assertFalse(service.contains("it.second > 10000L"))
    }

    // ---- v2.10.10 ----

    @Test fun `history deletion goes through the service when it is running`() {
        val history = source("ui/HistoryScreen.kt")
        assertTrue(history.contains("if (running != null) running.clearHistory() else dbHelper.clear()"))
        assertTrue(history.contains("incidents = emptyList()"))
        assertTrue(history.contains("forensicCases = emptyList()"))
        val service = source("service/MiniICService.kt")
        assertTrue(service.contains("suspend fun clearHistory()"))
        assertTrue(service.contains("forensicRecorder.reset()"))
        assertTrue(service.contains("historyEpoch.invalidate()"))
    }

    @Test fun `startup recovery runs on the forensic queue before new samples`() {
        val service = source("service/MiniICService.kt")
        val recovery = service.substringBefore("dbHelper.interruptOpenForensicCases()").takeLast(400)
        assertTrue(recovery.contains("writeGate.launch(scope, forensicDispatcher)"))
    }

    @Test fun `stable-site writes are not on the main thread`() {
        val service = source("service/MiniICService.kt")
        val mainBlock = service.substringAfter("if (!collectionGeneration.mayPublish(cycleTicket)) return@withContext")
            .substringBefore("stableSitePreviousIdentity = active.identityKey")
        assertFalse(mainBlock.contains("recordStableSiteContext"))
        assertTrue(service.contains("dbHelper.recordStableSiteContext("))
    }

    @Test fun `history writes snapshot their context and go through one ordered queue`() {
        val persistence = source("service/ObservationPersistenceController.kt")
        assertTrue(persistence.contains("observedAtMs = clock()"))
        assertTrue(persistence.contains("writeGate.launch(scope, writeDispatcher)"))
        assertFalse(persistence.contains("Dispatchers.IO"))
    }

    @Test fun `gps backfill is limited to a recent row`() {
        val db = source("storage/CellDbHelper.kt")
        assertTrue(db.contains("COORDINATE_BACKFILL_WINDOW_MS = 2L * 60_000L"))
        // 3.0 (#23) — La ventana se mide con el instante de la fila (texto local solo en filas antiguas).
        assertTrue(db.contains("AND \${observedSince()} AND \${ObservationTime.untilClause(COLUMN_OBSERVED_AT_MS, COLUMN_TIMESTAMP)}"))
        val service = source("service/MiniICService.kt")
        assertEquals(2, Regex("fixTimeMs = (loc|location)\\.time").findAll(service).count())
    }

    @Test fun `charts are cleared on handover and exports read one snapshot`() {
        val telemetry = source("service/TelemetryHistoryController.kt")
        assertTrue(telemetry.contains("rsrqHistory.value = emptyList()"))
        assertTrue(telemetry.contains("geoHistory.value = emptyList()"))
        assertTrue(source("forensics/StableSiteExporter.kt").contains("db.readConsistently"))
        assertTrue(source("forensics/GeometryExporter.kt").contains("db.readConsistently"))
        assertTrue(source("ui/RadioScreen.kt").contains("getServiceStateEvents(strict = true)"))
    }
}
