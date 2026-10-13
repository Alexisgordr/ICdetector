package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 3.0 (V4) — La comprobación de vigencia y los efectos de una respuesta son indivisibles frente a
 * un cambio de ajustes, y descartar una consulta antigua no borra el pendiente de la nueva.
 */
class VerificationConfigRaceTest {

    @Test fun `a settings change attempted between the check and the effects waits until they finish`() {
        val holder = VerificationConfigHolder("old", false)
        val snapshot = holder.current()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val changer = Executors.newSingleThreadExecutor()
        var changeDone: java.util.concurrent.Future<*>? = null
        val applied = holder.applyIfCurrent(snapshot) {
            // Justo después de comprobar la vigencia, el usuario guarda ajustes nuevos.
            changeDone = changer.submit { holder.update("new", true) { order += "cleanup" }; order += "changed" }
            Thread.sleep(200)
            assertEquals("el cambio no se cuela", snapshot.generation, holder.current().generation)
            order += "effects"
        }
        assertTrue(applied)
        changeDone!!.get(5, TimeUnit.SECONDS); changer.shutdown()
        assertEquals(listOf("effects", "cleanup", "changed"), order)
        // Tras el cambio, una respuesta de los ajustes viejos ya no se aplica.
        var ran = false
        assertFalse(holder.applyIfCurrent(snapshot) { ran = true })
        assertFalse(ran)
    }

    @Test fun `under concurrent saves no effect ever runs with stale settings`() {
        val holder = VerificationConfigHolder("k0", false)
        val stop = AtomicBoolean(false); val stale = AtomicInteger(); val applied = AtomicInteger(); val skipped = AtomicInteger()
        val pool = Executors.newFixedThreadPool(6)
        pool.execute { var i = 0; while (!stop.get()) { holder.update("k${++i}", i % 3 == 0) } }
        repeat(5) {
            pool.execute {
                while (!stop.get()) {
                    val snap = holder.current()
                    Thread.yield()   // la «consulta»
                    val ok = holder.applyIfCurrent(snap) {
                        repeat(50) { if (holder.current().generation != snap.generation) stale.incrementAndGet() }
                    }
                    if (ok) applied.incrementAndGet() else skipped.incrementAndGet()
                }
            }
        }
        Thread.sleep(500); stop.set(true); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(0, stale.get())
        assertTrue(applied.get() > 0); assertTrue(skipped.get() > 0)
    }

    @Test fun `discarding an old query never drops the pending of a newer query for the same cell`() {
        val pending = PendingQueries()
        val cache = ConcurrentHashMap<String, VerificationStatus>()
        fun start(key: String) = synchronized(cache) { cache[key] = VerificationStatus.PENDING; pending.claim(key) }
        fun discard(key: String, q: Long) = synchronized(cache) {
            if (pending.release(key, q) && cache[key] == VerificationStatus.PENDING) cache.remove(key)
        }
        val old = start("cell")
        // Cambian los ajustes; la celda vuelve a consultarse con los nuevos antes de que llegue la vieja.
        val fresh = start("cell")
        discard("cell", old)
        assertEquals(VerificationStatus.PENDING, cache["cell"])
        assertTrue(pending.owns("cell", fresh))
        // La nueva, si también se descarta, sí lo libera.
        discard("cell", fresh)
        assertEquals(null, cache["cell"])
        assertFalse(pending.release("cell", fresh))
    }

    @Test fun `pending ownership is consistent under concurrency`() {
        val pending = PendingQueries()
        val pool = Executors.newFixedThreadPool(8)
        val wins = AtomicInteger()
        val start = CountDownLatch(1)
        val ids = (1..200).map { pending.claim("k$it") }
        repeat(200) { i -> pool.execute { start.await(); if (pending.release("k${i + 1}", ids[i])) wins.incrementAndGet(); pending.release("k${i + 1}", ids[i]) } }
        start.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("cada consulta libera lo suyo exactamente una vez", 200, wins.get())
    }

    @Test fun `the controller applies results and discards under the configuration lock`() {
        val src = listOf("app/src/main/java/com/alexisgordr/icdetector/service/ExternalVerificationController.kt",
            "src/main/java/com/alexisgordr/icdetector/service/ExternalVerificationController.kt").map(::File).first { it.exists() }.readText()
        assertTrue(src.contains("config.applyIfCurrent(snapshot) { applyResult(cell, key, snapshot, result, generation) }"))
        assertTrue(src.contains("val applied = config.applyIfCurrent(snapshot) {\n                logPauseOnce(pause)"))
        val discard = src.substringAfter("private fun discardStale(").substringBefore("private fun applyResult(")
        assertTrue(discard.contains("pending.release(key, query) && cache[key] == VerificationStatus.PENDING"))
        assertFalse("ya no borra un PENDING ajeno", src.contains("if (cache[key] == VerificationStatus.PENDING) cache.remove(key)"))
        assertFalse(src.contains("!config.isCurrent(snapshot)"))
        val service = listOf("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt",
            "src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt").map(::File).first { it.exists() }.readText()
        assertTrue(service.contains("verificationConfig.update(apiKey, proxyEnabled) {"))
    }
}
