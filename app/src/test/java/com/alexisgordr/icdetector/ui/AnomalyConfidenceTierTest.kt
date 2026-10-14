package com.alexisgordr.icdetector.ui

import com.alexisgordr.icdetector.core.BayesianScorer
import org.junit.Assert.assertEquals
import org.junit.Test

/** 3.0 (corte de campaña): los umbrales de color del panel siguen al prior de 0,005 (30/10, antes 50/20). */
class AnomalyConfidenceTierTest {

    @Test
    fun `thresholds are 30 and 10`() {
        assertEquals(0, anomalyConfidenceTier(9.99f))
        assertEquals(1, anomalyConfidenceTier(10f))
        assertEquals(1, anomalyConfidenceTier(29.99f))
        assertEquals(2, anomalyConfidenceTier(30f))
        assertEquals(2, anomalyConfidenceTier(95f))
    }

    @Test
    fun `no evidence and one strong signal stay grey, combined evidence climbs`() {
        assertEquals(0, anomalyConfidenceTier(BayesianScorer.calculate(emptyList(), "PENDING", false)))
        // MCC (LR 9) sola: ~4,3 % -> gris.
        assertEquals(0, anomalyConfidenceTier(BayesianScorer.calculate(listOf("mccMismatch"), "PENDING", false)))
        // Dos señales fuertes de grupos distintos (MCC 9, H11 6): ~21 % -> ámbar.
        assertEquals(1, anomalyConfidenceTier(BayesianScorer.calculate(listOf("mccMismatch", "h11"), "PENDING", false)))
        // Tres (y TAC 6,5): ~64 % -> rojo.
        assertEquals(2, anomalyConfidenceTier(BayesianScorer.calculate(listOf("mccMismatch", "h11", "tacDev"), "PENDING", false)))
    }
}
