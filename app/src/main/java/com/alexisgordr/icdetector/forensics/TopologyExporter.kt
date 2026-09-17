package com.alexisgordr.icdetector.forensics

import android.content.Context
import android.net.Uri
import com.alexisgordr.icdetector.models.CellTransitionSummary
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exportación interoperable y de solo lectura del grafo local de handovers. */
object TopologyExporter {
    fun export(context: Context, transitions: List<CellTransitionSummary>, uri: Uri) {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val files = buildFiles(
            transitions = transitions,
            appVersion = packageInfo.versionName ?: "unknown",
            appVersionCode = packageInfo.longVersionCode,
            generatedAt = Instant.now()
        )
        context.contentResolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(raw).use { zip ->
                files.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        } ?: error("No se pudo abrir el destino de la topología")
    }

    internal fun buildFiles(
        transitions: List<CellTransitionSummary>,
        appVersion: String,
        appVersionCode: Long,
        generatedAt: Instant
    ): LinkedHashMap<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        val identities = transitions.flatMap { listOf(it.fromIdentity, it.toIdentity) }.distinct().sorted()

        files["cells.csv"] = buildString {
            appendLine("identity,incoming_routes,outgoing_routes,incoming_handovers,outgoing_handovers,trusted_incoming,trusted_outgoing")
            identities.forEach { identity ->
                val incoming = transitions.filter { it.toIdentity == identity }
                val outgoing = transitions.filter { it.fromIdentity == identity }
                appendLine(listOf(
                    identity, incoming.size, outgoing.size,
                    incoming.sumOf { it.observations }, outgoing.sumOf { it.observations },
                    incoming.sumOf { it.trustedObservations }, outgoing.sumOf { it.trustedObservations }
                ).joinToString(",") { csv(it) })
            }
        }.toByteArray()

        files["transitions.csv"] = buildString {
            appendLine("from_identity,to_identity,observations,trusted_observations,trust_ratio,last_status,last_seen_utc")
            transitions.sortedWith(compareBy<CellTransitionSummary> { it.fromIdentity }.thenBy { it.toIdentity })
                .forEach { route ->
                    appendLine(listOf(
                        route.fromIdentity, route.toIdentity, route.observations,
                        route.trustedObservations,
                        String.format(java.util.Locale.ROOT, "%.4f", route.trustRatio),
                        route.lastStatus.name, Instant.ofEpochMilli(route.lastSeenMs)
                    ).joinToString(",") { csv(it) })
                }
        }.toByteArray()

        val nodeIds = identities.withIndex().associate { (index, identity) -> identity to "n$index" }
        files["topology.graphml"] = buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">")
            appendLine("  <key id=\"identity\" for=\"node\" attr.name=\"identity\" attr.type=\"string\"/>")
            appendLine("  <key id=\"observations\" for=\"edge\" attr.name=\"observations\" attr.type=\"int\"/>")
            appendLine("  <key id=\"trusted\" for=\"edge\" attr.name=\"trusted_observations\" attr.type=\"int\"/>")
            appendLine("  <key id=\"status\" for=\"edge\" attr.name=\"last_status\" attr.type=\"string\"/>")
            appendLine("  <key id=\"last_seen\" for=\"edge\" attr.name=\"last_seen_utc\" attr.type=\"string\"/>")
            appendLine("  <graph id=\"ICdetectionTopology\" edgedefault=\"directed\">")
            identities.forEach { identity ->
                appendLine("    <node id=\"${nodeIds.getValue(identity)}\"><data key=\"identity\">${xml(identity)}</data></node>")
            }
            transitions.forEachIndexed { index, route ->
                appendLine("    <edge id=\"e$index\" source=\"${nodeIds.getValue(route.fromIdentity)}\" target=\"${nodeIds.getValue(route.toIdentity)}\">")
                appendLine("      <data key=\"observations\">${route.observations}</data>")
                appendLine("      <data key=\"trusted\">${route.trustedObservations}</data>")
                appendLine("      <data key=\"status\">${route.lastStatus.name}</data>")
                appendLine("      <data key=\"last_seen\">${Instant.ofEpochMilli(route.lastSeenMs)}</data>")
                appendLine("    </edge>")
            }
            appendLine("  </graph>")
            appendLine("</graphml>")
        }.toByteArray()

        files["metadata.json"] = JSONObject().apply {
            put("schemaVersion", 1)
            put("generatedAtUtc", generatedAt.toString())
            put("appVersion", appVersion)
            put("appVersionCode", appVersionCode)
            put("directedGraph", true)
            put("cellCount", identities.size)
            put("routeCount", transitions.size)
            put("handoverCount", transitions.sumOf { it.observations })
            put("privacyNote", "Cell identities and handover routes can reveal habitual movement patterns.")
            put("integrityNote", "SHA-256 detects later modification; it is not a legal chain-of-custody signature.")
        }.toString(2).toByteArray()

        files["SHA256SUMS.txt"] = files.entries.joinToString("\n", postfix = "\n") { (name, bytes) ->
            "${sha256(bytes)}  $name"
        }.toByteArray()
        return LinkedHashMap(files)
    }

    private fun csv(value: Any?): String = "\"${value?.toString().orEmpty().replace("\"", "\"\"")}\""
    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
