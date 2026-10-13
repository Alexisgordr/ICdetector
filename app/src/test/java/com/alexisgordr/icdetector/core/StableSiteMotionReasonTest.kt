package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — El motivo distingue "aún no lleva dos minutos" de "posiciones demasiado dispersas". */
class StableSiteMotionReasonTest {
    private val metresPerDegLat = 111_320.0
    private fun fix(t: Long, northM: Double = 0.0, speed: Float = 0f) =
        MotionFix(42.8 + northM / metresPerDegLat, -1.6, 1_000L + t, 8f, speed)

    @Test fun `a compact window shorter than two minutes is insufficient duration`() {
        val c = MotionClassifier()
        c.observe(fix(0)); val e = c.observe(fix(60_000, 3.0))
        assertEquals(MotionState.UNKNOWN, e.state)
        assertEquals(MotionReason.INSUFFICIENT_DURATION, e.reason)
    }

    @Test fun `a long window with scattered positions is dispersed, not short`() {
        // 30 m: fuera de la envolvente estática (25 m) pero por debajo del umbral de movimiento (40 m).
        val c = MotionClassifier()
        listOf(0L to 0.0, 30_000L to 30.0, 60_000L to 0.0, 90_000L to 30.0).forEach { (t, m) -> c.observe(fix(t, m)) }
        val e = c.observe(fix(120_000, 0.0))
        assertEquals(MotionState.UNKNOWN, e.state)
        assertEquals(MotionReason.POSITIONS_DISPERSED, e.reason)
        assertTrue(e.durationSeconds >= 120)
    }

    @Test fun `slow walking speed is reported as movement and stays unknown`() {
        val c = MotionClassifier()
        listOf(0L, 30_000L, 60_000L, 90_000L).forEach { c.observe(fix(it)) }
        val e = c.observe(fix(120_000, speed = 2.0f))
        assertEquals(MotionState.UNKNOWN, e.state)
        assertEquals(MotionReason.MOVEMENT, e.reason)
    }

    @Test fun `a compact two-minute window is still static`() {
        val c = MotionClassifier()
        listOf(0L, 30_000L, 60_000L, 90_000L).forEach { c.observe(fix(it, 2.0)) }
        assertEquals(MotionState.STATIC_CONFIRMED, c.observe(fix(120_000, 3.0)).state)
    }

    @Test fun `duration_s and the GSM identifier are documented as implemented`() {
        val manual = listOf(File("MANUAL.md"), File("../MANUAL.md")).first { it.exists() }.readText()
        assertTrue(manual.contains("`duration_s` is the length of the motion classifier's window at\nthe **last** saved evaluation"))
        assertTrue(manual.contains("not how long the phone was static that day"))
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val site = main.resolve("com/alexisgordr/icdetector/core/StableSite.kt").readText()
        assertTrue(site.contains("el BSIC (CellIdentityGsm.getBsic), pero esta app no lo lee"))
        assertTrue(!site.contains("GSM tiene ARFCN pero no\n    // identificador físico en la API"))
    }
}
