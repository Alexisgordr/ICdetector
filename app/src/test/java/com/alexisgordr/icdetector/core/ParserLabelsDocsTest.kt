package com.alexisgordr.icdetector.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — NR/GSM: sin deducir SA, sin prometer otra medida que SS-RSRP, BSIC reconocido. */
class ParserLabelsDocsTest {
    private val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
    private fun src(name: String) = main.walkTopDown().first { it.name == name }.readText()
    private fun doc(name: String) = listOf(File(name), File("../$name")).first { it.exists() }.readText()

    @Test fun `an NR cell is labelled 5G NR without claiming SA`() {
        val service = src("MiniICService.kt")
        val nr = service.substringAfter("} else if (info is CellInfoNr) {").substringBefore("} else if (info is CellInfoWcdma)")
        assertTrue(nr.contains("\"5G NR\""))
        assertFalse(nr.contains("\"5G NR (SA)\""))
        assertTrue(src("Visualizers.kt").contains("radioTech == com.alexisgordr.icdetector.models.RadioTech.NR || networkType.contains(\"(SA)\")"))
    }

    @Test fun `NR keeps SS-RSRP and the comments are accurate`() {
        val parser = src("CellParser.kt")
        assertTrue(parser.contains("val dbm = r.ssRsrp.let { if (it == Int.MAX_VALUE) r.dbm else it }"))
        assertFalse(parser.contains("csiRsrp"))
        assertTrue(parser.contains("NO es una medida alternativa garantizada"))
        assertFalse(parser.contains("dbm\n                // normalizado del framework"))
        val channels = src("RadioChannels.kt")
        assertFalse(channels.contains("GSM no tiene identificador físico en la API"))
        assertTrue(channels.contains("BSIC"))
        assertFalse("BSIC no entra en el campo PCI", parser.contains("bsic"))
    }

    @Test fun `the manual documents the new values in new rows`() {
        val manual = doc("MANUAL.md")
        assertTrue(manual.contains("`PCI` and `ARFCN` are empty when Android reported \"not available\""))
        assertTrue(manual.contains("LTE index 0–1282, GSM index 0–219"))
        assertTrue(manual.contains("T_A index (0–3846), not microseconds"))
        assertTrue(manual.contains("never from the manufacturer's text"))
        assertTrue(manual.contains("Carrier aggregation is not handled specially here"))
        assertTrue(manual.contains("`NetType` for an NR cell is `5G NR`"))
    }

    @Test fun `stored rows are never rewritten, so old backups keep their values`() {
        // La restauración instala la base byte a byte (RestoreEngineTest) y el esquema no cambia; aquí
        // se comprueba que ningún código reescribe PCI, ARFCN o TA de filas ya guardadas.
        val db = src("CellDbHelper.kt")
        listOf("put(COLUMN_PCI, pci)", "put(COLUMN_ARFCN, arfcn)", "put(COLUMN_TA, timingAdvance)").forEach {
            assertTrue(it, db.contains(it))
            assertTrue("solo una escritura (al insertar): " + it, db.indexOf(it) == db.lastIndexOf(it))
        }
        db.lines().filter { it.contains("UPDATE") && it.contains("TABLE_HISTORY") }.forEach { line ->
            listOf("COLUMN_PCI", "COLUMN_ARFCN", "COLUMN_TA").forEach { assertFalse(line, line.contains(it)) }
        }
    }
}
