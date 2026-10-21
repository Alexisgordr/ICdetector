package com.alexisgordr.icdetector.forensics

import android.content.Context
import android.net.Uri
import com.alexisgordr.icdetector.models.ForensicCase
import com.alexisgordr.icdetector.models.ForensicSample
import com.alexisgordr.icdetector.storage.CellDbHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ForensicExporter {
    fun export(context: Context, db: CellDbHelper, forensicCase: ForensicCase, uri: Uri) {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val files = buildFiles(
            forensicCase = forensicCase,
            samples = db.getForensicSamples(forensicCase.id),
            appVersion = com.alexisgordr.icdetector.core.AppBuildId.current(packageInfo.versionName) ?: "unknown",
            appVersionCode = packageInfo.longVersionCode
        )
        context.contentResolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(raw).use { zip -> files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            } }
        } ?: error("No se pudo abrir el destino del caso forense")
    }

    /**
     * v2.10.6 — Contenido del ZIP, separado de Android para poder probarlo en la JVM, igual que
     * TopologyExporter y GeometryExporter. Los archivos y su contenido no cambian.
     */
    internal fun buildFiles(
        forensicCase: ForensicCase,
        samples: List<ForensicSample>,
        appVersion: String,
        appVersionCode: Long
    ): LinkedHashMap<String, ByteArray> {
        val parsed = samples.map { it to JSONObject(it.payloadJson) }
        val files = linkedMapOf<String, ByteArray>()
        val firstPayload = parsed.firstOrNull()?.second

        files["case.json"] = JSONObject().apply {
            put("schemaVersion", 1); put("caseId", forensicCase.caseCode)
            put("state", forensicCase.state.name); put("createdAt", forensicCase.createdAt)
            put("captureOrigin", forensicCase.origin.name)
            put("updatedAt", forensicCase.updatedAt); put("closedAt", forensicCase.closedAt ?: JSONObject.NULL)
            put("cellIdentity", forensicCase.cellIdentity); put("highestPhase", forensicCase.highestPhase)
            put("confirmed", forensicCase.confirmed); put("sampleCount", samples.size)
            put("appVersion", appVersion)
            put("appVersionCode", appVersionCode)
            put("device", firstPayload?.optString("device") ?: "unknown")
            put("android", firstPayload?.optString("android") ?: "unknown")
            // Versión del formato de las muestras guardadas (la de la primera).
            put("sampleSchemaVersion", firstPayload?.optInt("schemaVersion", 0) ?: 0)
            put("integrityNote", "SHA-256 detects later modification; it is not a legal chain-of-custody signature.")
        }.toString(2).toByteArray()

        files["timeline.csv"] = buildString {
            // 3.0 (corte de campaña) — Columnas nuevas al final: fases necesarias, aviso de calidad de
            // dato (H8) y estado de cada regla con sus motivos de abstención.
            // 3.1.0 — `latency_state` se conserva en su sitio, vacía en las muestras nuevas (sin sonda
            // de latencia); `verified` es siempre PENDING (sin verificación externa).
            appendLine("timestamp_utc,elapsed_ms,event,phase,score,anomaly_confidence,cell_identity,verified,latency_state,gps_lat,gps_lon,gps_accuracy_m,reason," +
                "required_phases,data_quality_reason,heuristics")
            parsed.forEach { (s, j) ->
                val serving = j.getJSONObject("serving")
                val gps = j.optJSONObject("gps")
                appendLine(listOf(Instant.ofEpochMilli(s.wallTimeMs), s.elapsedTimeMs, s.event, j.optInt("phase"),
                    j.optInt("score"), j.optDouble("anomalyConfidence"), serving.optString("identity"),
                    j.optString("verified"), j.optString("latencyState"), gps?.optDouble("lat"),
                    gps?.optDouble("lon"), gps?.optDouble("accuracyM"), j.optString("reason"),
                    nullable(j, "requiredPhases"), nullable(j, "dataQualityReason"), j.optString("heuristics")
                ).joinToString(",") { csv(it) })
            }
        }.toByteArray()

        files["cells.csv"] = buildString {
            appendLine("timestamp_utc,role,identity,mcc,mnc,tac,cid,radio,network_type,dbm,pci,arfcn,band,rsrq,sinr,ta,ta_unit,connection_state,bandwidth_khz," +
                "registered,bands,csg_indicator,secondary_carriers")
            parsed.forEach { (s, j) ->
                fun row(role: String, c: JSONObject) = appendLine(listOf(
                    Instant.ofEpochMilli(s.wallTimeMs), role, c.optString("identity"), c.optString("mcc"),
                    c.optString("mnc"), c.optString("tac"), c.optString("cid"), c.optString("radio"),
                    c.optString("networkType"), nullable(c,"dbm"), nullable(c,"pci"), nullable(c,"arfcn"),
                    nullable(c,"band"), nullable(c,"rsrq"), nullable(c,"sinr"), nullable(c,"ta"), c.optString("taUnit"),
                    // v2.10.4 — vacío en muestras anteriores, que no guardaban estos campos.
                    c.optString("connectionState"), nullable(c,"bandwidthKhz"),
                    // 3.0 (corte de campaña) — Contexto de radio que la muestra ya guardaba.
                    nullable(c, "registered"), joined(c.optJSONArray("bands")), nullable(c, "csgIndicator"),
                    joined(c.optJSONArray("secondaryCarriers"))
                ).joinToString(",") { csv(it) })
                row("SERVING", j.getJSONObject("serving"))
                val n = j.optJSONArray("neighbors") ?: JSONArray()
                for (i in 0 until n.length()) n.getJSONObject(i).let { c -> row(neighborRole(c), c) }
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

        // 3.0 (corte de campaña) — Confianza local y Stable-Site de cada muestra. Antes no se exportaban:
        // un caso TRUST_CONTRADICTION salía sin las contradicciones que lo abrieron.
        files["trust_site.csv"] = buildString {
            appendLine("timestamp_utc,local_trust_state,local_trust_confidence_percent,local_trust_contradictions," +
                "site_key,site_feature_state,site_novelty,site_would_trigger,site_enforced,site_reason," +
                "site_static_duration_s,site_location_accuracy_m,site_serving_days,site_neighbour_days")
            parsed.forEach { (s, j) ->
                val trust = j.optJSONObject("localTrust") ?: JSONObject()
                val site = j.optJSONObject("stableSite") ?: JSONObject()
                appendLine(listOf(Instant.ofEpochMilli(s.wallTimeMs),
                    trust.optString("state"), nullable(trust, "confidencePercent"), joined(trust.optJSONArray("contradictions")),
                    nullable(site, "siteKey"), site.optString("featureState"), site.optString("novelty"),
                    nullable(site, "wouldTrigger"), nullable(site, "enforced"), site.optString("reason"),
                    nullable(site, "staticDurationSeconds"), nullable(site, "locationAccuracyM"),
                    nullable(site, "servingDays"), nullable(site, "neighbourDays")
                ).joinToString(",") { csv(it) })
            }
        }.toByteArray()

        files["capabilities.json"] = JSONObject().apply {
            put("schemaVersion", 1)
            put("observations", JSONArray().apply { parsed.forEach { (s, j) -> put(JSONObject().apply {
                put("timestamp", Instant.ofEpochMilli(s.wallTimeMs).toString())
                put("values", j.optJSONObject("capabilities") ?: JSONObject())
            }) } })
        }.toString(2).toByteArray()

        // Cada muestra guarda las últimas 40 líneas del terminal, así que muestras seguidas se solapan.
        // 3.0 (corte de campaña) — Se unen quitando solo el solapamiento (mergeLogWindows), no las
        // líneas repetidas: dos líneas iguales pueden ser dos eventos.
        files["terminal.log"] = buildString {
            mergeLogWindows(parsed.map { (_, j) ->
                val logs = j.optJSONArray("logs") ?: JSONArray()
                (0 until logs.length()).map { logs.optString(it) }
            }).forEach { appendLine(it) }
        }.toByteArray()

        files["SHA256SUMS.txt"] = files.entries.joinToString("\n", postfix = "\n") { (name, bytes) ->
            "${sha256(bytes)}  $name"
        }.toByteArray()
        return LinkedHashMap(files)
    }

    /**
     * 3.0 — Papel de una entrada de la lista `neighbors` de la muestra (todas las celdas no
     * registradas, secundarias incluidas). Solo por el `connectionState` GUARDADO en la muestra, sin
     * deducir nada de la tecnología: `SECONDARY_SERVING` es una portadora secundaria de la conexión
     * (por ejemplo la pata NR de NSA), no una vecina. Sin estado guardado (muestras anteriores a
     * v2.10.4) o con `NONE`/`UNKNOWN`, se conserva `NEIGHBOR` como antes; ahí no se puede distinguir.
     */
    internal fun neighborRole(cell: JSONObject): String =
        if (cell.optString("connectionState") == "SECONDARY_SERVING") "SECONDARY" else "NEIGHBOR"

    /**
     * Une ventanas consecutivas del terminal: de cada ventana se añade solo lo que sigue al mayor
     * solapamiento entre el final de lo ya unido y su comienzo. Conserva orden y repeticiones.
     */
    internal fun mergeLogWindows(windows: List<List<String>>): List<String> {
        val merged = mutableListOf<String>()
        windows.forEach { window ->
            val overlap = (minOf(merged.size, window.size) downTo 0).first { k ->
                k == 0 || merged.subList(merged.size - k, merged.size) == window.subList(0, k)
            }
            merged += window.drop(overlap)
        }
        return merged
    }

    private fun joined(array: JSONArray?): String =
        if (array == null) "" else (0 until array.length()).joinToString(";") { array.optString(it) }

    private fun nullable(o: JSONObject, key: String): Any = if (!o.has(key) || o.isNull(key)) "" else o.get(key)
    // 3.0 — Escapado y protección de texto compartidos (ver CsvText). Siempre entre comillas, como antes.
    private fun csv(value: Any?): String = com.alexisgordr.icdetector.utils.CsvText.quoted(value)
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
