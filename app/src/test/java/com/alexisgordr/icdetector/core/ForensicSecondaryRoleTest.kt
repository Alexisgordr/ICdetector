package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.forensics.ForensicExporter
import com.alexisgordr.icdetector.models.ForensicCase
import com.alexisgordr.icdetector.models.ForensicCaseOrigin
import com.alexisgordr.icdetector.models.ForensicCaseState
import com.alexisgordr.icdetector.models.ForensicSample
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 3.0 — En el cells.csv forense, una entrada guardada como SECONDARY_SERVING sale como SECONDARY. */
class ForensicSecondaryRoleTest {
    private fun cell(identity: String, radio: String, state: String?, dbm: Int, registered: Boolean, arfcn: Int, pci: Int) =
        JSONObject().apply {
            put("registered", registered); put("identity", identity); put("mcc", "214"); put("mnc", "07")
            put("tac", "31601"); put("cid", identity.substringAfterLast('-')); put("radio", radio)
            put("networkType", if (radio == "NR") "5G NR" else "4G LTE"); put("dbm", dbm)
            put("pci", pci); put("arfcn", arfcn); put("taUnit", "UNKNOWN")
            if (state != null) put("connectionState", state)
        }

    private fun rows(neighbors: List<JSONObject>): List<Map<String, String>> {
        val payload = JSONObject().apply {
            put("phase", 1); put("score", 70); put("anomalyConfidence", 5.0)
            put("serving", cell("214-07-31601-100-LTE", "LTE", "PRIMARY_SERVING", -80, true, 1301, 48).apply {
                put("secondaryCarriers", JSONArray().put("NR:632448:12"))
            })
            put("neighbors", JSONArray().apply { neighbors.forEach { put(it) } })
        }
        val case = ForensicCase(1, "ICD-2026-10-13-0001", "2026-10-13 10:00:00", "2026-10-13 10:00:00", null,
            ForensicCaseState.READY, ForensicCaseOrigin.ALARM, "214-07-31601-100-LTE", 1, false, 1)
        val csv = String(ForensicExporter.buildFiles(case, listOf(ForensicSample(1, 1, 1_790_000_000_000, 1, "PHASE", payload.toString())), "t", 1)
            .getValue("cells.csv")).trimEnd().lines()
        val header = csv.first().split(",")
        return csv.drop(1).map { line -> header.zip(line.split(",").map { it.trim('"') }).toMap() }
    }

    @Test fun `lte primary, unregistered nr secondary and real neighbours`() {
        val out = rows(listOf(
            cell("214-07-0-N/A-NR", "NR", "SECONDARY_SERVING", -85, false, 632448, 12),
            cell("214-07-31601-101-LTE", "LTE", "NONE", -100, false, 1301, 177),
            cell("214-07-0-N/A-NR", "NR", "NONE", -105, false, 632448, 301)
        ))
        assertEquals(listOf("SERVING", "SECONDARY", "NEIGHBOR", "NEIGHBOR"), out.map { it.getValue("role") })
        // Identidades y demás datos, sin cambios.
        assertEquals("214-07-0-N/A-NR", out[1].getValue("identity"))
        assertEquals("NR", out[1].getValue("radio"))
        assertEquals("-85", out[1].getValue("dbm"))
        assertEquals("632448", out[1].getValue("arfcn"))
        assertEquals("12", out[1].getValue("pci"))
        assertEquals("SECONDARY_SERVING", out[1].getValue("connection_state"))
        assertEquals("NR", out[3].getValue("radio"))
        assertEquals("NONE", out[3].getValue("connection_state"))
    }

    @Test fun `without a stored state the historical role is kept, never inferred from the technology`() {
        val out = rows(listOf(
            cell("214-07-0-N/A-NR", "NR", null, -85, false, 632448, 12),
            cell("214-07-31601-102-LTE", "LTE", "UNKNOWN", -95, false, 6400, 200)
        ))
        assertEquals(listOf("SERVING", "NEIGHBOR", "NEIGHBOR"), out.map { it.getValue("role") })
        assertEquals("", out[1].getValue("connection_state"))
    }

    @Test fun `an lte secondary is classified by its state too`() {
        val out = rows(listOf(cell("214-07-31601-200-LTE", "LTE", "SECONDARY_SERVING", -78, false, 6400, 200)))
        assertEquals("SECONDARY", out[1].getValue("role"))
    }

    @Test fun `the new role value is documented`() {
        fun doc(name: String) = listOf(java.io.File(name), java.io.File("../$name")).first { it.exists() }.readText()
        val manual = doc("MANUAL.md")
        listOf("`role` is `SERVING`", "`SECONDARY` (an entry the modem declared", "never from the technology",
            "stay `NEIGHBOR` as before").forEach { org.junit.Assert.assertTrue(it, manual.contains(it)) }
        org.junit.Assert.assertTrue(doc("CHANGELOG.md").contains("**Forensic `cells.csv`: `role=SECONDARY` (export format change).**"))
    }
}
