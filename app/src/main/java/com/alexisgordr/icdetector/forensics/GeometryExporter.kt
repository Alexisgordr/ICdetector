package com.alexisgordr.icdetector.forensics

import android.content.Context
import android.net.Uri
import com.alexisgordr.icdetector.core.MobilityEdge
import com.alexisgordr.icdetector.core.MobilityFamiliarityConfig
import com.alexisgordr.icdetector.models.MobilityGeometrySnapshot
import com.alexisgordr.icdetector.storage.CellDbHelper
import org.json.JSONObject
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object GeometryExporter {
    fun export(context: Context, db: CellDbHelper, uri: Uri) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val files = buildFiles(db.getMobilityGeometrySnapshot(), info.versionName ?: "unknown", info.longVersionCode, Instant.now())
        context.contentResolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(raw).use { zip -> files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            } }
        } ?: error("No se pudo abrir el destino de Geometry")
    }

    internal fun buildFiles(
        snapshot: MobilityGeometrySnapshot,
        appVersion: String,
        appVersionCode: Long,
        generatedAt: Instant,
        config: MobilityFamiliarityConfig = MobilityFamiliarityConfig()
    ): LinkedHashMap<String, ByteArray> {
        val open = snapshot.openTrip
        val validOpen = open?.hadMoving == true && open.distinctCells >= config.minimumDistinctCells
        val files = linkedMapOf<String, ByteArray>()
        files["geometry_cells.csv"] = buildString {
            appendLine("identity,radio_tech,local_trust_state,mobility_familiarity,mobility_good_edges,mobility_relevant_trip_count,incoming_routes,outgoing_routes,incoming_transitions,outgoing_transitions,trusted_incoming,trusted_outgoing,first_mobility_seen_utc,last_mobility_seen_utc")
            snapshot.cells.forEach { c -> appendLine(listOf(
                c.identity,c.radioTech,c.localTrustState,c.familiarity.name,c.goodEdges,c.relevantTripCount,
                c.incomingRoutes,c.outgoingRoutes,c.incomingTransitions,c.outgoingTransitions,
                c.trustedIncoming,c.trustedOutgoing,c.firstMobilitySeenMs?.let{Instant.ofEpochMilli(it)},
                c.lastMobilitySeenMs?.let{Instant.ofEpochMilli(it)}
            ).joinToString(","){csv(it)}) }
        }.toByteArray()
        files["geometry_edges.csv"] = buildString {
            appendLine("from_identity,to_identity,transition_count,trusted_transition_count,trip_count,pending_current_trip,projected_trip_count_after_valid_close,mobility_first_seen_utc,mobility_last_seen_utc,last_trip_id")
            snapshot.transitions.forEach { e ->
                val pending = MobilityEdge(e.fromIdentity,e.toIdentity) in snapshot.pendingEdges
                appendLine(listOf(e.fromIdentity,e.toIdentity,e.observations,e.trustedObservations,e.tripCount,pending,
                    if(pending&&validOpen)e.tripCount+1 else e.tripCount,
                    e.mobilityFirstSeenMs?.let{Instant.ofEpochMilli(it)},e.mobilityLastSeenMs?.let{Instant.ofEpochMilli(it)},e.lastTripId
                ).joinToString(","){csv(it)})
            }
        }.toByteArray()
        files["mobility_trips.csv"] = buildString {
            appendLine("trip_id,started_at_utc,ended_at_utc,state,close_reason,had_moving,distinct_cells,edge_count")
            snapshot.trips.forEach { t -> appendLine(listOf(t.tripId,Instant.ofEpochMilli(t.startedAtMs),
                t.endedAtMs?.let{Instant.ofEpochMilli(it)},t.state,t.closeReason,t.hadMoving,t.distinctCells,t.edgeCount
            ).joinToString(","){csv(it)}) }
        }.toByteArray()
        val nodeIds=snapshot.cells.withIndex().associate{(i,c)->c.identity to "n$i"}
        files["geometry.graphml"] = buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">")
            listOf("identity","radio_tech","local_trust_state","mobility_familiarity","mobility_good_edges","mobility_relevant_trip_count").forEach { key ->
                appendLine("  <key id=\"$key\" for=\"node\" attr.name=\"$key\" attr.type=\"string\"/>")
            }
            listOf("transition_count","trusted_transition_count","trip_count").forEach { key ->
                appendLine("  <key id=\"$key\" for=\"edge\" attr.name=\"$key\" attr.type=\"int\"/>")
            }
            appendLine("  <graph id=\"ICdetectionGeometry\" edgedefault=\"directed\">")
            snapshot.cells.forEach { c -> appendLine("    <node id=\"${nodeIds.getValue(c.identity)}\"><data key=\"identity\">${xml(c.identity)}</data><data key=\"radio_tech\">${xml(c.radioTech)}</data><data key=\"local_trust_state\">${xml(c.localTrustState ?: "UNAVAILABLE")}</data><data key=\"mobility_familiarity\">${c.familiarity.name}</data><data key=\"mobility_good_edges\">${c.goodEdges}</data><data key=\"mobility_relevant_trip_count\">${c.relevantTripCount}</data></node>") }
            snapshot.transitions.forEachIndexed { i,e -> appendLine("    <edge id=\"e$i\" source=\"${nodeIds.getValue(e.fromIdentity)}\" target=\"${nodeIds.getValue(e.toIdentity)}\"><data key=\"transition_count\">${e.observations}</data><data key=\"trusted_transition_count\">${e.trustedObservations}</data><data key=\"trip_count\">${e.tripCount}</data></edge>") }
            appendLine("  </graph>");appendLine("</graphml>")
        }.toByteArray()
        files["metadata.json"] = JSONObject().apply {
            put("schemaVersion",17);put("appVersion",appVersion);put("appVersionCode",appVersionCode)
            put("exportTimeUtc",generatedAt.toString());put("feature","MOBILITY_FAMILIARITY");put("featureMode","CONTEXT_ONLY")
            put("K_PRIOR_TRIPS",config.priorTripsRequired);put("R_GOOD_EDGES",config.goodEdgesRequired);put("MIN_DISTINCT_CELLS",config.minimumDistinctCells)
            put("STATIC_CLOSE_DURATION_MS",config.staticCloseDurationMs);put("SAME_SERVING_CLOSE_DURATION_MS",config.sameServingCloseDurationMs);put("MAX_TRIP_DURATION_MS",config.maxTripDurationMs)
            put("semanticNote","Mobility Familiarity is contextual route memory. KNOWN_ON_ROUTE does not mean trusted, verified or legitimate.")
            put("privacyNote","This export can reveal cell identities and patterns associated with frequently visited places or routes. Review it before sharing publicly.")
            put("coordinatesIncluded",false);put("localTrustStateAvailability","Unavailable in schema 17; left empty rather than inferred.")
            put("openTripContributionIncludedInFamiliarity",false)
        }.toString(2).toByteArray()
        return LinkedHashMap(files)
    }
    private fun csv(value:Any?)="\"${value?.toString().orEmpty().replace("\"","\"\"")}\""
    private fun xml(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
}
