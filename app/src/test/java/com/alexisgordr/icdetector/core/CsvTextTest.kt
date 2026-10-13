package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.forensics.ForensicExporter
import com.alexisgordr.icdetector.forensics.TopologyExporter
import com.alexisgordr.icdetector.models.CellTransitionSummary
import com.alexisgordr.icdetector.models.ForensicCase
import com.alexisgordr.icdetector.models.ForensicCaseOrigin
import com.alexisgordr.icdetector.models.ForensicCaseState
import com.alexisgordr.icdetector.models.ForensicSample
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.ServiceRegistrationState
import com.alexisgordr.icdetector.models.ServiceStateSnapshot
import com.alexisgordr.icdetector.models.ServiceStateSource
import com.alexisgordr.icdetector.utils.CsvText
import com.alexisgordr.icdetector.utils.ExportUtils
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** 3.0 — Texto no confiable en los CSV: se exporta como texto y el fichero sigue siendo CSV válido. */
class CsvTextTest {

    /** Lector RFC 4180 mínimo: comillas, comillas dobladas, comas y saltos de línea dentro de campo. */
    private fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val f = StringBuilder()
        var i = 0; var quoted = false
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"' && i + 1 < text.length && text[i + 1] == '"') { f.append('"'); i++ }
                else if (ch == '"') quoted = false else f.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                ',' -> { row.add(f.toString()); f.clear() }
                '\n' -> { row.add(f.toString()); f.clear(); rows.add(row); row = mutableListOf() }
                '\r' -> {}
                else -> f.append(ch)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
        return rows
    }

    private fun unguard(s: String) = if (s.startsWith(CsvText.TEXT_GUARD)) s.drop(1) else s

    private val risky = listOf("=1+1", "+1+1", "-1+1", "@SUM(1,1)", "\t=1+1", "\r=1+1", "\u0000=1", "'quoted", "＝1+1")

    @Test fun `text that could start a formula is prefixed and reversible`() {
        risky.forEach { v ->
            val g = CsvText.guard(v)
            assertEquals("prefijo para ${v.toCharArray().map { it.code }}", "'$v", g)
            assertEquals(v, unguard(g))
        }
    }

    @Test fun `numbers and ordinary text are not changed`() {
        listOf("-95", "-12.5", "40.4168", "-3.7038", "-1.0E-5", "+34", "0", ".5", "-.5",
            "Movistar", "LTE:6400:200", "H1;H9", "214-07-31601-100-LTE", "[sub-umbral] Celda aislada", "")
            .forEach { assertEquals(it, CsvText.guard(it)) }
    }

    @Test fun `quotes, separators and newlines still round-trip`() {
        val values = risky + listOf("a,b", "say \"hi\"", "line1\nline2", "x;y", "-1+1,\"x\"\nnext", "Movistar", "-95")
        val minimal = values.joinToString(",") { CsvText.field(it) } + "\n"
        val quoted = values.joinToString(",") { CsvText.quoted(it) } + "\n"
        listOf(minimal, quoted).forEach { line ->
            val parsed = parse(line).single()
            assertEquals(values.size, parsed.size)
            assertEquals(values, parsed.map(::unguard))
        }
        assertEquals("x;y", CsvText.field("x;y"))
        assertEquals("-95", CsvText.field(-95))
    }

    @Test fun `history csv keeps numbers and protects network text`() {
        val row = ExportUtils.csvRow(HistoryRecord(
            timestamp = "2026-09-25 10:00:00", netType = "4G LTE", cid = "100", mnc = "07", tac = "31601",
            mcc = "214", dbm = -90, lat = 40.4168, lon = -3.7038, rsrq = -12, radio = RadioTech.LTE,
            csgName = "=1+1", additionalPlmns = "@SUM(1,1)", networkOperator = "+1+1", simOperator = "-1+1"
        ))
        val header = ExportUtils.CSV_HEADER.split(",")
        val cols = parse(ExportUtils.CSV_HEADER + "\n" + row + "\n")[1]
        assertEquals(header.size, cols.size)
        assertEquals("'=1+1", cols[header.indexOf("CsgName")])
        assertEquals("'@SUM(1,1)", cols[header.indexOf("AdditionalPlmns")])
        assertEquals("'+1+1", cols[header.indexOf("NetworkOperator")])
        assertEquals("'-1+1", cols[header.indexOf("SimOperator")])
        assertEquals("-90", cols[header.indexOf("DBM")])
        assertEquals("-3.7038", cols[header.indexOf("Lon")])
        assertEquals("-12", cols[header.indexOf("RSRQ")])
    }

    @Test fun `service state csv protects the network name`() {
        val row = ExportUtils.serviceStateCsvRow(ServiceStateSnapshot(
            timestampMs = 1_790_000_000_000, state = ServiceRegistrationState.IN_SERVICE,
            operatorNumeric = "21407", operatorAlphaLong = "-1+1,\"x\"\nnext", simOperator = "21407",
            cellBandwidthsKhz = listOf(20_000, 10_000), source = ServiceStateSource.CALLBACK
        ))
        val header = ExportUtils.SERVICE_STATE_CSV_HEADER.split(",")
        val cols = parse(row + "\n").single()
        assertEquals(header.size, cols.size)
        assertEquals("'-1+1,\"x\"\nnext", cols[header.indexOf("NetworkName")])
        assertEquals("20000;10000", cols[header.indexOf("CellBandwidthsKHz")])
    }

    @Test fun `forensic and topology csv protect identities without touching numbers`() {
        val case = ForensicCase(7, "ICD-2026-10-01-0001", "2026-10-01 10:00:00", "2026-10-01 10:05:00", null,
            ForensicCaseState.READY, ForensicCaseOrigin.ALARM, "=1+1", 3, true, 1)
        val payload = JSONObject().apply {
            put("phase", 1); put("score", 60); put("anomalyConfidence", 12.5); put("reason", "@SUM(1,1)")
            put("serving", JSONObject().apply { put("identity", "=1+1"); put("mcc", "+1+1"); put("dbm", -70); put("rsrq", -11) })
            put("neighbors", JSONArray())
            put("gps", JSONObject().apply { put("lat", 40.4); put("lon", -3.7); put("accuracyM", 8.0) })
        }
        val files = ForensicExporter.buildFiles(case, listOf(ForensicSample(1, 7, 1_790_000_000_000, 1, "ALARM", payload.toString())), "t", 1)
        val cells = parse(String(files.getValue("cells.csv")))
        val h = cells[0]
        assertEquals("'=1+1", cells[1][h.indexOf("identity")])
        assertEquals("'+1+1", cells[1][h.indexOf("mcc")])
        assertEquals("-70", cells[1][h.indexOf("dbm")])
        assertEquals("-11", cells[1][h.indexOf("rsrq")])
        val timeline = parse(String(files.getValue("timeline.csv")))
        assertEquals("'@SUM(1,1)", timeline[1][timeline[0].indexOf("reason")])
        assertEquals("-3.7", timeline[1][timeline[0].indexOf("gps_lon")])
        assertTrue("la evidencia JSON no se toca", String(files.getValue("case.json")).contains("\"cellIdentity\": \"=1+1\""))

        val topo = TopologyExporter.buildFiles(listOf(CellTransitionSummary("+1+1", "B", 2, 1, HeuristicStatus.PASSED, 1L)), "t", 1, Instant.EPOCH)
        val t = parse(String(topo.getValue("transitions.csv")))
        assertEquals("'+1+1", t[1][t[0].indexOf("from_identity")])
    }

    @Test fun `every csv exporter uses the shared function`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        mapOf(
            "forensics/ForensicExporter.kt" to "CsvText.quoted(value)",
            "forensics/TopologyExporter.kt" to "CsvText.quoted(value)",
            "forensics/GeometryExporter.kt" to "CsvText.quoted(value)",
            "storage/CellDbHelper.kt" to "CsvText.field(v)",
            "utils/ExportUtils.kt" to "CsvText.field(value)"
        ).forEach { (file, call) ->
            val src = main.resolve("com/alexisgordr/icdetector/$file").readText()
            assertTrue("$file usa $call", src.contains(call))
            assertFalse("$file ya no escapa por su cuenta", src.contains(".replace(\"\\\"\",\"\\\"\\\"\")") || src.contains(".replace(\"\\\"\", \"\\\"\\\"\")"))
        }
    }

    @Test fun `the prefix is documented without promising universal protection`() {
        fun doc(name: String) = listOf(File(name), File("../$name")).first { it.exists() }.readText()
        val manual = doc("MANUAL.md").substringAfter("### Text that a spreadsheet could read as a formula")
            .substringBefore("\n## ")
        listOf("`NetworkName`", "`CsgName`", "one\napostrophe (`'`)", "remove exactly **one** leading apostrophe",
            "Plain numbers are never changed", "it does not guarantee it", "JSON evidence")
            .forEach { assertTrue(it, manual.contains(it)) }
        assertTrue(doc("CHANGELOG.md").contains("**CSV text that could look like a formula (export format change).**"))
        assertTrue(doc("README.md").contains("**Text that could look like a formula**"))
    }
}
