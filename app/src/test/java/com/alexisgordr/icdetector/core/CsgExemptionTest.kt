package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellReputation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0 (corte de campaña) — La exención CSG exige historial local previo de la identidad. */
class CsgExemptionTest {
    private fun reputation(observations: Int, days: Int, cleanRatio: Double = 0.0) =
        CellReputation(observations, days, cleanRatio, trustScore = 0)

    @Test fun `a CSG cell with 10 rows on 2 days is exempt`() {
        assertTrue(CsgExemption.applies(true, reputation(10, 2)))
        assertTrue(CsgExemption.applies(true, reputation(40, 5)))
    }

    @Test fun `without enough local history there is no exemption`() {
        assertFalse("sin reputación", CsgExemption.applies(true, null))
        assertFalse("9 filas", CsgExemption.applies(true, reputation(9, 2)))
        assertFalse("un solo día", CsgExemption.applies(true, reputation(100, 1)))
    }

    @Test fun `a cell that does not declare CSG is never exempt`() {
        assertFalse(CsgExemption.applies(false, reputation(40, 5)))
    }

    @Test fun `history counts whether its rows were clean or not`() {
        assertTrue(CsgExemption.applies(true, reputation(10, 2, cleanRatio = 0.0)))
        assertTrue(CsgExemption.applies(true, reputation(10, 2, cleanRatio = 1.0)))
    }
}
