package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.1.7 (#49) — Registrar callbacks nunca lanza, el reinicio nunca corre dentro de la entrega y,
 * con la ubicación apagada, los listeners no se acumulan hasta el tope del sistema.
 */
class TelephonyRecoveryTest {

    /** TelephonyManager falso: cuenta los listeners vivos y lanza al pasar el tope, como Android. */
    private class FakeTelephony(private val limit: Int = 50) {
        val live = mutableListOf<String>()
        var registrations = 0
        var failNext: Exception? = null

        fun register(cb: String) {
            failNext?.let { failNext = null; throw it }
            if (live.size >= limit) {
                throw IllegalStateException("Pid 1234 has exceeded the number of permissible registered listeners. Ignoring request to add.")
            }
            registrations++
            live += cb
        }

        fun unregister(cb: String) {
            live.remove(cb)
        }
    }

    // --- CallbackRegistrar -------------------------------------------------------------------

    @Test fun `a register that throws IllegalStateException does not propagate and leaves one line`() {
        val telephony = FakeTelephony(limit = 0)
        val logs = mutableListOf<String>()
        val registrar = CallbackRegistrar<String>(telephony::register, telephony::unregister, logs::add)
        assertFalse(registrar.add("DisplayInfo", "display-1"))
        assertFalse(registrar.add("DisplayInfo", "display-2"))
        assertFalse(registrar.add("DisplayInfo", "display-3"))
        assertEquals(0, registrar.liveCount)
        assertEquals(listOf("No se pudo registrar DisplayInfo (IllegalStateException); se sigue sin ese callback."), logs)
    }

    @Test fun `any exception from register is contained, one line per callback`() {
        val telephony = FakeTelephony()
        val logs = mutableListOf<String>()
        val registrar = CallbackRegistrar<String>(telephony::register, telephony::unregister, logs::add)
        telephony.failNext = SecurityException("no permission")
        assertFalse(registrar.add("CellInfo", "cell"))
        telephony.failNext = RuntimeException("vendor")
        assertFalse(registrar.add("ServiceState", "state"))
        assertEquals(2, logs.size)
        assertTrue(logs[0].contains("CellInfo (SecurityException)"))
        assertTrue(logs[1].contains("ServiceState (RuntimeException)"))
    }

    @Test fun `after a success the failure is reported again if it comes back`() {
        val telephony = FakeTelephony()
        val logs = mutableListOf<String>()
        val registrar = CallbackRegistrar<String>(telephony::register, telephony::unregister, logs::add)
        telephony.failNext = IllegalStateException()
        registrar.add("CellInfo", "cell-1")
        assertTrue(registrar.add("CellInfo", "cell-2"))
        registrar.removeAll()
        telephony.failNext = IllegalStateException()
        registrar.add("CellInfo", "cell-3")
        assertEquals(2, logs.size)
    }

    @Test fun `registering the same name twice keeps one listener`() {
        val telephony = FakeTelephony()
        val registrar = CallbackRegistrar<String>(telephony::register, telephony::unregister, {})
        assertTrue(registrar.add("CellInfo", "cell-1"))
        assertTrue(registrar.add("CellInfo", "cell-2"))
        assertEquals(listOf("cell-1"), telephony.live)
        assertTrue(registrar.isRegistered("CellInfo"))
    }

    @Test fun `removeAll unregisters every callback even if one unregister throws`() {
        val telephony = FakeTelephony()
        val registrar = CallbackRegistrar<String>(
            telephony::register,
            { cb -> if (cb == "display") throw IllegalStateException() else telephony.unregister(cb) },
            {}
        )
        registrar.add("CellInfo", "cell")
        registrar.add("DisplayInfo", "display")
        registrar.add("ServiceState", "state")
        registrar.removeAll()
        assertEquals(0, registrar.liveCount)
        assertEquals(listOf("display"), telephony.live)
    }

    @Test fun `never more than three live callbacks`() {
        val telephony = FakeTelephony()
        val logs = mutableListOf<String>()
        val registrar = CallbackRegistrar<String>(telephony::register, telephony::unregister, logs::add)
        registrar.add("CellInfo", "cell")
        registrar.add("DisplayInfo", "display")
        registrar.add("ServiceState", "state")
        registrar.add("Other", "other")
        assertEquals(listOf("other"), telephony.live)
        assertEquals(1, registrar.liveCount)
        assertEquals(listOf(TelephonyRecoveryLog.LIVE_CAP), logs)
    }

    // --- DeferredRestart ---------------------------------------------------------------------

    @Test fun `the restart is queued, never run synchronously, and runs later`() {
        val queue = ArrayDeque<Runnable>()
        val restart = DeferredRestart { queue.addLast(it) }
        val events = mutableListOf<String>()
        // Dentro de la entrega de un callback:
        events += "delivery-start"
        assertTrue(restart.request { events += "destroy"; events += "start" })
        events += "delivery-end"
        assertEquals(listOf("delivery-start", "delivery-end"), events)
        assertTrue(restart.isPending)
        // Después, el executor del hilo principal ejecuta lo encolado.
        queue.removeFirst().run()
        assertEquals(listOf("delivery-start", "delivery-end", "destroy", "start"), events)
        assertFalse(restart.isPending)
    }

    @Test fun `only one restart is queued at a time`() {
        val queue = ArrayDeque<Runnable>()
        val restart = DeferredRestart { queue.addLast(it) }
        var runs = 0
        assertTrue(restart.request { runs++ })
        assertFalse(restart.request { runs++ })
        assertFalse(restart.request { runs++ })
        assertEquals(1, queue.size)
        queue.removeFirst().run()
        assertEquals(1, runs)
        assertTrue("tras ejecutarse se puede volver a encolar", restart.request { runs++ })
    }

    @Test fun `a restart that throws does not leave the queue blocked`() {
        val queue = ArrayDeque<Runnable>()
        val restart = DeferredRestart { queue.addLast(it) }
        restart.request { throw IllegalStateException() }
        runCatching { queue.removeFirst().run() }
        assertFalse(restart.isPending)
        assertTrue(restart.request { })
    }

    @Test fun `if the restart cannot be posted nothing stays pending`() {
        val restart = DeferredRestart { throw java.util.concurrent.RejectedExecutionException() }
        assertFalse(restart.request { })
        assertFalse(restart.isPending)
    }

    // --- El escenario del #49 ----------------------------------------------------------------

    /**
     * Lo que hace TelephonyCollectionController en cada refresco, con un TelephonyManager falso que
     * no libera del todo los listeners desregistrados (como el dispositivo del #49).
     */
    private class Scenario(locationOff: Boolean, limit: Int = 50) {
        var now = 0L
        var locationOff = locationOff
        val telephony = FakeTelephony(limit)
        val leaked = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val queue = ArrayDeque<Runnable>()
        val watchdog = CallbackWatchdog(clock = { now })
        val registrar = CallbackRegistrar<String>(
            telephony::register,
            { cb -> leaked += cb }, // el sistema no lo libera: sigue contando para el tope
            logs::add
        )
        val restart = DeferredRestart { queue.addLast(it) }
        var counter = 0

        fun start() {
            listOf("CellInfo", "DisplayInfo", "ServiceState").forEach { registrar.add(it, "$it-${counter++}") }
        }

        fun refresh(cellsDelivered: Boolean) {
            when (val d = watchdog.onRefreshRequested(cellsBlockedByKnownCause = locationOff)) {
                is CallbackWatchdog.Decision.Restart -> restart.request { registrar.removeAll(); start() }
                is CallbackWatchdog.Decision.KnownCause -> if (d.announce) logs += TelephonyRecoveryLog.LOCATION_OFF
                else -> Unit
            }
            start()
            if (cellsDelivered) watchdog.onDelivery()
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    @Test fun `with the location off the listeners do not pile up and nothing throws`() {
        val s = Scenario(locationOff = true)
        repeat(24 * 720) { // un día, un refresco cada 5 s
            s.now += 5_000L
            s.refresh(cellsDelivered = false)
        }
        assertEquals("solo el registro inicial", 3, s.telephony.registrations)
        assertEquals(listOf(TelephonyRecoveryLog.LOCATION_OFF), s.logs)
    }

    @Test fun `with total silence restarts are few and the system limit is never reached`() {
        val s = Scenario(locationOff = false, limit = 50)
        repeat(24 * 720) {
            s.now += 5_000L
            s.refresh(cellsDelivered = false)
        }
        // 3 iniciales + 3 por cada uno de los 5 reinicios antes de pararse, lejos del tope de 50
        // aunque el sistema no libere ninguno de los desregistrados.
        assertEquals(3 + 3 * CallbackWatchdog.MAX_CONSECUTIVE_RESTARTS, s.telephony.registrations)
        assertTrue(s.telephony.live.size < 50)
        assertTrue(s.logs.none { it.startsWith("No se pudo registrar") })
    }

    @Test fun `normal deliveries with cells keep the same three listeners`() {
        val s = Scenario(locationOff = false)
        repeat(720) {
            s.now += 5_000L
            s.refresh(cellsDelivered = true)
        }
        assertEquals(3, s.telephony.registrations)
        assertEquals(3, s.registrar.liveCount)
        assertTrue(s.logs.isEmpty())
    }

    @Test fun `log lines`() {
        assertEquals(
            "Watchdog: callbacks de telefonía reiniciados; 45 s sin entregas de celdas.",
            TelephonyRecoveryLog.restarted(45_000L)
        )
        assertEquals(
            "Watchdog: 5 reinicios seguidos sin celdas; no se reinicia más hasta que cambie la ubicación, el permiso o la red.",
            TelephonyRecoveryLog.paused(5)
        )
        assertEquals(
            "No se pudo pedir celdas (IllegalStateException); se reintenta en el siguiente ciclo.",
            TelephonyRecoveryLog.requestFailed(IllegalStateException())
        )
    }
}
