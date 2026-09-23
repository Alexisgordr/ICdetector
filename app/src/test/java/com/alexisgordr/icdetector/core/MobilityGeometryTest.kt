package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.forensics.GeometryExporter
import com.alexisgordr.icdetector.models.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

class MobilityGeometryTest {
    @Test fun geometryReflectsMobilityStateIndependentlyFromLocalTrust() {
        val edges=listOf(
            edge("A","B",10,0), edge("B","C",10,1), edge("C","D",10,3), edge("D","B",10,3)
        )
        val snapshot=MobilityGeometryProjection.build(edges,emptyList(),emptySet())
        assertEquals(MobilityFamiliarity.UNKNOWN_ON_ROUTE,snapshot.cells.single{it.identity=="A"}.familiarity)
        assertEquals(MobilityFamiliarity.OBSERVED_ON_ROUTE,snapshot.cells.single{it.identity=="C"}.familiarity)
        val known=snapshot.cells.single{it.identity=="D"}
        assertEquals(MobilityFamiliarity.KNOWN_ON_ROUTE,known.familiarity)
        assertNull(known.localTrustState) // KNOWN_ON_ROUTE never manufactures ESTABLISHED.
    }

    @Test fun geometryExportKeepsTransitionsAndIndependentTripsDistinct() {
        val transitions=listOf(edge("A","B",28,4),edge("B","C",3,2))
        val snapshot=MobilityGeometryProjection.build(transitions,emptyList(),emptySet())
        val files=GeometryExporter.buildFiles(snapshot,"test",1,Instant.EPOCH)
        assertEquals(setOf("geometry_cells.csv","geometry_edges.csv","mobility_trips.csv","geometry.graphml","metadata.json"),files.keys)
        val csv=files.getValue("geometry_edges.csv").decodeToString()
        assertTrue(csv.contains("\"28\",\"0\",\"4\""));assertTrue(csv.contains("\"3\",\"0\",\"2\""))
        val graph=files.getValue("geometry.graphml")
        val doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(graph))
        assertEquals("graphml",doc.documentElement.localName ?: doc.documentElement.nodeName)
        assertTrue(graph.decodeToString().contains("<data key=\"transition_count\">28</data>"))
        assertTrue(graph.decodeToString().contains("<data key=\"trip_count\">4</data>"));assertTrue(graph.decodeToString().contains("<data key=\"trip_count\">2</data>"))
        val metadata=JSONObject(files.getValue("metadata.json").decodeToString())
        assertEquals("CONTEXT_ONLY",metadata.getString("featureMode"));assertFalse(metadata.getBoolean("coordinatesIncluded"))
        files.values.forEach { assertFalse(it.decodeToString().contains("latitude",true));assertFalse(it.decodeToString().contains("longitude",true)) }
    }

    @Test fun openTripProjectionNeverCountsFutureContributionAsHistory() {
        val e=edge("A","B",27,2)
        val trip=MobilityTripSummary("open",0,null,"OPEN",null,true,3,1,"B")
        val snapshot=MobilityGeometryProjection.build(listOf(e),listOf(trip),setOf(MobilityEdge("A","B")))
        assertEquals(2,snapshot.transitions.single().tripCount)
        assertNotEquals(MobilityFamiliarity.KNOWN_ON_ROUTE,snapshot.cells.single{it.identity=="B"}.familiarity)
        val exported=GeometryExporter.buildFiles(snapshot,"test",1,Instant.EPOCH).getValue("geometry_edges.csv").decodeToString()
        assertTrue(exported.contains("\"2\",\"true\",\"3\""))
    }

    private fun edge(a:String,b:String,transitions:Int,trips:Int)=CellTransitionSummary(
        a,b,transitions,0,HeuristicStatus.PASSED,0,tripCount=trips
    )
}
