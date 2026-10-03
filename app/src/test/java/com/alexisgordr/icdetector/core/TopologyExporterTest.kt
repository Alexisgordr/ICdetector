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

    // v2.10.6 — La exportación ya no tiene tope de rutas; los recuentos por celda deben seguir
    // siendo exactos con miles de rutas (antes se calculaban filtrando la lista por cada celda).
    @Test fun `per-cell counts stay exact with thousands of routes`() {
        val routes = (0 until 3_000).map { i ->
            CellTransitionSummary("C$i", "C${i + 1}", 2, 1, HeuristicStatus.PASSED, 1_700_000_000_000L + i)
        } + CellTransitionSummary("C0", "C2", 5, 0, HeuristicStatus.FAILED, 1_700_000_000_000L)
        val files = TopologyExporter.buildFiles(routes, "2.10.6", 34, Instant.EPOCH)

        val cells = files.getValue("cells.csv").decodeToString().lines().drop(1).filter { it.isNotBlank() }
        assertEquals(3_001, cells.size)
        val c2 = cells.single { it.startsWith("\"C2\",") }.split(",").map { it.trim('"') }
        // C2: entra desde C1 (2) y desde C0 (5); sale hacia C3 (2).
        assertEquals(listOf("C2", "2", "1", "7", "2", "1", "1"), c2)
        val meta = files.getValue("metadata.json").decodeToString()
        assertTrue(meta.contains("\"routeCount\": 3001"))
    }
}
