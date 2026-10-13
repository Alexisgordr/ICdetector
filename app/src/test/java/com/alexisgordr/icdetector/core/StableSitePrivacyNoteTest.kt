package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.forensics.StableSiteExportNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** 3.0 — Las claves Stable-Site no son anónimas, y el export y la documentación lo dicen. */
class StableSitePrivacyNoteTest {
    private fun doc(name: String) = listOf(File(name), File("../$name")).first { it.exists() }.readText()
    private val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }

    @Test fun `a key can be matched to an approximate place by a candidate search`() {
        // Lo que afirma la nota: sin sal, cualquiera recalcula las claves de una zona y las empareja.
        val target = StableSiteKey.from(40.4168, -3.7038)
        var found: Pair<Double, Double>? = null
        var lat = 40.39
        while (found == null && lat <= 40.44) {
            var lon = -3.73
            while (found == null && lon <= -3.68) {
                if (target in StableSiteKey.candidates(lat, lon)) found = lat to lon
                lon += 0.002
            }
            lat += 0.002
        }
        val (fLat, fLon) = checkNotNull(found) { "la búsqueda de candidatos encuentra la clave" }
        assertTrue("a menos de ~1 km", abs(fLat - 40.4168) < 0.01 && abs(fLon + 3.7038) < 0.012)
        assertEquals("determinista", target, StableSiteKey.from(40.4168, -3.7038))
    }

    @Test fun `the zip starts with a readable privacy note and keeps the csv files unchanged`() {
        val csv = linkedMapOf("sites.csv" to "site_key\nabc\n", "motion.csv" to "x\n")
        val files = StableSiteExportNote.withPrivacyNote(csv)
        assertEquals(listOf("PRIVACY.txt", "sites.csv", "motion.csv"), files.keys.toList())
        assertEquals(csv["sites.csv"], files["sites.csv"])
        val note = files.getValue("PRIVACY.txt")
        listOf("not anonymous", "deterministic, unsalted SHA-256", "candidate search",
            "approximate location", "complete cell identities", "reveal habits")
            .forEach { assertTrue(it, note.contains(it)) }
    }

    @Test fun `the export warning exists in both languages and is shown before exporting`() {
        listOf("values", "values-es").forEach { dir ->
            val strings = res.resolve("$dir/strings.xml").readText()
            assertTrue(dir, strings.contains("name=\"stable_site_export_privacy_warning\""))
            assertTrue(dir, strings.contains("name=\"export_stable_site_title\""))
        }
        assertTrue(res.resolve("values/strings.xml").readText().contains("deterministic, unsalted hashes"))
        assertTrue(res.resolve("values-es/strings.xml").readText().contains("hashes deterministas y sin sal"))
        val screen = main.resolve("com/alexisgordr/icdetector/ui/HistoryScreen.kt").readText()
        assertTrue(screen.contains("onClick = { confirmStableSiteExport = true }"))
        assertTrue(screen.contains("stringResource(R.string.stable_site_export_privacy_warning)"))
        assertTrue(main.resolve("com/alexisgordr/icdetector/forensics/StableSiteExporter.kt").readText()
            .contains("StableSiteExportNote.withPrivacyNote("))
    }

    @Test fun `readme and manual explain the limits of the hash`() {
        assertTrue(doc("MANUAL.md").contains("**Site keys are not anonymous.** A site key is a deterministic, **unsalted** SHA-256"))
        assertTrue(doc("MANUAL.md").contains("it does not provide\nanonymity"))
        assertTrue(doc("README.md").contains("**The keys are not anonymous:**"))
    }
}
