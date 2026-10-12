package com.alexisgordr.icdetector.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {

    private val tmp: File = Files.createTempDirectory("archive").toFile()

    @Test fun `write and read give back the same database, manifest and settings`() {
        val db = File(tmp, "src.db").apply { writeBytes(ByteArray(5000) { (it % 251).toByte() }) }
        val manifest = BackupManifest(1, BackupFormat.APP_ID, "3.0.0-beta4+abc1234", 21, "t",
            BackupArchive.sha256(db), db.length())
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, manifest, db, "{\"location_mode\":\"INTELLIGENT\"}")

        val contents = BackupArchive.read(ByteArrayInputStream(out.toByteArray()), File(tmp, "x"), "staged.db")
        assertEquals(manifest, contents.manifest)
        assertArrayEquals(db.readBytes(), contents.database!!.readBytes())
        assertEquals(manifest.databaseSha256, contents.databaseSha256)
        assertEquals("{\"location_mode\":\"INTELLIGENT\"}", contents.settingsJson)
        assertFalse(contents.tooLarge)
    }

    @Test fun `only the three known entries are written, never a path from the zip`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf("../evil.db", "/abs.db", "sub/icdetector_history.db", "other.txt").forEach {
                zip.putNextEntry(ZipEntry(it)); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
            }
        }
        val target = File(tmp, "y")
        val contents = BackupArchive.read(ByteArrayInputStream(out.toByteArray()), target, "staged.db")
        assertNull(contents.manifest)
        assertNull(contents.database)
        assertTrue(target.listFiles().isNullOrEmpty())
        assertFalse(File(tmp, "evil.db").exists())
    }

    @Test fun `settings keep only user preferences and never the API key`() {
        val json = BackupSettings.toJson(mapOf(
            "location_mode" to "ADAPTIVE", "proxy_enabled" to true, "opencellid_key" to "secret",
            "last_accepted_loc" to "1,2,3", "latency_detection_enabled" to "not a boolean"
        ))
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("last_accepted_loc"))
        // Sin la clave de avisos 2G/3G (copia anterior): se restaura su valor predeterminado.
        assertEquals(mapOf("location_mode" to "ADAPTIVE", "proxy_enabled" to true, "legacy_network_alert_mode" to "NOTIFY"),
            BackupSettings.fromJson(json))
        assertEquals(mapOf("legacy_network_alert_mode" to "NOTIFY"), BackupSettings.fromJson("{\"opencellid_key\":\"x\"}"))
        assertEquals(emptyMap<String, Any>(), BackupSettings.fromJson("broken"))
    }

    @Test fun `2G-3G alert mode travels in new backups and old backups restore the default`() {
        val json = BackupSettings.toJson(mapOf("legacy_network_alert_mode" to "NOTIFY_SOUND"))
        assertEquals("NOTIFY_SOUND", BackupSettings.fromJson(json)["legacy_network_alert_mode"])
        assertEquals("OFF", BackupSettings.fromJson("{\"legacy_network_alert_mode\":\"OFF\"}")["legacy_network_alert_mode"])
        // Copia 3.0 anterior a la clave, con sus otros ajustes intactos.
        val old = BackupSettings.fromJson("{\"app_language\":\"es\",\"location_mode\":\"CONTINUOUS\"}")
        assertEquals("es", old["app_language"])
        assertEquals(com.alexisgordr.icdetector.core.LegacyNetworkAlertMode.DEFAULT.storedValue, old["legacy_network_alert_mode"])
        // Un valor desconocido se restaura tal cual y el servicio lo lee como el predeterminado.
        val odd = BackupSettings.fromJson("{\"legacy_network_alert_mode\":\"LOUD\"}")["legacy_network_alert_mode"] as String
        assertEquals(com.alexisgordr.icdetector.core.LegacyNetworkAlertMode.NOTIFY,
            com.alexisgordr.icdetector.core.LegacyNetworkAlertMode.fromStored(odd))
        assertEquals(com.alexisgordr.icdetector.core.LegacyNetworkAlertMode.PREF_KEY, "legacy_network_alert_mode")
    }
}
