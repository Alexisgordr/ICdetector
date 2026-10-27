package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.CallbackWatchdog.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — El watchdog no reinicia callbacks mientras el módem conteste a los sondeos.
 * 3.1.7 (#49) — Ni con una causa conocida (ubicación apagada), ni con señales de vida; plazo creciente
 * entre reinicios y parada tras varios seguidos sin celdas.
 */
class CallbackWatchdogTest {
    private var now = 0L
    private val watchdog = CallbackWatchdog(timeoutMs = 30_000L, maxRetries = 4, clock = { now })

    /** Ciclo del servicio: pide refresco y, si el módem contesta con celdas, cuenta como entrega. */
    private fun pollCycle(answered: Boolean, locationOff: Boolean = false): Decision {
        val decision = watchdog.onRefreshRequested(cellsBlockedByKnownCause = locationOff)
        if (answered) watchdog.onDelivery()
        return decision
    }

    @Test fun `answered polls without spontaneous callbacks never restart`() {
        // Móvil quieto, sin cambios de pantalla: solo respuestas a requestCellInfoUpdate, cada 5 s
        // durante 10 minutos. Con la regla anterior (las respuestas no contaban) reiniciaba.
        repeat(120) {
            now += 5_000L
            assertEquals("ciclo $it", Decision.Wait, pollCycle(answered = true))
        }
    }

    @Test fun `a silent modem is restarted after the timeout and four requests, with the silence`() {
        now += 31_000L
        repeat(3) { assertEquals(Decision.Wait, watchdog.onRefreshRequested()) }
        assertEquals(Decision.Restart(31_000L), watchdog.onRefreshRequested())
        // Tras el reinicio el plazo vuelve a empezar.
        now += 1_000L
        assertEquals(Decision.Wait, watchdog.onRefreshRequested())
    }

    @Test fun `an empty answer is not a delivery`() {
        now += 31_000L
        repeat(3) { assertEquals(Decision.Wait, pollCycle(answered = false)) }
        assertTrue(pollCycle(answered = false) is Decision.Restart)
    }

    // --- 3.1.7 (#49) -------------------------------------------------------------------------

    @Test fun `with the location off, 100 refreshes in one hour never restart`() {
        var restarts = 0
        var announcements = 0
        repeat(100) {
            now += 36_000L // 100 peticiones repartidas en una hora
            when (val d = pollCycle(answered = false, locationOff = true)) {
                is Decision.Restart -> restarts++
                is Decision.KnownCause -> if (d.announce) announcements++
                else -> Unit
            }
        }
        assertEquals(0, restarts)
        assertEquals("el aviso se escribe una vez", 1, announcements)
    }

    @Test fun `the location coming back starts a fresh window instead of an immediate restart`() {
        now += 10 * 60_000L
        assertTrue(watchdog.onRefreshRequested(cellsBlockedByKnownCause = true) is Decision.KnownCause)
        now += 10 * 60_000L
        assertEquals(Decision.Wait, watchdog.onRefreshRequested(cellsBlockedByKnownCause = false))
        now += 20_000L
        repeat(4) { assertEquals(Decision.Wait, watchdog.onRefreshRequested()) }
        // Y si vuelve a apagarse, se avisa otra vez (nuevo episodio).
        assertEquals(Decision.KnownCause(announce = true), watchdog.onRefreshRequested(cellsBlockedByKnownCause = true))
    }

    /** Silencio total; un reinicio cada vez que toque. Devuelve los segundos entre reinicios. */
    private fun restartGaps(w: CallbackWatchdog, count: Int, stepMs: Long = 1_000L): List<Long> {
        val gaps = mutableListOf<Long>()
        var last = now
        while (gaps.size < count) {
            now += stepMs
            if (w.onRefreshRequested() is Decision.Restart) {
                gaps += (now - last) / 1_000L
                last = now
            }
        }
        return gaps
    }

    @Test fun `restarts back off 30, 60, 120, 240 s up to 30 min`() {
        val w = CallbackWatchdog(timeoutMs = 30_000L, maxRetries = 1, maxConsecutiveRestarts = 100, clock = { now })
        val gaps = restartGaps(w, 9)
        // Se reinicia en cuanto el silencio supera el plazo (1 s después, con un paso de 1 s).
        assertEquals(listOf(31L, 61L, 121L, 241L, 481L, 961L, 1_801L, 1_801L, 1_801L), gaps)
        assertEquals(CallbackWatchdog.MAX_TIMEOUT_MS, w.currentTimeoutMs())
    }

    @Test fun `a delivery with cells resets the backoff to 30 s`() {
        val w = CallbackWatchdog(timeoutMs = 30_000L, maxRetries = 1, maxConsecutiveRestarts = 100, clock = { now })
        restartGaps(w, 4)
        assertEquals(480_000L, w.currentTimeoutMs())
        w.onDelivery()
        assertEquals(30_000L, w.currentTimeoutMs())
        assertEquals(listOf(31L), restartGaps(w, 1))
    }

    @Test fun `after five restarts in a row without cells it stops until something changes`() {
        val w = CallbackWatchdog(timeoutMs = 30_000L, maxRetries = 1, clock = { now })
        var restarts = 0
        var pausedAnnouncements = 0
        repeat(24 * 360) { // un día, una petición cada 10 s
            now += 10_000L
            when (val d = w.onRefreshRequested()) {
                is Decision.Restart -> restarts++
                is Decision.Paused -> if (d.announce) pausedAnnouncements++
                else -> Unit
            }
        }
        assertEquals(CallbackWatchdog.MAX_CONSECUTIVE_RESTARTS, restarts)
        assertEquals(1, pausedAnnouncements)
        // Un cambio de estado (ubicación, permiso, red) lo reactiva, con el plazo de 30 s.
        w.onStateChanged()
        assertEquals(30_000L, w.currentTimeoutMs())
        assertEquals(listOf(40L), restartGaps(w, 1, stepMs = 10_000L))
    }

    @Test fun `cells also wake a stopped watchdog`() {
        val w = CallbackWatchdog(timeoutMs = 30_000L, maxRetries = 1, maxConsecutiveRestarts = 1, clock = { now })
        restartGaps(w, 1)
        now += 120_000L
        assertEquals(Decision.Paused(announce = true), w.onRefreshRequested())
        now += 120_000L
        assertEquals(Decision.Paused(announce = false), w.onRefreshRequested())
        w.onDelivery()
        now += 31_000L
        assertTrue(w.onRefreshRequested() is Decision.Restart)
    }

    @Test fun `ServiceState or DisplayInfo arriving without cells never restart`() {
        var restarts = 0
        repeat(720) { // una hora, una petición cada 5 s; un ServiceState o DisplayInfo cada 20 s
            now += 5_000L
            if (it % 4 == 0) watchdog.onSignOfLife()
            if (watchdog.onRefreshRequested() is Decision.Restart) restarts++
        }
        assertEquals(0, restarts)
    }

    @Test fun `a sign of life does not reset the growing timeout`() {
        val w = CallbackWatchdog(timeoutMs = 30_000L, maxRetries = 1, maxConsecutiveRestarts = 100, clock = { now })
        restartGaps(w, 2)
        w.onSignOfLife()
        assertEquals(120_000L, w.currentTimeoutMs())
    }

    @Test fun `the controller decides through the watchdog and restarts later, not inline`() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/service/TelephonyCollectionController.kt").readText()
        val onCellInfo = src.substringAfter("NullTolerantTelephonyCallbacks.CellInfoRequest(").substringBefore("{ _, _ ->")
        // 3.1.6 (#49) — La respuesta pasa por handleCellDelivery, que cuenta solo listas con celdas.
        assertTrue(onCellInfo.contains("handleCellDelivery(cellInfo, fresh = true, registered = false)"))
        val handler = src.substringAfter("private fun handleCellDelivery(").substringBefore("\n    }")
        assertTrue(handler.contains("if (com.alexisgordr.icdetector.core.CellDelivery.countsForWatchdog(raw)) watchdog.onDelivery()"))
        assertTrue("reloj monotónico", src.contains("CallbackWatchdog { android.os.SystemClock.elapsedRealtime() }"))
        // 3.1.7 — Causa conocida, aviso y reinicio publicado para después.
        val recover = src.substringAfter("private fun recoverCallbacksIfStale()").substringBefore("\n    }\n")
        assertTrue(recover.contains("watchdog.onRefreshRequested(cellsBlockedByKnownCause = locationOff || noPreciseLocation)"))
        assertTrue(recover.contains("log(TelephonyRecoveryLog.restarted(decision.silentMs))"))
        assertTrue(recover.contains("deferredRestart.request {"))
        assertFalse("nunca destroy() en línea", recover.contains("destroy()"))
        assertFalse("misma instancia de TelephonyManager", src.contains("manager = context.getSystemService"))
        assertTrue(src.contains("private val manager: TelephonyManager = context.getSystemService(TelephonyManager::class.java)"))
        assertTrue(src.contains("DeferredRestart { context.mainExecutor.execute(it) }"))
        // DisplayInfo y ServiceState son señales de vida, no entregas de celdas.
        val display = src.substringAfter("private fun registerDisplayCallback()").substringBefore("\n    }\n")
        assertTrue(display.contains("watchdog.onSignOfLife()"))
        assertFalse(display.contains("watchdog.onDelivery()"))
        val serviceState = src.substringAfter("private fun registerServiceStateCallback()").substringBefore("\n    }\n")
        assertTrue(serviceState.contains("watchdog.onSignOfLife()"))
        assertTrue(serviceState.contains("if (nowInService && inService == false) watchdog.onStateChanged()"))
        assertFalse(src.contains("lastRegisteredCallbackAt"))
    }
}
