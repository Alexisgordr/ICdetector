package com.alexisgordr.icdetector.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.10.10 — Un segundo episodio en la misma celda vuelve a avisar; un parpadeo, no. */
class AlarmEpisodeGateTest {
    private var now = 0L
    private val gate = AlarmEpisodeGate(closeAfterMs = 60_000L, clock = { now })

    @Test fun `the first confirmed alarm starts an episode, repeats do not`() {
        assertTrue(gate.onConfirmedAlarm("214-07-1-100-LTE"))
        now += 5_000L
        assertFalse(gate.onConfirmedAlarm("214-07-1-100-LTE"))
    }

    @Test fun `a new episode in the same cell after a real recovery notifies again`() {
        assertTrue(gate.onConfirmedAlarm("A"))
        now += 1_000L; gate.onClear()
        now += 60_000L; gate.onClear()          // 60 s sin alarma: episodio cerrado
        now += 1_000L
        assertTrue(gate.onConfirmedAlarm("A"))
    }

    @Test fun `a short flicker is the same episode`() {
        assertTrue(gate.onConfirmedAlarm("A"))
        now += 1_000L; gate.onClear()
        now += 20_000L; gate.onClear()          // solo 20 s sin alarma
        assertFalse(gate.onConfirmedAlarm("A"))
        // El parpadeo reinicia la cuenta: hacen falta otros 60 s seguidos sin alarma.
        now += 1_000L; gate.onClear()
        now += 59_000L; gate.onClear()
        assertFalse(gate.onConfirmedAlarm("A"))
    }

    @Test fun `another identity is another episode`() {
        assertTrue(gate.onConfirmedAlarm("214-07-1-100-LTE"))
        assertTrue(gate.onConfirmedAlarm("214-01-9-100-LTE"))   // mismo CID, otra red
    }

    @Test fun `reset makes the next alarm a new episode`() {
        assertTrue(gate.onConfirmedAlarm("A"))
        gate.reset()
        assertTrue(gate.onConfirmedAlarm("A"))
    }

    // Revisión de v2.10.10: el vencimiento se mide también al volver la alarma, no solo en el
    // último ciclo limpio.
    @Test fun `an alarm returning after 60 s clear is a new episode even without a clean cycle at 60 s`() {
        now = 0L
        assertTrue(gate.onConfirmedAlarm("A"))
        now = 1_000L; gate.onClear()            // empieza la recuperación
        now = 59_000L; gate.onClear()           // último ciclo limpio: 58 s sin alarma
        now = 61_000L                            // vuelve la alarma: 60 s sin alarma
        assertTrue(gate.onConfirmedAlarm("A"))
    }

    @Test fun `an alarm returning before 60 s clear is still the same episode`() {
        now = 0L
        assertTrue(gate.onConfirmedAlarm("A"))
        now = 1_000L; gate.onClear()
        now = 59_000L; gate.onClear()
        now = 60_900L                            // 59,9 s sin alarma
        assertFalse(gate.onConfirmedAlarm("A"))
    }
}
