package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.CoverageContinuity.Break
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (#20) — Bug found during testing: un hueco largo sin lecturas, sin aviso de pérdida, no
 * reiniciaba la racha de H1 ni el contexto de bandas de H14. Ahora la continuidad también se
 * rompe por tiempo (el mismo límite de 120 s que TemporalConfidence).
 */
class CoverageContinuityTest {
    private val cell = "214-07-1-100-LTE"

    /** Lo que hace el servicio en cada ciclo: comprobar, reiniciar si hace falta, publicar. */
    private fun cycle(continuity: CoverageContinuity, isolation: IsolatedCellConfidence,
                      atMs: Long, token: Long, losses: Long = 0L): Break {
        val brk = continuity.check(losses, atMs)
        if (brk != Break.NONE) isolation.reset()
        isolation.observe(cell, candidate = true, observationToken = token)
        continuity.acknowledge(losses, atMs)
        return brk
    }

    @Test fun `isolation samples a few seconds apart keep counting`() {
        val continuity = CoverageContinuity(); val isolation = IsolatedCellConfidence()
        assertEquals(Break.NONE, cycle(continuity, isolation, 0L, 1L))
        assertEquals(Break.NONE, cycle(continuity, isolation, 10_000L, 2L))
        assertEquals(Break.NONE, cycle(continuity, isolation, 20_000L, 3L))
        assertEquals(3, isolation.progress)
    }

    @Test fun `two isolation samples, minutes without deliveries, same cell starts again`() {
        val continuity = CoverageContinuity(); val isolation = IsolatedCellConfidence()
        cycle(continuity, isolation, 0L, 1L)
        cycle(continuity, isolation, 10_000L, 2L)
        assertEquals(2, isolation.progress)
        // Cinco minutos sin entregas y sin lista vacía; vuelve la misma celda.
        assertEquals(Break.GAP, cycle(continuity, isolation, 10_000L + 5 * 60_000L, 3L))
        assertEquals("la primera muestra tras el hueco empieza de cero", 1, isolation.progress)
    }

    @Test fun `exactly the limit is still continuous, one millisecond more is a gap`() {
        val continuity = CoverageContinuity()
        continuity.acknowledge(0L, 0L)
        assertEquals(Break.NONE, continuity.check(0L, CoverageContinuity.MAX_GAP_MS))
        assertEquals(Break.GAP, continuity.check(0L, CoverageContinuity.MAX_GAP_MS + 1))
    }

    @Test fun `a signal loss still breaks continuity and is reported as such`() {
        val continuity = CoverageContinuity()
        continuity.acknowledge(0L, 0L)
        assertEquals(Break.SIGNAL_LOSS, continuity.check(1L, 5_000L))
        // Pérdida y hueco a la vez: se informa la pérdida.
        assertEquals(Break.SIGNAL_LOSS, continuity.check(1L, 10 * 60_000L))
    }

    @Test fun `a break is reported again until a cycle is published`() {
        val continuity = CoverageContinuity()
        continuity.acknowledge(0L, 0L)
        assertEquals(Break.GAP, continuity.check(0L, 300_000L))   // ese ciclo no se publica
        assertEquals(Break.GAP, continuity.check(0L, 303_000L))   // el siguiente lo vuelve a ver
        continuity.acknowledge(0L, 303_000L)
        assertEquals(Break.NONE, continuity.check(0L, 306_000L))
    }

    @Test fun `TemporalConfidence and CoverageContinuity break at the same gap`() {
        val suspicious = com.alexisgordr.icdetector.models.CellData(
            isRegistered = true, networkType = "4G LTE", cellId = "100", mnc = "07", tac = "1", dbm = -70,
            mcc = "214", radioTech = com.alexisgordr.icdetector.models.RadioTech.LTE, isSuspicious = true,
            suspiciousReason = "Desviación TAC"
        )
        fun streakAfterGap(gapMs: Long): Int {
            var now = 0L
            val t = TemporalConfidence(confirmationCycles = 3, elapsedRealtimeMs = { now })
            t.apply(suspicious, 10_000L)
            now += gapMs
            return t.apply(suspicious, 10_000L + gapMs).temporalProgress.phase
        }
        // Justo en el límite la racha sigue; un milisegundo más y empieza de nuevo, igual que H1.
        assertEquals(2, streakAfterGap(CoverageContinuity.MAX_GAP_MS))
        assertEquals(1, streakAfterGap(CoverageContinuity.MAX_GAP_MS + 1))
        // Y no es el límite de 30 s del timestamp congelado: 31 s no rompen la racha.
        assertEquals(2, streakAfterGap(31_000L))
    }

    /**
     * Las dos puertas de tiempo son distintas a propósito y su RELACIÓN es lo que importa, no los
     * números: con lecturas cada 10 s (pantalla apagada), 30 s son 3 lecturas perdidas (módem
     * atascado: dejar pasar una muestra real) y 120 s son 12 (hueco de cobertura: romper la
     * continuidad). Si cambia el intervalo de lectura, este test obliga a revisar las dos.
     */
    @Test fun `the continuity gap stays wider than the stalled-modem gate, both sized in scan intervals`() {
        val stall = TemporalConfidence.MAX_OBSERVATION_STALL_MS
        val gap = CoverageContinuity.MAX_GAP_MS
        val service = listOf(
            File("src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt")
        ).first { it.exists() }.readText()
        val screenOffScan = Regex("SCREEN_OFF_SCAN_INTERVAL_MS = ([0-9_]+)L").find(service)!!
            .groupValues[1].replace("_", "").toLong()
        assertTrue("las dos puertas no pueden colapsar", gap > stall)
        assertTrue("la puerta de módem atascado tolera al menos 2 lecturas perdidas", stall >= 2 * screenOffScan)
        assertTrue("un hueco de cobertura son al menos 10 lecturas perdidas", gap >= 10 * screenOffScan)
        assertTrue("el hueco es al menos 3 veces la puerta de módem atascado", gap >= 3 * stall)
    }

    @Test fun `the first observation ever is not a gap`() {
        assertEquals(Break.NONE, CoverageContinuity().check(0L, 999_999_999L))
    }

    @Test fun `the service resets H1 and the band context on a gap and uses a clock that counts sleep`() {
        val service = listOf(
            File("src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt")
        ).first { it.exists() }.readText()
        assertTrue(service.contains("val deliveredAtElapsedMs = SystemClock.elapsedRealtime()"))
        assertTrue(service.contains("coverageContinuity.check(signalLosses, deliveredAtElapsedMs)"))
        assertTrue(service.contains("coverageContinuity.acknowledge(signalLosses, deliveredAtElapsedMs)"))
        val broken = service.substringAfter("if (continuityBroken) {").substringBefore("}")
        listOf("isolatedCellConfidence.reset()", "prevBand = null", "prevBandSite = null", "prevNrArfcn = null")
            .forEach { assertTrue(it, broken.contains(it)) }
        // TemporalConfidence mide su hueco con el mismo reloj: nanoTime se para en reposo profundo.
        assertTrue(service.contains("elapsedRealtimeMs = SystemClock::elapsedRealtime"))
        assertFalse(service.contains("acknowledgedSignalLosses"))
    }
}
