package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.forensics.TopologyExporter
import com.alexisgordr.icdetector.models.CellTransitionSummary
import com.alexisgordr.icdetector.models.HeuristicStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TopologyExporterTest {
    @Test fun `export contains interoperable graph tables metadata and hashes`() {
        val route = CellTransitionSummary(
            "214-01-1-100-LTE", "214-01-1-200-LTE", 3, 2,
            HeuristicStatus.PASSED, 1_700_000_000_000L
        )
        val files = TopologyExporter.buildFiles(listOf(route), "2.3.0", 9, Instant.EPOCH)

        assertEquals(
            listOf("cells.csv", "transitions.csv", "topology.graphml", "metadata.json", "SHA256SUMS.txt"),
            files.keys.toList()
        )
        val graph = files.getValue("topology.graphml").decodeToString()
        assertTrue(graph.contains("edgedefault=\"directed\""))
        assertTrue(graph.contains("214-01-1-100-LTE"))
        val sums = files.getValue("SHA256SUMS.txt").decodeToString()
        assertEquals(4, sums.lineSequence().count { it.isNotBlank() })
        assertTrue(sums.contains("topology.graphml"))
    }
}
