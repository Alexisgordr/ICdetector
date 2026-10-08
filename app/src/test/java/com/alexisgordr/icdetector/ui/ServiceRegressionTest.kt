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
        assertTrue(recovery.contains("scope.launch(forensicDispatcher)"))
    }

    @Test fun `stable-site writes are not on the main thread`() {
        val service = source("service/MiniICService.kt")
        val mainBlock = service.substringAfter("withContext(Dispatchers.Main) {\n                    // v2.10.4 — La servidora va siempre primero")
            .substringBefore("stableSitePreviousIdentity = active.identityKey")
        assertFalse(mainBlock.contains("recordStableSiteContext"))
        assertTrue(service.contains("dbHelper.recordStableSiteContext("))
    }

    @Test fun `history writes snapshot their context and go through one ordered queue`() {
        val persistence = source("service/ObservationPersistenceController.kt")
        assertTrue(persistence.contains("observedAtMs = clock()"))
        assertTrue(persistence.contains("scope.launch(writeDispatcher)"))
        assertFalse(persistence.contains("Dispatchers.IO"))
    }

    @Test fun `gps backfill is limited to a recent row`() {
        val db = source("storage/CellDbHelper.kt")
        assertTrue(db.contains("COORDINATE_BACKFILL_WINDOW_MS = 2L * 60_000L"))
        assertTrue(db.contains("AND \$COLUMN_TIMESTAMP >= ? AND \$COLUMN_TIMESTAMP <= ?"))
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

    // Revisión de v2.10.10: una verificación de antes del borrado no escribe después, y la
    // re-comprobación local de VERIFIED no depende de tener credenciales de OpenCellID.
    @Test fun `verification results from before a history deletion are discarded`() {
        val verification = source("service/ExternalVerificationController.kt")
        assertTrue(verification.contains("historyEpoch.ifCurrent(generation, block)"))
        assertTrue(Regex("ifCurrent\\(generation\\)").findAll(verification).count() >= 5)
        assertEquals(5, Regex("publish\\(cell, [^)]*, generation\\)").findAll(verification).count())
        val service = source("service/MiniICService.kt")
        assertTrue(service.contains("historyEpoch = historyEpoch"))
        assertTrue(service.contains("historyEpoch.ifCurrent(generationAtStart)"))
        // La publicación en pantalla vuelve a comprobar la época dentro de la tarea de Main.
        val publish = service.substringAfter("private fun updateFlowWithStatus(").substringBefore("\n    }\n")
        assertTrue(publish.contains("scope.launch(Dispatchers.Main) {\n            historyEpoch.ifCurrent(epoch) {"))
    }

    @Test fun `verified label is rechecked locally without api credentials`() {
        val verification = source("service/ExternalVerificationController.kt")
        val noToken = verification.substringAfter("if (token.isBlank() || token.startsWith(\"pk.YOUR\")) {")
            .substringBefore("return\n")
        assertTrue(noToken.contains("recheckVerifiedLocally(cell, key, generation)"))
        assertFalse(verification.substringAfter("private fun recheckVerifiedLocally").substringBefore("private fun verifyNow")
            .contains("OpenCellIdClient"))
    }
}
