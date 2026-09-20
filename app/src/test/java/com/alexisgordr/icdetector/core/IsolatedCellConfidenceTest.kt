package com.alexisgordr.icdetector.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsolatedCellConfidenceTest {
    @Test fun `requires three consecutive fresh observations`() {
        val confidence = IsolatedCellConfidence(3)

        assertFalse(confidence.observe("cell-a", true, 10_000L))
        assertFalse(confidence.observe("cell-a", true, 20_000L))
        assertTrue(confidence.observe("cell-a", true, 30_000L))
    }

    @Test fun `duplicate token cannot advance the streak`() {
        val confidence = IsolatedCellConfidence(3)

        assertFalse(confidence.observe("cell-a", true, 10_000L))
        assertFalse(confidence.observe("cell-a", true, 10_000L))
        assertFalse(confidence.observe("cell-a", true, 20_000L))
        assertTrue(confidence.observe("cell-a", true, 30_000L))
    }

    @Test fun `neighbour snapshot clears the streak immediately`() {
        val confidence = IsolatedCellConfidence(3)
        confidence.observe("cell-a", true, 10_000L)
        confidence.observe("cell-a", true, 20_000L)

        assertFalse(confidence.observe("cell-a", false, 30_000L))
        assertFalse(confidence.observe("cell-a", true, 40_000L))
    }

    @Test fun `handover cannot inherit isolation evidence`() {
        val confidence = IsolatedCellConfidence(3)
        confidence.observe("cell-a", true, 10_000L)
        confidence.observe("cell-a", true, 20_000L)

        assertFalse(confidence.observe("cell-b", true, 30_000L))
        assertFalse(confidence.observe("cell-b", true, 40_000L))
        assertTrue(confidence.observe("cell-b", true, 50_000L))
    }
}
