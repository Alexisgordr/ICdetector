package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.ForensicRetentionPolicy.Candidate
import org.junit.Assert.*
import org.junit.Test

/**
 * v2.8.0 — El tope forense se aplica borrando casos cerrados ENTEROS. Lo que se prueba aquí es la
 * decisión: cuántos casos caen, en qué orden y cuándo hay que parar. La garantía de no tocar una
 * captura en curso vive en la consulta SQL que construye la lista (solo READY / INTERRUPTED).
 */
class ForensicRetentionPolicyTest {

    @Test fun `under the cap nothing is deleted`() {
        val closed = listOf(Candidate(1, 100), Candidate(2, 100))
        assertTrue(ForensicRetentionPolicy.casesToDelete(200, 20_000, closed).isEmpty())
    }

    @Test fun `exactly at the cap nothing is deleted`() {
        val closed = listOf(Candidate(1, 500))
        assertTrue(ForensicRetentionPolicy.casesToDelete(1_000, 1_000, closed).isEmpty())
    }

    @Test fun `deletes the oldest case first`() {
        val closed = listOf(Candidate(1, 600), Candidate(2, 600), Candidate(3, 600))
        val doomed = ForensicRetentionPolicy.casesToDelete(1_800, 1_500, closed)
        assertEquals(listOf(1L), doomed.map { it.caseId })
    }

    @Test fun `deletes only as many cases as needed`() {
        val closed = listOf(Candidate(1, 100), Candidate(2, 100), Candidate(3, 100), Candidate(4, 100))
        val doomed = ForensicRetentionPolicy.casesToDelete(400, 150, closed)
        assertEquals(listOf(1L, 2L, 3L), doomed.map { it.caseId })
        assertEquals(100, ForensicRetentionPolicy.remainingAfter(400, doomed))
    }

    @Test fun `a whole case is never partially deleted`() {
        // 1 sola muestra de exceso: cae el caso entero, no una muestra suelta.
        val closed = listOf(Candidate(1, 180), Candidate(2, 180))
        val doomed = ForensicRetentionPolicy.casesToDelete(361, 360, closed)
        assertEquals(1, doomed.size)
        assertEquals(180, doomed.single().sampleCount)
        assertEquals(181, ForensicRetentionPolicy.remainingAfter(361, doomed))
    }

    @Test fun `with no closed cases nothing can be deleted`() {
        val doomed = ForensicRetentionPolicy.casesToDelete(25_000, 20_000, emptyList())
        assertTrue(doomed.isEmpty())
        assertEquals(25_000, ForensicRetentionPolicy.remainingAfter(25_000, doomed))
    }

    @Test fun `open cases alone above the cap leave the total over capacity`() {
        // Un solo caso cerrado pequeño no basta: el resto son capturas en curso y no se tocan.
        val closed = listOf(Candidate(1, 500))
        val doomed = ForensicRetentionPolicy.casesToDelete(25_000, 20_000, closed)
        assertEquals(listOf(1L), doomed.map { it.caseId })
        assertEquals(24_500, ForensicRetentionPolicy.remainingAfter(25_000, doomed))
        assertTrue(ForensicRetentionPolicy.remainingAfter(25_000, doomed) > 20_000)
    }

    @Test fun `an empty closed case does not stall the loop`() {
        // Un caso cerrado sin muestras no reduce el total; el bucle debe seguir con el siguiente.
        val closed = listOf(Candidate(1, 0), Candidate(2, 300))
        val doomed = ForensicRetentionPolicy.casesToDelete(400, 150, closed)
        assertEquals(listOf(1L, 2L), doomed.map { it.caseId })
        assertEquals(100, ForensicRetentionPolicy.remainingAfter(400, doomed))
    }

    @Test fun `the real cap deletes the whole oldest campaign day`() {
        // Escenario de campaña: 30 días de casos de ~700 muestras y el tope real de la app.
        val closed = (1L..30L).map { Candidate(it, 700) }
        val doomed = ForensicRetentionPolicy.casesToDelete(21_000, CAP, closed)
        assertEquals(listOf(1L, 2L), doomed.map { it.caseId })
        assertEquals(19_600, ForensicRetentionPolicy.remainingAfter(21_000, doomed))
        assertTrue(ForensicRetentionPolicy.remainingAfter(21_000, doomed) <= CAP)
    }

    private companion object {
        const val CAP = 20_000
    }
}
