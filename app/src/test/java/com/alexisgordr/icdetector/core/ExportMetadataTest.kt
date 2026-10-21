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

    @Test fun `the csv ends with the app version, the exporting device and the location mode`() {
        val header = ExportUtils.CSV_HEADER.split(",")
        val app = header.indexOf("AppVersion")
        assertEquals(listOf("AppVersion", "ExportDevice", "ExportAndroid", "LocationMode"), header.subList(app, app + 4))
        // 3.0 (esquema 22) — Las columnas del perfil local van después: no mueven ninguna anterior.
        // 3.0 — Y tras ellas, los identificadores de la fila y de su lectura.
        assertTrue(header.drop(app + 4).dropLast(5).all { it.startsWith("Profile") })
        // 3.1.0 (esquema 26) — Y tras ellas, el churn de celdas (H17 en sombra).
        assertEquals(listOf("RowId", "ObservationId", "ChurnCells15m", "ChurnChanges15m", "ChurnMotion"), header.takeLast(5))
        val device = ExportUtils.ExportDevice("Google Pixel 8", "16 (API 36)")
        val row = ExportUtils.csvRow(base.copy(appVersion = "3.0.0-beta1"), device).split(",")
        assertEquals(header.size, row.size)
        assertEquals("3.0.0-beta1", row[header.indexOf("AppVersion")])
        assertEquals("Google Pixel 8", row[header.indexOf("ExportDevice")])
        assertEquals("16 (API 36)", row[header.indexOf("ExportAndroid")])
    }

    @Test fun `each row carries its own location mode, unknown when it was not recorded`() {
        val header = ExportUtils.CSV_HEADER.split(",")
        val idx = header.indexOf("LocationMode")
        assertEquals("ADAPTIVE", ExportUtils.csvRow(base.copy(locationMode = "ADAPTIVE")).split(",")[idx])
        assertEquals("CONTINUOUS", ExportUtils.csvRow(base.copy(locationMode = "CONTINUOUS")).split(",")[idx])
        assertEquals("", ExportUtils.csvRow(base).split(",")[idx])
    }

    @Test fun `each row carries its row id and the reading it comes from`() {
        val header = ExportUtils.CSV_HEADER.split(",")
        val periodic = ExportUtils.csvRow(base.copy(rowId = 41, observationId = "obs-7")).split(",")
        val alarm = ExportUtils.csvRow(base.copy(rowId = 42, observationId = "obs-7")).split(",")
        assertEquals(header.size, periodic.size)
        assertEquals("41", periodic[header.indexOf("RowId")])
        assertEquals("42", alarm[header.indexOf("RowId")])
        assertEquals("dos filas de la misma lectura", periodic[header.indexOf("ObservationId")], alarm[header.indexOf("ObservationId")])
        // Antes del esquema 24 no había identificador de lectura: vacío, nunca inventado.
        assertEquals("", ExportUtils.csvRow(base.copy(rowId = 5)).split(",")[header.indexOf("ObservationId")])
    }

    @Test fun `the export query reads the row id and the observation id`() {
        val src = listOf("src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt",
            "app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt").map(::File).first { it.exists() }.readText()
        val read = src.substringAfter("private fun readRecord(").substringBefore("return HistoryRecord(")
        assertTrue(read.contains("cursor.getColumnIndex(COLUMN_ID)"))
        assertTrue(read.contains("cursor.getColumnIndex(COLUMN_OBSERVATION_ID)"))
        // El export lee h.*: trae id y observation_id sin alias que los oculten.
        assertTrue(src.contains("private val EXPORT_QUERY = \"SELECT h.*, "))
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
