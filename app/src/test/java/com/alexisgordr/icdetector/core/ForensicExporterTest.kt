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
            listOf("case.json", "timeline.csv", "cells.csv", "heuristics.csv", "capabilities.json",
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

    @Test fun `terminal log is de-duplicated in order`() {
        assertEquals(listOf("línea A", "línea B", "línea C"),
            files.getValue("terminal.log").decodeToString().lines().filter { it.isNotBlank() })
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
