package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellTransitionSummary
import com.alexisgordr.icdetector.models.HeuristicStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkTopologyTest {
    @Test fun `trust ratio reflects only mature coherent observations`() {
        val route = CellTransitionSummary(
            fromIdentity = "214-01-1-100-LTE",
            toIdentity = "214-01-1-200-LTE",
            observations = 4,
            trustedObservations = 3,
            lastStatus = HeuristicStatus.PASSED,
            lastSeenMs = 1L
        )
        assertEquals(0.75f, route.trustRatio, 0.0001f)
    }

    @Test fun `empty route has zero trust without division errors`() {
        val route = CellTransitionSummary("A", "B", 0, 0, HeuristicStatus.NOT_EVALUATED, 0L)
        assertEquals(0f, route.trustRatio, 0f)
    }
}
