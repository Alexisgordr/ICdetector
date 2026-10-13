package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.network.OpenCellIdClient
import com.alexisgordr.icdetector.network.VerificationFailure
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 3.0 (V4) — Configuración de OpenCellID coherente por consulta: clave y ruta se publican juntas, cada
 * consulta usa una instantánea, un cambio de ruta cancela lo que está en curso, y con Tor no hay
 * salida directa de reserva. Sin red real ni cuota.
 */
class VerificationConfigTest {

    @Test fun `readers never see a key from one save and a route from another`() {
        val holder = VerificationConfigHolder("key-false", false)
        val stop = AtomicBoolean(false); val bad = AtomicInteger(); val reads = AtomicInteger()
        val pool = Executors.newFixedThreadPool(6)
        repeat(2) { w -> pool.execute { var i = 0; while (!stop.get()) { val p = (i++ + w) % 2 == 0; holder.update("key-$p", p) } } }
        repeat(4) { pool.execute { while (!stop.get()) { val c = holder.current(); reads.incrementAndGet(); if (c.apiKey != "key-${c.proxyEnabled}") bad.incrementAndGet() } } }
        Thread.sleep(400); stop.set(true); pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
        assertTrue(reads.get() > 1000)
        assertEquals("ninguna pareja mezclada", 0, bad.get())
    }

    @Test fun `every change bumps the generation and invalidates older snapshots`() {
        val holder = VerificationConfigHolder("a", false)
        val first = holder.current()
        assertEquals(VerificationConfigHolder.Change.NONE, holder.update(" a ", false))
        assertTrue("sin cambios sigue vigente", holder.isCurrent(first))
        assertEquals(VerificationConfigHolder.Change.CREDENTIAL, holder.update("b", false))
        assertFalse(holder.isCurrent(first))
        val second = holder.current()
        assertEquals(VerificationConfigHolder.Change.ROUTE, holder.update("b", true))
        assertFalse(holder.isCurrent(second))
        assertEquals(VerificationConfigHolder.Change.CREDENTIAL_AND_ROUTE, holder.update("c", false))
        assertEquals(3, holder.current().generation)
    }

    @Test fun `a route change cancels requests in flight, a key change only invalidates them`() {
        val holder = VerificationConfigHolder("a", false)
        val snap = holder.current()
        var cancelled = 0
        val untrack = holder.track(snap) { cancelled++ }
        holder.update("b", false)                       // solo credencial
        assertEquals(0, cancelled)
        assertFalse("pero su resultado ya no vale", holder.isCurrent(snap))
        holder.update("b", true)                        // cambio de ruta
        assertEquals(1, cancelled)
        assertEquals(0, holder.inFlightCount())
        untrack()
        // Una petición que se registra con una instantánea ya caducada no llega a empezar.
        var late = 0
        holder.track(snap) { late++ }
        assertEquals(1, late)
        // La vigente no se cancela.
        var ok = 0
        val done = holder.track(holder.current()) { ok++ }
        assertEquals(0, ok); done(); assertEquals(0, holder.inFlightCount())
    }

    @Test fun `a stale snapshot never opens a connection`() {
        val holder = VerificationConfigHolder("key", false)
        val snap = holder.current()
        holder.update("key", true)
        val cell = CellData(isRegistered = true, networkType = "4G LTE", cellId = "1", mnc = "07", tac = "1", dbm = -80, mcc = "214", radioTech = RadioTech.LTE)
        val r = OpenCellIdClient.tryOpenCellIdSyncWithData(cell, snap, OkHttpClient(), holder)
        assertEquals(VerificationFailure.CONFIG_CHANGED, r.failure)
    }

    @Test fun `the proxy is explicit, and without it no system proxy is inherited`() {
        val base = OkHttpClient()
        assertEquals(OpenCellIdClient.TOR_PROXY, OpenCellIdClient.clientFor(VerificationConfig("k", true, 0), base).proxy)
        assertEquals(Proxy.Type.SOCKS, OpenCellIdClient.TOR_PROXY.type())
        assertEquals(Proxy.NO_PROXY, OpenCellIdClient.clientFor(VerificationConfig("k", false, 0), base).proxy)
    }

    @Test fun `with Tor selected and unavailable there is no direct fallback`() {
        val torUp = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 9050), 200) } }.isSuccess
        assumeTrue("necesita el puerto 9050 cerrado", !torUp)
        ServerSocket(0).use { server ->
            val accepted = AtomicInteger()
            val ready = CountDownLatch(1)
            val t = Thread {
                ready.countDown()
                runCatching { while (true) server.accept().use { s -> accepted.incrementAndGet()
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray()) } }
            }.apply { isDaemon = true; start() }
            ready.await()
            val url = "http://127.0.0.1:${server.localPort}/"
            val base = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
            val viaTor = OpenCellIdClient.clientFor(VerificationConfig("k", true, 0), base)
            val failed = runCatching { viaTor.newCall(Request.Builder().url(url).build()).execute().close() }.isFailure
            assertTrue("con Tor caído la petición falla", failed)
            assertEquals("y nunca sale directa", 0, accepted.get())
            // Control: sin proxy, el mismo servidor sí recibe la conexión.
            OpenCellIdClient.clientFor(VerificationConfig("k", false, 0), base).newCall(Request.Builder().url(url).build()).execute().close()
            assertEquals(1, accepted.get())
            server.close(); t.join(1000)
        }
    }

    @Test fun `the key never appears in text, and the service publishes both values together`() {
        assertFalse(VerificationConfig("secret-key", true, 1).toString().contains("secret-key"))
        assertFalse(VerificationConfig.fingerprint("secret-key").contains("secret"))
        assertEquals(VerificationConfig.fingerprint("k"), VerificationConfig.fingerprint(" k "))
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val service = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        assertFalse("sin campos sueltos", Regex("""var openCellIdKey|var isProxyEnabled""").containsMatchIn(service))
        val settings = main.resolve("com/alexisgordr/icdetector/ui/SettingsScreen.kt").readText()
        assertTrue(settings.contains("service?.updateVerificationSettings(cleanToken, proxyEnabled)"))
        val controller = main.resolve("com/alexisgordr/icdetector/service/ExternalVerificationController.kt").readText()
        assertTrue(controller.contains("val snapshot = config.current()"))
        assertTrue(controller.contains("OpenCellIdClient.tryOpenCellIdSyncWithData(cell, snapshot, client, config)"))
    }
}
