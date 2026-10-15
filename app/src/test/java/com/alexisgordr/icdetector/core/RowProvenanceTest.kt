package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.RowProvenance.NotEvaluated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 3.0 — Procedencia de una fila: una columna ausente es «sin dato», nunca «evaluada». */
class RowProvenanceTest {
    @Test fun `not evaluated rules keep unknown, none and a list apart`() {
        assertEquals(NotEvaluated.Unknown, RowProvenance.notEvaluated(null))
        assertEquals(NotEvaluated.Unknown, RowProvenance.notEvaluated(""))
        assertEquals(NotEvaluated.None, RowProvenance.notEvaluated("NONE"))
        assertEquals(NotEvaluated.Rules(listOf("H1", "H6", "H9")), RowProvenance.notEvaluated("H1;H6;H9"))
        assertEquals(NotEvaluated.Rules(listOf("H13")), RowProvenance.notEvaluated(" H13 ; "))
    }

    @Test fun `accuracy is shown only when a valid value was stored`() {
        assertNull(RowProvenance.accuracyMeters(null))
        assertNull(RowProvenance.accuracyMeters(Float.NaN))
        assertNull(RowProvenance.accuracyMeters(-1f))
        assertEquals(0, RowProvenance.accuracyMeters(0f))
        assertEquals(13, RowProvenance.accuracyMeters(12.6f))
    }
}
