package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Test

/** 3.1.0 (H17) — Churn de celdas en 15 min: identidades distintas, cambios y movimiento. */
class CellChurnTest {
    private val minute = 60_000L
    private fun id(cid: String, tac: String = "100") = ServingIdentityChange.Identity("214", "07", tac, cid, RadioTech.LTE)
    private fun CellChurn.at(nowMs: Long, speed: Float? = 0f, fixAge: Long? = 5_000L) = snapshot(nowMs, speed, fixAge)

    @Test fun `empty - nothing observed`() {
        assertEquals(ChurnSnapshot(0, 0, ChurnMotion.UNKNOWN), CellChurn().snapshot(0L, null, null))
    }

    @Test fun `stable - one cell for an hour is one identity and no change`() {
        val churn = CellChurn()
        (0..60).forEach { churn.observe(id("A"), it * minute) }
        assertEquals(ChurnSnapshot(1, 0, ChurnMotion.STILL), churn.at(60 * minute))
    }

    @Test fun `A-B-A counts two identities and two changes`() {
        val churn = CellChurn()
        churn.observe(id("A"), 0); churn.observe(id("B"), minute); churn.observe(id("A"), 2 * minute)
        assertEquals(ChurnSnapshot(2, 2, ChurnMotion.STILL), churn.at(2 * minute))
    }

    @Test fun `a 6-cell trip while moving`() {
        val churn = CellChurn()
        listOf("A", "B", "C", "D", "E", "F").forEachIndexed { i, cid -> churn.observe(id(cid, tac = "${100 + i}"), i * 2 * minute) }
        assertEquals(ChurnSnapshot(6, 5, ChurnMotion.MOVING), churn.at(10 * minute, speed = 20f))
        // 15 min después del último cambio solo queda la servidora actual.
        assertEquals(ChurnSnapshot(1, 0, ChurnMotion.MOVING), churn.at(10 * minute + CellChurn.WINDOW_MS + 1, speed = 20f))
    }

    @Test fun `the serving cell at the start of the window counts, changes before it do not`() {
        val churn = CellChurn()
        churn.observe(id("A"), 0); churn.observe(id("B"), minute); churn.observe(id("C"), 10 * minute)
        // Ventana [6, 21] min: B servía al empezar y C llegó dentro.
        assertEquals(ChurnSnapshot(2, 1, ChurnMotion.STILL), churn.at(21 * minute))
    }

    @Test fun `reset by a gap or a service loss starts from scratch`() {
        val churn = CellChurn()
        churn.observe(id("A"), 0); churn.observe(id("B"), minute); churn.observe(id("C"), 2 * minute)
        churn.reset()
        churn.observe(id("C"), 5 * minute)
        assertEquals("la primera tras el reset no es un cambio", ChurnSnapshot(1, 0, ChurnMotion.STILL), churn.at(5 * minute))
        churn.observe(id("D"), 6 * minute)
        assertEquals(ChurnSnapshot(2, 1, ChurnMotion.STILL), churn.at(6 * minute))
    }

    @Test fun `unknown fields and an unreadable Cell ID are not changes`() {
        val churn = CellChurn()
        churn.observe(id("A"), 0)
        churn.observe(id("N/A"), minute)
        churn.observe(ServingIdentityChange.Identity("N/A", "07", "100", "A", RadioTech.UNKNOWN), 2 * minute)
        churn.observe(id("A"), 3 * minute)
        assertEquals(ChurnSnapshot(1, 0, ChurnMotion.STILL), churn.at(3 * minute))
    }

    @Test fun `an old fix, a fix of unknown age or no speed give UNKNOWN motion`() {
        assertEquals(ChurnMotion.UNKNOWN, CellChurn.motionOf(30f, 61_000L))
        assertEquals(ChurnMotion.UNKNOWN, CellChurn.motionOf(30f, null))
        assertEquals(ChurnMotion.UNKNOWN, CellChurn.motionOf(null, 1_000L))
        assertEquals(ChurnMotion.UNKNOWN, CellChurn.motionOf(Float.NaN, 1_000L))
        assertEquals(ChurnMotion.MOVING, CellChurn.motionOf(2.5f, 60_000L))
        assertEquals(ChurnMotion.STILL, CellChurn.motionOf(2.4f, 0L))
    }
}
