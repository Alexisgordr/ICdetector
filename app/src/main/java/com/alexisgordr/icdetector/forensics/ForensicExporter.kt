package com.alexisgordr.icdetector.forensics

import android.content.Context
import android.net.Uri
import com.alexisgordr.icdetector.models.ForensicCase
import com.alexisgordr.icdetector.storage.CellDbHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ForensicExporter {
    fun export(context: Context, db: CellDbHelper, forensicCase: ForensicCase, uri: Uri) {
        val samples = db.getForensicSamples(forensicCase.id)
        val parsed = samples.map { it to JSONObject(it.payloadJson) }
        val files = linkedMapOf<String, ByteArray>()
        val firstPayload = parsed.firstOrNull()?.second
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

        files["case.json"] = JSONObject().apply {
            put("schemaVersion", 1); put("caseId", forensicCase.caseCode)
            put("state", forensicCase.state.name); put("createdAt", forensicCase.createdAt)
            put("captureOrigin", forensicCase.origin.name)
            put("updatedAt", forensicCase.updatedAt); put("closedAt", forensicCase.closedAt ?: JSONObject.NULL)
            put("cellIdentity", forensicCase.cellIdentity); put("highestPhase", forensicCase.highestPhase)
            put("confirmed", forensicCase.confirmed); put("sampleCount", samples.size)
            put("appVersion", packageInfo.versionName ?: "unknown")
            put("appVersionCode", packageInfo.longVersionCode)
            put("device", firstPayload?.optString("device") ?: "unknown")
            put("android", firstPayload?.optString("android") ?: "unknown")
            put("integrityNote", "SHA-256 detects later modification; it is not a legal chain-of-custody signature.")
        }.toString(2).toByteArray()

        files["timeline.csv"] = buildString {
            appendLine("timestamp_utc,elapsed_ms,event,phase,score,anomaly_confidence,cell_identity,verified,latency_state,gps_lat,gps_lon,gps_accuracy_m,reason")
            parsed.forEach { (s, j) ->
                val serving = j.getJSONObject("serving")
                val gps = j.optJSONObject("gps")
                appendLine(listOf(Instant.ofEpochMilli(s.wallTimeMs), s.elapsedTimeMs, s.event, j.optInt("phase"),
                    j.optInt("score"), j.optDouble("anomalyConfidence"), serving.optString("identity"),
                    j.optString("verified"), j.optString("latencyState"), gps?.optDouble("lat"),
                    gps?.optDouble("lon"), gps?.optDouble("accuracyM"), j.optString("reason")
                ).joinToString(",") { csv(it) })
            }
        }.toByteArray()

        files["cells.csv"] = buildString {
            appendLine("timestamp_utc,role,identity,mcc,mnc,tac,cid,radio,network_type,dbm,pci,arfcn,band,rsrq,sinr,ta,ta_unit")
            parsed.forEach { (s, j) ->
                fun row(role: String, c: JSONObject) = appendLine(listOf(
                    Instant.ofEpochMilli(s.wallTimeMs), role, c.optString("identity"), c.optString("mcc"),
                    c.optString("mnc"), c.optString("tac"), c.optString("cid"), c.optString("radio"),
                    c.optString("networkType"), nullable(c,"dbm"), nullable(c,"pci"), nullable(c,"arfcn"),
                    nullable(c,"band"), nullable(c,"rsrq"), nullable(c,"sinr"), nullable(c,"ta"), c.optString("taUnit")
                ).joinToString(",") { csv(it) })
                row("SERVING", j.getJSONObject("serving"))
                val n = j.optJSONArray("neighbors") ?: JSONArray()
                for (i in 0 until n.length()) row("NEIGHBOR", n.getJSONObject(i))
            }
        }.toByteArray()

        files["heuristics.csv"] = buildString {
            appendLine("timestamp_utc,heuristic_id,name,status,explanation")
            parsed.forEach { (s, j) ->
                val d = j.optJSONArray("diagnostics") ?: JSONArray()
                for (i in 0 until d.length()) d.getJSONObject(i).let { x ->
                    appendLine(listOf(Instant.ofEpochMilli(s.wallTimeMs), x.optInt("id"), x.optString("name"),
                        x.optString("status"), x.optString("explanation")).joinToString(",") { csv(it) })
                }
            }
        }.toByteArray()

        files["capabilities.json"] = JSONObject().apply {
            put("schemaVersion", 1)
            put("observations", JSONArray().apply { parsed.forEach { (s, j) -> put(JSONObject().apply {
                put("timestamp", Instant.ofEpochMilli(s.wallTimeMs).toString())
                put("values", j.optJSONObject("capabilities") ?: JSONObject())
            }) } })
        }.toString(2).toByteArray()

        val uniqueLogs = linkedSetOf<String>()
        parsed.forEach { (_, j) ->
            val logs = j.optJSONArray("logs") ?: JSONArray()
            for (i in 0 until logs.length()) uniqueLogs += logs.optString(i)
        }
        files["terminal.log"] = buildString {
            uniqueLogs.forEach { appendLine(it) }
        }.toByteArray()

        files["SHA256SUMS.txt"] = files.entries.joinToString("\n", postfix = "\n") { (name, bytes) ->
            "${sha256(bytes)}  $name"
        }.toByteArray()

        context.contentResolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(raw).use { zip -> files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            } }
        } ?: error("No se pudo abrir el destino del caso forense")
    }

    private fun nullable(o: JSONObject, key: String): Any = if (!o.has(key) || o.isNull(key)) "" else o.get(key)
    private fun csv(value: Any?): String = "\"${value?.toString().orEmpty().replace("\"", "\"\"")}\""
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
