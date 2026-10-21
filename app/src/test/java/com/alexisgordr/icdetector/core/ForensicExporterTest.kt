package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.forensics.ForensicExporter
import com.alexisgordr.icdetector.models.ForensicCase
import com.alexisgordr.icdetector.models.ForensicCaseOrigin
import com.alexisgordr.icdetector.models.ForensicCaseState
import com.alexisgordr.icdetector.models.ForensicSample
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** v2.10.6 — El ZIP forense es el export que más se comparte con terceros; ahora tiene test. */
class ForensicExporterTest {
    private val case = ForensicCase(
        id = 7, caseCode = "ICD-2026-10-01-0001", createdAt = "2026-10-01 10:00:00",
        updatedAt = "2026-10-01 10:05:00", closedAt = null, state = ForensicCaseState.READY,
        origin = ForensicCaseOrigin.ALARM, cellIdentity = "214-07-1-100-LTE",
        highestPhase = 3, confirmed = true, sampleCount = 2
    )

    private fun sample(id: Long, wall: Long, event: String, logs: List<String>) = ForensicSample(
        id = id, caseId = 7, wallTimeMs = wall, elapsedTimeMs = wall - 1_000, event = event,
        payloadJson = JSONObject().apply {
            put("device", "Pixel 9a"); put("android", "16")
            put("phase", 1); put("score", 60); put("anomalyConfidence", 12.5)
            put("verified", "PENDING"); put("latencyState", "OK"); put("reason", "Salto potencia")
            put("serving", JSONObject().apply {
                put("identity", "214-07-1-100-LTE"); put("mcc", "214"); put("mnc", "07")
                put("tac", "1"); put("cid", "100"); put("radio", "LTE"); put("dbm", -70)
            })
            put("neighbors", org.json.JSONArray().put(JSONObject().apply { put("pci", 48) }))
            put("diagnostics", org.json.JSONArray().put(JSONObject().apply {
                put("id", 2); put("name", "Salto de potencia"); put("status", "FAILED")
            }))
            put("logs", org.json.JSONArray().apply { logs.forEach { put(it) } })
        }.toString()
    )

    private val files = ForensicExporter.buildFiles(
        forensicCase = case,
        samples = listOf(
            sample(1, 1_790_000_000_000, "ANOMALY_STARTED", listOf("línea A", "línea B")),
            sample(2, 1_790_000_015_000, "OBSERVATION", listOf("línea B", "línea C"))
        ),
        appVersion = "2.10.6",
        appVersionCode = 34
    )

    @Test fun `contains every documented file in order`() {
        assertEquals(
            listOf("case.json", "timeline.csv", "cells.csv", "heuristics.csv", "trust_site.csv", "capabilities.json",
                "terminal.log", "SHA256SUMS.txt"),
            files.keys.toList()
        )
    }

    @Test fun `case metadata comes from the case and the first sample`() {
        val json = JSONObject(files.getValue("case.json").decodeToString())
        assertEquals("ICD-2026-10-01-0001", json.getString("caseId"))
        assertEquals(2, json.getInt("sampleCount"))
        assertEquals("2.10.6", json.getString("appVersion"))
        assertEquals("Pixel 9a", json.getString("device"))
        assertTrue(json.isNull("closedAt"))
    }

    @Test fun `tables keep one row per sample and per cell`() {
        fun rows(name: String) = files.getValue(name).decodeToString().lines().count { it.isNotBlank() } - 1
        assertEquals(2, rows("timeline.csv"))
        assertEquals(4, rows("cells.csv"))       // serving + 1 vecina por muestra
        assertEquals(2, rows("heuristics.csv"))
    }

    @Test fun `terminal log joins overlapping windows in order`() {
        assertEquals(listOf("línea A", "línea B", "línea C"),
            files.getValue("terminal.log").decodeToString().lines().filter { it.isNotBlank() })
    }

    /** 3.0 (corte de campaña) — Solo se quita el solapamiento entre ventanas, no las repeticiones. */
    @Test fun `repeated terminal lines are events and are kept`() {
        val merge = ForensicExporter::mergeLogWindows
        assertEquals(listOf("a", "b", "b", "c"), merge(listOf(listOf("a", "b", "b"), listOf("b", "b", "c"))))
        assertEquals(listOf("a", "a", "a"), merge(listOf(listOf("a", "a"), listOf("a", "a", "a"))))
        assertEquals("sin solapamiento, se añade entera", listOf("a", "b", "c", "d"), merge(listOf(listOf("a", "b"), listOf("c", "d"))))
        assertEquals("ventana repetida, nada nuevo", listOf("a", "b"), merge(listOf(listOf("a", "b"), listOf("a", "b"))))
    }

    /**
     * 3.0 (corte de campaña) — Todo lo que escribe ForensicRecorder lo lee ForensicExporter, y al revés.
     * Antes localTrust, stableSite, heuristics, csgIndicator, bands y secondaryCarriers se guardaban
     * y no se exportaban. `capabilities` se exporta como objeto entero.
     */
    @Test fun `every key the recorder writes is exported, and the exporter reads only recorded keys`() {
        fun source(name: String) = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/forensics/$name").readText()
        val payload = source("ForensicRecorder.kt").substringAfter("private fun payload(")
        val capabilities = payload.substringAfter("put(\"capabilities\"").substringBefore("})")
        val written = Regex("""put\("(\w+)"""").findAll(payload.replace(capabilities, "")).map { it.groupValues[1] }.toSet()
        val exporter = source("ForensicExporter.kt").substringAfter("internal fun buildFiles(")
        val read = (Regex("""(?:opt|get)\w*\("(\w+)"""").findAll(exporter) + Regex("""nullable\(\w+,\s*"(\w+)"\)""").findAll(exporter))
            .map { it.groupValues[1] }.toSet()
        assertTrue("guardado y no exportado: ${written - read}", read.containsAll(written))
        // 3.1.0 — `latencyState` solo se lee de muestras anteriores (la sonda de latencia se retiró).
        assertTrue("leído y nunca guardado: ${read - written}", written.containsAll(read - "latencyState"))
    }

    @Test fun `trust, site, data quality and rule states reach the export`() {
        val payload = JSONObject().apply {
            put("schemaVersion", 1); put("phase", 1); put("requiredPhases", 3); put("score", 70)
            put("reason", ""); put("dataQualityReason", "Calidad de dato: EARFCN 4G fuera de la tabla 3GPP")
            put("heuristics", "H1=NOT_EVALUATED(csg);H8=FAILED")
            put("serving", JSONObject().apply {
                put("identity", "214-07-1-100-LTE"); put("registered", true); put("csgIndicator", true)
                put("bands", org.json.JSONArray().put(3).put(7)); put("secondaryCarriers", org.json.JSONArray().put("NR:632448:12"))
            })
            put("localTrust", JSONObject().apply {
                put("state", "CHANGED"); put("confidencePercent", 64); put("contradictions", org.json.JSONArray().put("PCI").put("HANDOVER"))
            })
            put("stableSite", JSONObject().apply { put("featureState", "SHADOW"); put("novelty", "NEW_CELL"); put("reason", "NOVEL") })
        }
        val out = ForensicExporter.buildFiles(case, listOf(ForensicSample(1, 7, 1_790_000_000_000, 1, "TRUST_CONTRADICTION", payload.toString())), "t", 1)
        fun table(name: String): Map<String, String> {
            val lines = out.getValue(name).decodeToString().trimEnd().lines()
            return lines.first().split(",").zip(lines[1].split("\",\"").map { it.trim('"') }).toMap()
        }
        assertEquals("CHANGED", table("trust_site.csv")["local_trust_state"])
        assertEquals("PCI;HANDOVER", table("trust_site.csv")["local_trust_contradictions"])
        assertEquals("NEW_CELL", table("trust_site.csv")["site_novelty"])
        assertEquals("Calidad de dato: EARFCN 4G fuera de la tabla 3GPP", table("timeline.csv")["data_quality_reason"])
        assertEquals("H1=NOT_EVALUATED(csg);H8=FAILED", table("timeline.csv")["heuristics"])
        assertEquals("3", table("timeline.csv")["required_phases"])
        assertEquals("3;7", table("cells.csv")["bands"])
        assertEquals("true", table("cells.csv")["csg_indicator"])
        assertEquals("NR:632448:12", table("cells.csv")["secondary_carriers"])
        assertEquals(1, JSONObject(out.getValue("case.json").decodeToString()).getInt("sampleSchemaVersion"))
    }

    @Test fun `checksums match every other file`() {
        val sums = files.getValue("SHA256SUMS.txt").decodeToString().lines().filter { it.isNotBlank() }
        assertEquals(files.size - 1, sums.size)
        sums.forEach { line ->
            val (hash, name) = line.split("  ", limit = 2)
            val expected = MessageDigest.getInstance("SHA-256").digest(files.getValue(name))
                .joinToString("") { "%02x".format(it) }
            assertEquals(name, expected, hash)
        }
    }
}
