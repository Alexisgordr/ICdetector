package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — El watchdog no reinicia callbacks mientras el módem conteste a los sondeos. */
class CallbackWatchdogTest {
    private var now = 0L
    private val watchdog = CallbackWatchdog(timeoutMs = 30_000L, maxRetries = 4, clock = { now })

    /** Ciclo del servicio: pide refresco y, si el módem contesta con celdas, cuenta como entrega. */
    private fun pollCycle(answered: Boolean): Long? {
        val restart = watchdog.onRefreshRequested()
        if (answered) watchdog.onDelivery()
        return restart
    }

    @Test fun `answered polls without spontaneous callbacks never restart`() {
        // Móvil quieto, sin cambios de pantalla: solo respuestas a requestCellInfoUpdate, cada 5 s
        // durante 10 minutos. Con la regla anterior (las respuestas no contaban) reiniciaba.
        repeat(120) {
            now += 5_000L
            assertNull("ciclo $it", pollCycle(answered = true))
        }
    }

    @Test fun `the old rule restarted in the same situation`() {
        // Mismo patrón sin contar las respuestas, como antes de este cambio: a los 30 s, tras 4 peticiones.
        var restarts = 0
        repeat(120) {
            now += 5_000L
            if (pollCycle(answered = false) != null) restarts++
        }
        assertTrue("antes reiniciaba cada pocas peticiones: $restarts", restarts > 10)
    }

    @Test fun `a silent modem is restarted after the timeout and four requests, with the silence`() {
        now += 31_000L
        repeat(3) { assertNull(watchdog.onRefreshRequested()) }
        assertEquals(31_000L, watchdog.onRefreshRequested())
        // Tras el reinicio el plazo vuelve a empezar.
        now += 1_000L
        assertNull(watchdog.onRefreshRequested())
    }

    @Test fun `an empty answer is not a delivery`() {
        now += 31_000L
        repeat(3) { assertNull(pollCycle(answered = false)) }
        assertFalse(pollCycle(answered = false) == null)
    }

    @Test fun `the controller counts poll answers and logs every restart`() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/service/TelephonyCollectionController.kt").readText()
        val onCellInfo = src.substringAfter("override fun onCellInfo(").substringBefore("override fun onError")
        assertTrue(onCellInfo.contains("if (cellInfo.isNotEmpty()) watchdog.onDelivery()"))
        assertTrue(src.contains("log(\"Watchdog: callbacks de telefonía reiniciados;"))
        assertTrue("reloj monotónico", src.contains("CallbackWatchdog { android.os.SystemClock.elapsedRealtime() }"))
        assertFalse(src.contains("lastRegisteredCallbackAt"))
    }
}
