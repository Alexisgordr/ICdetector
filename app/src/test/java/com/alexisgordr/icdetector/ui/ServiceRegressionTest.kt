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
     * 3.1.6 (#49) — Un override de Kotlin comprueba la nulidad de sus parámetros al entrar, y Android
     * los declara no nulos (Kotlin no deja hacerlos nulables). Una lista nula del fabricante cerraba
     * la app en segundo plano antes de llegar al código. Los callbacks son ahora adaptadores en Java,
     * que no añaden esa comprobación, y la lista llega, quizá null, a una función que la acepta.
     */
    @Test fun `telephony callbacks go through the null-tolerant Java adapters`() {
        val telephony = source("service/TelephonyCollectionController.kt")
        listOf("onCellInfoChanged", "onCellInfo", "onServiceStateChanged", "onDisplayInfoChanged", "onError").forEach {
            assertFalse("ningún override de Kotlin de $it", telephony.contains("override fun $it("))
        }
        assertTrue(telephony.contains("NullTolerantTelephonyCallbacks.CellInfoRequest("))
        assertTrue(telephony.contains("NullTolerantTelephonyCallbacks.CellInfoChanged { cellInfo ->"))
        assertTrue(telephony.contains("NullTolerantTelephonyCallbacks.ServiceStateChanged { serviceState ->"))
        assertTrue(telephony.contains("NullTolerantTelephonyCallbacks.DisplayInfoChanged { displayInfo ->"))
        // Las dos vías de celdas pasan por el mismo tratamiento (CellDelivery, probado en la JVM).
        val handler = telephony.substringAfter("private fun handleCellDelivery(").substringBefore("\n    }")
        assertTrue(handler.contains("CellDelivery.countsForWatchdog(raw)"))
        assertTrue(handler.contains("nullCellsNotice.onDelivery(raw == null)"))
        assertTrue(handler.contains("CellDelivery.orEmpty(raw)"))
        assertTrue(telephony.contains("handleCellDelivery(cellInfo, fresh = true, registered = true)"))
        assertTrue(telephony.contains("handleCellDelivery(cellInfo, fresh = true, registered = false)"))
        // Error de la petición: allCellInfo puede lanzar IllegalStateException; se captura cualquiera.
        val onError = telephony.substringAfter("{ _, _ ->").substringBefore("\n                    }\n                )")
        assertTrue(onError.contains("handleCellDelivery(manager.allCellInfo, fresh = false, registered = false)"))
        assertTrue(onError.contains("} catch (_: Exception) {}"))
        // Los ServiceState y DisplayInfo nulos se ignoran.
        assertTrue(telephony.contains("if (serviceState != null) {"))
        assertTrue(telephony.contains("if (displayInfo != null) {"))
    }

    /**
     * 3.1.7 (#49) — Registrar un callback lanzaba IllegalStateException al pasar el tope de listeners
     * por proceso y la excepción salía de onDisplayInfoChanged: crash. Ahora todo registro pasa por
     * CallbackRegistrar (probado en la JVM) y pedir celdas captura cualquier excepción.
     */
    @Test fun `registering telephony callbacks never throws`() {
        val telephony = source("service/TelephonyCollectionController.kt")
        assertTrue(telephony.contains("register = { manager.registerTelephonyCallback(context.mainExecutor, it) }"))
        assertTrue(telephony.contains("unregister = { manager.unregisterTelephonyCallback(it) }"))
        // Ninguna otra llamada directa a registerTelephonyCallback.
        assertEquals(1, telephony.split("manager.registerTelephonyCallback(").size - 1)
        listOf("CELL_INFO", "DISPLAY_INFO", "SERVICE_STATE").forEach {
            assertTrue(it, telephony.contains("callbacks.add($it, NullTolerantTelephonyCallbacks."))
        }
        val request = telephony.substringAfter("fun requestFreshCellInfo()").substringBefore("\n    }\n")
        assertTrue(request.contains("} catch (e: Exception) {"))
        assertTrue(request.contains("reportRequestFailure(e)"))
        assertTrue(telephony.substringAfter("private fun reportRequestFailure(").substringBefore("\n    }")
            .contains("log(TelephonyRecoveryLog.requestFailed(error))"))
        // 3.1.7 — Lint (MissingPermission) exige ver la SecurityException capturada de forma explícita,
        // en la petición y en la lectura de reserva; Exception va detrás para lo demás.
        assertTrue(request.contains("} catch (e: SecurityException) {"))
        assertTrue(request.indexOf("catch (e: SecurityException)") < request.indexOf("catch (e: Exception)"))
        assertTrue(request.contains("} catch (_: SecurityException) {"))
        // destroy() deja el controlador cerrado: un reinicio pendiente no vuelve a registrar.
        assertTrue(telephony.contains("if (closed || !hasPhonePermission()) return"))
        assertTrue(telephony.substringAfter("fun destroy()").substringBefore("\n    }").contains("closed = true"))
    }

    @Test fun `the Java adapters only forward and accept null`() {
        val adapters = source("service/NullTolerantTelephonyCallbacks.java")
        assertTrue(adapters.contains("void accept(@Nullable T value);"))
        assertTrue(adapters.contains("void onError(int errorCode, @Nullable Throwable detail);"))
        listOf(
            "public void onCellInfoChanged(List<CellInfo> cellInfo) {\n            sink.accept(cellInfo);",
            "public void onCellInfo(List<CellInfo> cellInfo) {\n            sink.accept(cellInfo);",
            "public void onServiceStateChanged(ServiceState serviceState) {\n            sink.accept(serviceState);",
            "public void onDisplayInfoChanged(TelephonyDisplayInfo displayInfo) {\n            sink.accept(displayInfo);",
            "public void onError(int errorCode, Throwable detail) {\n            errorSink.onError(errorCode, detail);"
        ).forEach { assertTrue(it, adapters.contains(it)) }
        // Ninguna comprobación de nulidad en el reenvío.
        assertFalse(adapters.contains("requireNonNull"))
        assertFalse(adapters.contains("@NonNull"))
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
