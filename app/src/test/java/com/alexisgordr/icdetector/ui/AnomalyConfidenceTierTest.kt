package com.alexisgordr.icdetector.ui

import com.alexisgordr.icdetector.core.BayesianScorer
import org.junit.Assert.assertEquals
import org.junit.Test

/** 3.0.0-beta5: los umbrales de color del panel siguen al prior de 0,005 (30/10, antes 50/20). */
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
    fun `no evidence stays grey and one strong signal alone is amber, not red`() {
        assertEquals(0, anomalyConfidenceTier(BayesianScorer.calculate(emptyList(), "PENDING", false)))
        // MCC (LR 9) sola: ~4,3 % -> gris; cifrado nulo (LR 12) solo: ~5,7 % -> gris.
        assertEquals(0, anomalyConfidenceTier(BayesianScorer.calculate(listOf("ciphering"), "PENDING", false)))
        // Dos señales fuertes independientes: ~41 % -> rojo.
        assertEquals(2, anomalyConfidenceTier(BayesianScorer.calculate(listOf("ciphering", "mccMismatch"), "PENDING", false)))
    }
}
