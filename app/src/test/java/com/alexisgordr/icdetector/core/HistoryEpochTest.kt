package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.10.10 — Un resultado empezado antes de "Borrar historial" no se aplica después. Reproduce la
 * carrera de la publicación en pantalla: el resultado se encola, se borra el historial y después se
 * ejecuta la tarea encolada.
 */
class HistoryEpochTest {
    private val epoch = HistoryEpoch()

    @Test fun `a publication queued before a deletion is not applied after it`() {
        var screen = "PENDING"
        val mainQueue = ArrayDeque<() -> Unit>()

        // La verificación termina y encola la publicación con la época en que empezó.
        val startedAt = epoch.current()
        mainQueue.addLast { epoch.ifCurrent(startedAt) { screen = "VERIFIED" } }

        epoch.invalidate()                         // "Borrar historial" antes de que corra Main
        mainQueue.removeFirst().invoke()           // se libera la publicación retenida

        assertEquals("PENDING", screen)
    }

    @Test fun `a publication started after the deletion is applied`() {
        var screen = "PENDING"
        epoch.invalidate()
        val startedAt = epoch.current()
        assertTrue(epoch.ifCurrent(startedAt) { screen = "VERIFIED" })
        assertEquals("VERIFIED", screen)
    }

    @Test fun `invalidation waits for a result that is being applied`() {
        val startedAt = epoch.current()
        val insideBlock = CountDownLatch(1)
        val releaseBlock = CountDownLatch(1)
        val applied = AtomicBoolean(false)
        val invalidated = AtomicBoolean(false)

        val writer = Thread {
            epoch.ifCurrent(startedAt) {
                insideBlock.countDown()
                releaseBlock.await(5, TimeUnit.SECONDS)
                applied.set(true)
            }
        }.apply { start() }
        assertTrue(insideBlock.await(5, TimeUnit.SECONDS))

        val deleter = Thread { epoch.invalidate(); invalidated.set(true) }.apply { start() }
        Thread.sleep(100)
        // El borrado no puede invalidar mientras el resultado se está aplicando.
        assertFalse(invalidated.get())

        releaseBlock.countDown()
        writer.join(5_000); deleter.join(5_000)
        assertTrue(applied.get())
        assertTrue(invalidated.get())
        // Y tras invalidar, ese mismo trabajo ya no puede aplicar nada más.
        assertFalse(epoch.ifCurrent(startedAt) {})
    }
}
