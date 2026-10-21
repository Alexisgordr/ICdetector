package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.utils.ExportUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.1.0 — Sin verificación externa. No queda código que hable con una base pública, toda fila nueva
 * se guarda como PENDING con ApiLat/ApiLon vacías, y las columnas siguen en su sitio en el CSV.
 * La prueba de ciclo completo contra SQLite real está en androidTest (ExternalVerificationCycleTest).
 */
class ExternalVerificationRemovalTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun source(path: String) = main.resolve("java/com/alexisgordr/icdetector/$path").readText()

    @Test fun `no source file names the public database, a SOCKS proxy or an API key`() {
        val forbidden = Regex("opencellid|socks|apikey|api_key", RegexOption.IGNORE_CASE)
        val offenders = main.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml") }
            // La única excepción: la lista de claves antiguas que el servicio borra al arrancar.
            .filterNot { it.name == "LegacyPreferences.kt" }
            .filter { forbidden.containsMatchIn(it.readText()) }
            .map { it.relativeTo(main).path }
            .toList()
        assertEquals(emptyList<String>(), offenders)
        listOf("network/OpenCellIdClient.kt", "network/VerificationOutcome.kt", "service/ExternalVerificationController.kt",
            "core/VerificationConfig.kt", "core/VerificationDecision.kt").forEach {
            assertFalse(it, main.resolve("java/com/alexisgordr/icdetector/$it").exists())
        }
    }

    @Test fun `the service still removes the stored token and proxy settings of earlier versions`() {
        assertTrue(LegacyPreferences.KEYS.containsAll(listOf("opencellid_key", "proxy_enabled", "opencellid_pause")))
        assertTrue(source("service/MiniICService.kt").contains("LegacyPreferences.KEYS.forEach(::remove)"))
    }

    @Test fun `every new history row is PENDING and never writes API coordinates`() {
        val db = source("storage/CellDbHelper.kt")
        val log = db.substringAfter("fun logConnection(").substringBefore("\n    }\n")
        assertFalse("ningún llamador puede pasar otro estado", log.substringBefore("): Long").contains("verified"))
        assertTrue(log.contains("put(COLUMN_VERIFIED, VerificationStatus.PENDING.name)"))
        assertFalse(db.contains("put(COLUMN_API_LAT") || db.contains("put(COLUMN_API_LON"))
        assertFalse(source("service/ObservationPersistenceController.kt").contains("verified"))
    }

    @Test fun `the csv keeps Verified, ApiLat and ApiLon in place, PENDING and empty`() {
        val header = ExportUtils.CSV_HEADER.split(",")
        assertEquals(7, header.indexOf("Verified"))
        assertEquals(17, header.indexOf("ApiLat"))
        assertEquals(18, header.indexOf("ApiLon"))
        // Una fila tal como la lee readRecord de una observación nueva (verified PENDING, api_* NULL).
        val row = ExportUtils.csvRow(HistoryRecord(timestamp = "2026-10-21 10:00:00", netType = "4G", cid = "1",
            mnc = "07", tac = "1", mcc = "214", dbm = -90)).split(",")
        assertEquals(header.size, row.size)
        assertEquals("PENDING", row[7])
        assertEquals("", row[17])
        assertEquals("", row[18])
    }
}
