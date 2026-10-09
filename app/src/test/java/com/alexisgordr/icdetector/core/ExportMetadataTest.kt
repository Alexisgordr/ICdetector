package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.utils.ExportUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (#30) — Cada fila lleva la versión de la app que la observó, y el CSV dice desde qué
 * teléfono y Android se exportó, sin ningún identificador personal ni del aparato.
 */
class ExportMetadataTest {
    private val base = HistoryRecord(
        timestamp = "2026-10-09 12:00:00", netType = "4G", cid = "1", mnc = "07",
        tac = "1", mcc = "214", dbm = -90
    )

    @Test fun `the csv ends with the app version and the exporting device`() {
        val header = ExportUtils.CSV_HEADER.split(",")
        assertEquals(listOf("AppVersion", "ExportDevice", "ExportAndroid"), header.takeLast(3))
        val device = ExportUtils.ExportDevice("Google Pixel 8", "16 (API 36)")
        val row = ExportUtils.csvRow(base.copy(appVersion = "3.0.0-beta1"), device).split(",")
        assertEquals(header.size, row.size)
        assertEquals("3.0.0-beta1", row[header.indexOf("AppVersion")])
        assertEquals("Google Pixel 8", row[header.indexOf("ExportDevice")])
        assertEquals("16 (API 36)", row[header.indexOf("ExportAndroid")])
    }

    @Test fun `rows before 3_0 have an unknown version`() {
        val header = ExportUtils.CSV_HEADER.split(",")
        val row = ExportUtils.csvRow(base).split(",")
        assertEquals(header.size, row.size)
        assertEquals("", row[header.indexOf("AppVersion")])
    }

    @Test fun `the export never includes a personal or hardware identifier`() {
        val header = ExportUtils.CSV_HEADER.lowercase()
        listOf("imei", "imsi", "serial", "account", "msisdn", "android_id").forEach {
            assertFalse("$it en la cabecera", header.contains(it))
        }
        val source = listOf(
            File("src/main/java/com/alexisgordr/icdetector/utils/ExportUtils.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/utils/ExportUtils.kt")
        ).first { it.exists() }.readText()
        listOf("getImei", "Build.SERIAL", "getSerial", "ANDROID_ID", "subscriberId").forEach {
            assertFalse("$it en ExportUtils", source.contains(it))
        }
        val persistence = listOf(
            File("src/main/java/com/alexisgordr/icdetector/service/ObservationPersistenceController.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/service/ObservationPersistenceController.kt")
        ).first { it.exists() }.readText()
        assertTrue(persistence.contains("appVersion = appVersion"))
    }
}
