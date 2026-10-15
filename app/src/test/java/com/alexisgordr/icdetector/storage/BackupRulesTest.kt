package com.alexisgordr.icdetector.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 3.0 — Ningún dato de la app sale por la copia de Android ni por la transferencia entre móviles.
 * Bug found during testing: la transferencia solo excluía las preferencias y se llevaba la base.
 */
class BackupRulesTest {

    private val domains = setOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref"
    )

    private fun xml(name: String) = listOf(
        File("src/main/res/xml/$name"), File("app/src/main/res/xml/$name")
    ).first { it.exists() }.let { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }

    private fun excludedDomains(parent: org.w3c.dom.Element): Set<String> {
        val nodes = parent.getElementsByTagName("include")
        assertEquals("no include rules", 0, nodes.length)
        val excludes = parent.getElementsByTagName("exclude")
        return (0 until excludes.length).map { excludes.item(it) as org.w3c.dom.Element }
            .filter { it.getAttribute("path") == "." }
            .map { it.getAttribute("domain") }.toSet()
    }

    @Test fun `cloud backup and device transfer exclude every domain`() {
        val root = xml("data_extraction_rules.xml").documentElement
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val element = root.getElementsByTagName(section).item(0) as org.w3c.dom.Element
            assertEquals(section, domains, excludedDomains(element))
        }
    }

    @Test fun `legacy full backup excludes every domain`() {
        assertEquals(domains, excludedDomains(xml("backup_rules.xml").documentElement))
    }

    @Test fun `the manifest points to both rule files and keeps allowBackup off`() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .first { it.exists() }.readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
    }
}
