package com.alexisgordr.icdetector.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** 3.0 — Limitaciones: Wi-Fi y «Permitir 2G» documentados sin prometer más de lo que hay. */
class LegacyAndWifiDocsTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun doc(name: String) = listOf(File(name), File("../$name")).first { it.exists() }.readText()
    private fun strings(dir: String): Map<String, String> {
        val d = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(main, "res/$dir/strings.xml"))
        val n = d.getElementsByTagName("string")
        return (0 until n.length).associate { i -> n.item(i).attributes.getNamedItem("name").nodeValue to n.item(i).textContent }
    }

    @Test fun `README limitations list the Wi-Fi abstentions and Allow 2G without a universal route`() {
        val readme = doc("README.md")
        val limits = readme.substringAfter("## 🚧 Technical limitations").substringBefore("\n---")
        assertTrue(limits.contains("abstains on **H1, H2, H7 and H13**"))
        assertTrue(limits.contains("**Allow 2G**") && limits.contains("**not 3G**"))
        assertTrue(limits.contains("so there is no universal route"))
        assertTrue(limits.contains("turning Airplane mode on is always the user's own\naction"))
        assertTrue(doc("MANUAL.md").contains("turning it off avoids 2G but not 3G"))
    }

    @Test fun `the 2G-3G setting text mentions Allow 2G in both languages`() {
        val es = strings("values-es").getValue("legacy_alert_description")
        val en = strings("values").getValue("legacy_alert_description")
        assertTrue(es.contains("«Permitir 2G»") && es.contains("(no 3G)") && es.contains("El modo avión es siempre decisión tuya"))
        assertTrue(en.contains("«Allow 2G»") && en.contains("(not 3G)") && en.contains("Airplane mode is always your own choice"))
        listOf(es, en).forEach { assertFalse(it.contains("bloquea") || it.contains("blocks")) }
    }

    @Test fun `the manual never promises a separate tone for a confirmed alarm`() {
        val manual = doc("MANUAL.md")
        assertFalse(manual.contains("plays the alert tone"))
        val phase3 = manual.substringAfter("**`3/3` — confirmed incident.**").substringBefore("\n\n")
        assertTrue(phase3.contains("which sounds as that channel allows (there is no separate tone)"))
    }
}
