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
        assertEquals(mapOf("location_mode" to "ADAPTIVE", "proxy_enabled" to true), BackupSettings.fromJson(json))
        assertEquals(emptyMap<String, Any>(), BackupSettings.fromJson("{\"opencellid_key\":\"x\"}"))
        assertEquals(emptyMap<String, Any>(), BackupSettings.fromJson("broken"))
    }
}
