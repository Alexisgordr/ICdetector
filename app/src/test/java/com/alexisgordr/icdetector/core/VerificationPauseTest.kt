package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.backup.BackupSettings
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.VerificationStatus
import com.alexisgordr.icdetector.network.OpenCellIdClient
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 3.0 (V1) — Reintentos de OpenCellID según el fallo: clasificación por códigos documentados,
 * Retry-After, pausa global por credencial, reinicios y cambios de reloj. Las respuestas son
 * simuladas con un interceptor: ninguna prueba sale a la red ni gasta cuota.
 */
class VerificationPauseTest {
    private val min = 60_000L
    private val day = 24 * 60 * min
    private fun clock(civil: Long = 1_700_000_000_000L, elapsed: Long = 10 * min, boot: String? = "7") = MobilityClock(civil, elapsed, boot)
    private val fpA = VerificationConfig("key-A", false, 0).credentialFingerprint
    private val fpB = VerificationConfig("key-B", false, 0).credentialFingerprint

    // ── Clasificación ─────────────────────────────────────────────────────────────────────

    @Test fun `documented codes decide the class, not the text`() {
        val c = ExternalFailureClassifier
        assertEquals(ExternalFailure.NONE, c.classify(200, 1))
        assertEquals(ExternalFailure.INVALID_CREDENTIAL, c.classify(401, 2))
        assertEquals(ExternalFailure.INVALID_CREDENTIAL, c.classify(200, 2))
        assertEquals(ExternalFailure.INVALID_CREDENTIAL, c.classify(401, -1))
        assertEquals(ExternalFailure.INVALID_INPUT, c.classify(400, 3))
        assertEquals(ExternalFailure.ACCESS_RESTRICTED, c.classify(403, 4))
        assertEquals(ExternalFailure.TRANSIENT, c.classify(500, 5))
        assertEquals(ExternalFailure.RATE_LIMITED, c.classify(503, 6))
        assertEquals(ExternalFailure.RATE_LIMITED, c.classify(503, -1))
        assertEquals(ExternalFailure.DAILY_QUOTA, c.classify(429, 7))
        assertEquals("429 sin code 7: limitación temporal", ExternalFailure.RATE_LIMITED, c.classify(429, -1))
        assertEquals(ExternalFailure.RATE_LIMITED, c.classify(429, 6))
        assertEquals(ExternalFailure.TRANSIENT, c.classify(404, -1))
        assertEquals(ExternalFailure.TRANSIENT, c.classify(502, -1))
        assertEquals("sin respuesta", ExternalFailure.TRANSIENT, c.classify(null, -1))
        assertEquals(ExternalFailure.TRANSIENT, c.classify(200, 1, temporaryNotice = true))
        assertEquals(ExternalFailure.NONE, c.classify(200, -1))
    }

    // ── Retry-After ───────────────────────────────────────────────────────────────────────

    @Test fun `Retry-After as seconds and as an HTTP date`() {
        val now = Instant.parse("2026-10-13T10:00:00Z").toEpochMilli()
        assertEquals(120_000L, RetryAfter.parse("120", now))
        assertEquals(0L, RetryAfter.parse("0", now))
        assertEquals(90_000L, RetryAfter.parse("Tue, 13 Oct 2026 10:01:30 GMT", now))
        val inFiveHours = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 5 * 3_600_000).atZone(ZoneOffset.UTC))
        assertEquals(5 * 3_600_000L, RetryAfter.parse(inFiveHours, now))
        assertEquals("fecha pasada: ya", 0L, RetryAfter.parse("Tue, 13 Oct 2026 09:00:00 GMT", now))
    }

    @Test fun `invalid Retry-After values are ignored`() {
        val now = Instant.parse("2026-10-13T10:00:00Z").toEpochMilli()
        listOf(null, "", "  ", "-5", "1.5", "abc", "120s", "99999999999999999999", "${49 * 3600}",
            "Fri, 13 Nov 2026 10:00:00 GMT", "13/10/2026").forEach {
            assertNull("«$it»", RetryAfter.parse(it, now))
        }
    }

    // ── Pausa global ──────────────────────────────────────────────────────────────────────

    @Test fun `an invalid key pauses until the key changes, and the pause is not per cell`() {
        val gate = VerificationPauseGate()
        assertNotNull(gate.onResult(fpA, ExternalFailure.INVALID_CREDENTIAL, null, clock()))
        // Muchas horas y muchas celdas después: sigue en pausa (la pausa no sabe de celdas).
        listOf(clock(elapsed = 20 * min), clock(civil = 1_800_000_000_000L, elapsed = 1000 * min)).forEach {
            assertEquals(ExternalFailure.INVALID_CREDENTIAL, gate.blocking(fpA, it)?.kind)
        }
        assertNull("otra clave no hereda la pausa", gate.blocking(fpB, clock()))
        gate.onConfigChanged(fpB)
        assertNull(gate.blocking(fpB, clock()))
        assertNull("al volver a la clave anterior se prueba de nuevo", gate.blocking(fpA, clock()))
    }

    @Test fun `whitelisting required behaves like an invalid credential`() {
        val gate = VerificationPauseGate()
        gate.onResult(fpA, ExternalFailure.ACCESS_RESTRICTED, null, clock())
        assertEquals(ExternalFailure.ACCESS_RESTRICTED, gate.blocking(fpA, clock(elapsed = 10_000 * min))?.kind)
    }

    @Test fun `a rate limit honours a valid Retry-After`() {
        val gate = VerificationPauseGate()
        gate.onResult(fpA, ExternalFailure.RATE_LIMITED, 5 * min, clock())
        assertNotNull(gate.blocking(fpA, clock(elapsed = 10 * min + 5 * min - 1)))
        assertNull(gate.blocking(fpA, clock(elapsed = 15 * min)))
    }

    @Test fun `without Retry-After a rate limit backs off and is bounded`() {
        val gate = VerificationPauseGate()
        var t = 10 * min
        val waits = (1..8).map {
            val p = gate.onResult(fpA, ExternalFailure.RATE_LIMITED, null, clock(elapsed = t))!!
            (p.untilElapsedMs!! - t).also { t = p.untilElapsedMs!! }
        }
        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30, 30).map { it * min }, waits)
        gate.onResult(fpA, ExternalFailure.NONE, null, clock(elapsed = t))
        val again = gate.onResult(fpA, ExternalFailure.RATE_LIMITED, null, clock(elapsed = t))!!
        assertEquals("una respuesta buena reinicia la racha", min, again.untilElapsedMs!! - t)
    }

    @Test fun `the daily quota waits a full day, never until an assumed midnight`() {
        val gate = VerificationPauseGate()
        val lateUtc = Instant.parse("2026-10-13T23:59:00Z").toEpochMilli()
        val p = gate.onResult(fpA, ExternalFailure.DAILY_QUOTA, null, clock(civil = lateUtc))!!
        assertEquals(day, p.untilCivilMs!! - lateUtc)
        assertNotNull("pasada la medianoche UTC sigue en pausa", gate.blocking(fpA, clock(civil = lateUtc + 2 * min, elapsed = 12 * min)))
        assertNull(gate.blocking(fpA, clock(civil = lateUtc + day, elapsed = 10 * min + day)))
        // Con Retry-After válido se usa ese plazo.
        val g2 = VerificationPauseGate()
        assertEquals(3 * 60 * min, g2.onResult(fpA, ExternalFailure.DAILY_QUOTA, 3 * 60 * min, clock())!!.untilElapsedMs!! - 10 * min)
    }

    @Test fun `a transient failure backs off in memory and any answer resets it`() {
        val store = InMemoryVerificationPauseStore()
        val gate = VerificationPauseGate(store)
        var t = 10 * min
        val waits = (1..7).map {
            val p = gate.onResult(fpA, ExternalFailure.TRANSIENT, null, clock(elapsed = t))!!
            (p.untilElapsedMs!! - t).also { t = p.untilElapsedMs!! }
        }
        assertEquals(listOf(30_000L, min, 2 * min, 4 * min, 8 * min, 15 * min, 15 * min), waits)
        assertNull("no se guarda", store.load())
        gate.onResult(fpA, ExternalFailure.TRANSIENT, null, clock(elapsed = t))
        gate.onResult(fpA, ExternalFailure.INVALID_INPUT, null, clock(elapsed = t))
        assertNull("una respuesta (aunque sea de entrada inválida) la levanta", gate.blocking(fpA, clock(elapsed = t)))
        assertNull("la entrada inválida no pausa", gate.blocking(fpA, clock(elapsed = t + 1)))
    }

    @Test fun `a route change lifts the transient pause but not the quota`() {
        val gate = VerificationPauseGate()
        gate.onResult(fpA, ExternalFailure.TRANSIENT, null, clock())
        gate.onConfigChanged(fpA)
        assertNull(gate.blocking(fpA, clock()))
        gate.onResult(fpA, ExternalFailure.DAILY_QUOTA, null, clock())
        gate.onConfigChanged(fpA)
        assertNotNull("misma credencial: el cupo sigue agotado", gate.blocking(fpA, clock()))
    }

    @Test fun `the pause survives a restart and a civil clock jump in the same boot`() {
        val store = InMemoryVerificationPauseStore()
        VerificationPauseGate(store).onResult(fpA, ExternalFailure.RATE_LIMITED, 10 * min, clock())
        // Proceso nuevo, mismo arranque; el usuario adelanta el reloj civil un día.
        val restarted = VerificationPauseGate(store)
        assertNotNull(restarted.blocking(fpA, clock(civil = 1_700_000_000_000L + day, elapsed = 15 * min)))
        assertNull(restarted.blocking(fpA, clock(civil = 1_700_000_000_000L + day, elapsed = 20 * min)))
        assertNull("terminada, se borra", store.load())
        // Clave inválida guardada: tras reiniciar sigue en pausa con la misma clave.
        VerificationPauseGate(store).onResult(fpA, ExternalFailure.INVALID_CREDENTIAL, null, clock())
        assertNotNull(VerificationPauseGate(store).blocking(fpA, clock(boot = "8", elapsed = 1)))
    }

    @Test fun `after a reboot the civil clock decides, bounded by the class maximum`() {
        val store = InMemoryVerificationPauseStore()
        val civil = 1_700_000_000_000L
        VerificationPauseGate(store).onResult(fpA, ExternalFailure.DAILY_QUOTA, null, clock(civil = civil))
        // Otro arranque, 6 h después: quedan 18 h.
        val g = VerificationPauseGate(store)
        val p = g.blocking(fpA, clock(civil = civil + 6 * 60 * min, elapsed = 5 * min, boot = "8"))!!
        assertEquals("se reancla al arranque nuevo", "8", p.bootId)
        assertEquals(18 * 60 * min, p.untilElapsedMs!! - 5 * min)
        // Reloj civil atrasado un año tras otro reinicio: no más de un día.
        val g2 = VerificationPauseGate(store)
        val back = g2.blocking(fpA, clock(civil = civil - 365 * day, elapsed = 1 * min, boot = "9"))!!
        assertTrue(back.untilElapsedMs!! - 1 * min <= day)
        assertNull(g2.blocking(fpA, clock(civil = civil - 365 * day, elapsed = 1 * min + day, boot = "9")))
        // Sin número de arranque y con el reloj adelantado: termina.
        VerificationPauseGate(store).onResult(fpA, ExternalFailure.RATE_LIMITED, 5 * min, clock(boot = null))
        assertNull(VerificationPauseGate(store).blocking(fpA, clock(civil = 1_700_000_000_000L + 6 * min, boot = null)))
    }

    @Test fun `a stored pause of another key does not block the new key and is dropped on change`() {
        val store = InMemoryVerificationPauseStore()
        VerificationPauseGate(store).onResult(fpA, ExternalFailure.DAILY_QUOTA, null, clock())
        val g = VerificationPauseGate(store)
        assertNull(g.blocking(fpB, clock()))
        g.onConfigChanged(fpB)
        assertNull(store.load())
    }

    @Test fun `a late answer for the old key never pauses the new key`() {
        // El controlador solo pasa resultados de la configuración vigente; aun así, la pausa es de
        // la huella con la que se hizo la consulta.
        val holder = VerificationConfigHolder("key-A", false)
        val old = holder.current()
        val gate = VerificationPauseGate()
        holder.update("key-B", false); gate.onConfigChanged(holder.current().credentialFingerprint)
        if (holder.isCurrent(old)) gate.onResult(old.credentialFingerprint, ExternalFailure.INVALID_CREDENTIAL, null, clock())
        gate.onResult(old.credentialFingerprint, ExternalFailure.DAILY_QUOTA, null, clock())   // aunque llegara
        assertNull(gate.blocking(holder.current().credentialFingerprint, clock()))
    }

    @Test fun `concurrent results keep one coherent, longest pause`() {
        val store = InMemoryVerificationPauseStore()
        val gate = VerificationPauseGate(store)
        val pool = Executors.newFixedThreadPool(8)
        val blocked = AtomicInteger()
        repeat(400) { i ->
            pool.execute {
                if (i % 2 == 0) gate.onResult(fpA, ExternalFailure.RATE_LIMITED, (i % 7 + 1) * min, clock())
                else if (gate.blocking(fpA, clock()) != null) blocked.incrementAndGet()
            }
        }
        pool.shutdown(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        val p = gate.blocking(fpA, clock())!!
        assertEquals(7 * min, p.untilElapsedMs!! - 10 * min)
        assertEquals(p, store.load())
        assertTrue(blocked.get() > 0)
    }

    @Test fun `the stored pause carries a truncated fingerprint, never the key, and stays out of backups`() {
        val store = InMemoryVerificationPauseStore()
        VerificationPauseGate(store).onResult(fpA, ExternalFailure.INVALID_CREDENTIAL, null, clock())
        val text = store.load()!!.serialize()
        assertFalse(text.contains("key-A"))
        assertEquals(store.load(), VerificationPauseState.parse(text))
        listOf(null, "", "x|y", "BOGUS|f||||", "RATE_LIMITED||1|2|3|4").forEach { assertNull(VerificationPauseState.parse(it)) }
        assertFalse(BackupSettings.KEYS.containsKey(VerificationPauseGate.PREF_KEY))
        assertFalse(BackupSettings.KEYS.containsKey(BackupSettings.NEVER_EXPORTED))
        assertTrue("el proxy sí viaja en la copia", BackupSettings.KEYS.containsKey("proxy_enabled"))
    }

    @Test fun `terminal lines never carry the fingerprint`() {
        val c = clock()
        ExternalFailure.entries.filter { it != ExternalFailure.NONE && it != ExternalFailure.INVALID_INPUT }.forEach { k ->
            val p = VerificationPauseGate().onResult(fpA, k, null, c)!!
            val line = VerificationPauseGate.describe(p, c)
            assertFalse(line, line.contains(fpA))
            assertTrue(line, line.startsWith("OpenCellID"))
        }
    }

    // ── Cliente con respuestas simuladas ──────────────────────────────────────────────────

    private val cell = CellData(isRegistered = true, networkType = "4G LTE", cellId = "1234", mnc = "07", tac = "100", dbm = -80, mcc = "214", radioTech = RadioTech.LTE)

    private fun fake(code: Int, body: String, vararg headers: Pair<String, String>) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }).build()

    private fun ask(client: OkHttpClient, key: String = "key-A") =
        OpenCellIdClient.tryOpenCellIdSyncWithData(cell, VerificationConfig(key, false, 0), client)

    @Test fun `simulated answers keep the verdict semantics and carry the failure class`() {
        val quota = ask(fake(429, """{"code":7,"error":"Daily limit"}""", "Retry-After" to "3600"))
        assertEquals(VerificationStatus.ERROR, quota.status)
        assertEquals(ExternalFailure.DAILY_QUOTA, quota.externalFailure)
        assertEquals(3_600_000L, quota.retryAfterMillis)

        val now = System.currentTimeMillis()
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 120_000).atZone(ZoneOffset.UTC))
        val busy = ask(fake(503, """{"code":6}""", "Retry-After" to date))
        assertEquals(ExternalFailure.RATE_LIMITED, busy.externalFailure)
        assertTrue(busy.retryAfterMillis!! in 100_000L..121_000L)

        // HTTP 429 sin code 7: limitación temporal que respeta Retry-After, no cuota diaria.
        val plain429 = ask(fake(429, "Too Many Requests", "Retry-After" to "90"))
        assertEquals(VerificationStatus.ERROR, plain429.status)
        assertEquals(ExternalFailure.RATE_LIMITED, plain429.externalFailure)
        assertEquals(90_000L, plain429.retryAfterMillis)
        assertFalse(plain429.reason!!.contains("diario"))
        val g = VerificationPauseGate()
        val p429 = g.onResult(fpA, plain429.externalFailure, plain429.retryAfterMillis, clock())!!
        assertEquals(90_000L, p429.untilElapsedMs!! - 10 * min)
        assertEquals(ExternalFailure.RATE_LIMITED, ask(fake(429, "")).externalFailure)

        val bogus = ask(fake(503, "", "Retry-After" to "soon"))
        assertEquals(ExternalFailure.RATE_LIMITED, bogus.externalFailure)
        assertNull(bogus.retryAfterMillis)

        assertEquals(ExternalFailure.INVALID_CREDENTIAL, ask(fake(401, """{"code":2,"error":"API Key not known"}""")).externalFailure)
        assertEquals("código de la API aunque el HTTP sea 200", ExternalFailure.INVALID_CREDENTIAL, ask(fake(200, """{"code":2}""")).externalFailure)
        assertEquals(ExternalFailure.ACCESS_RESTRICTED, ask(fake(403, """{"code":4}""")).externalFailure)
        assertEquals(ExternalFailure.INVALID_INPUT, ask(fake(400, """{"code":3}""")).externalFailure)
        assertEquals(ExternalFailure.TRANSIENT, ask(fake(500, """{"code":5}""")).externalFailure)

        val notFound = ask(fake(200, """{"code":1,"error":"Cell not found"}"""))
        assertEquals(VerificationStatus.NOT_FOUND, notFound.status)
        assertEquals(ExternalFailure.NONE, notFound.externalFailure)
        // Retry-After en una respuesta que no es un límite no se usa.
        assertNull(ask(fake(200, """{"code":1}""", "Retry-After" to "60")).retryAfterMillis)

        val ok = ask(fake(200, """{"lat":42.8,"lon":-1.6,"mcc":214,"mnc":7,"lac":100,"cellid":1234,"radio":"LTE"}"""))
        assertEquals(VerificationStatus.VERIFIED, ok.status)
        assertEquals(ExternalFailure.NONE, ok.externalFailure)
    }

    @Test fun `a quota or limit answer is never NOT_FOUND`() {
        listOf(429 to """{"code":7}""", 503 to """{"code":6}""", 200 to """{"code":7}""", 200 to """{"code":6}""").forEach { (h, b) ->
            assertEquals("$h $b", VerificationStatus.ERROR, ask(fake(h, b)).status)
        }
    }

    @Test fun `the key echoed by the API is redacted and network errors are transient`() {
        val r = ask(fake(401, """{"code":2,"error":"API Key not known: secret-key-123"}"""), key = "secret-key-123")
        assertFalse(r.reason!!, r.reason!!.contains("secret-key-123"))
        val down = ask(OkHttpClient.Builder().addInterceptor(Interceptor { throw IOException("connect to opencellid.org?key=secret") }).build())
        assertEquals(ExternalFailure.TRANSIENT, down.externalFailure)
        assertFalse(down.reason!!.contains("secret"))
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun `coroutine cancellation propagates instead of being a network error`() {
        ask(OkHttpClient.Builder().addInterceptor(Interceptor { throw kotlinx.coroutines.CancellationException("stop") }).build())
    }

    // ── Cableado (comprobación de fuente) ─────────────────────────────────────────────────

    @Test fun `the gate sits after the local check and before the network, and logs nothing sensitive`() {
        val src = listOf("app/src/main/java/com/alexisgordr/icdetector/service/ExternalVerificationController.kt",
            "src/main/java/com/alexisgordr/icdetector/service/ExternalVerificationController.kt").map(::File).first { it.exists() }.readText()
        val verifyNow = src.substringAfter("private fun verifyNow(").substringBefore("private fun complete(")
        val local = verifyNow.indexOf("db.getKnownStatus(")
        val gate = verifyNow.indexOf("pauseGate.blocking(")
        val net = verifyNow.indexOf("OpenCellIdClient.tryOpenCellIdSyncWithData(")
        assertTrue(local in 0 until gate && gate < net)
        // El resultado solo abre una pausa dentro de applyIfCurrent (vigencia y efectos juntos).
        assertTrue(verifyNow.contains("config.applyIfCurrent(snapshot) { applyResult("))
        assertTrue(src.substringAfter("private fun applyResult(").contains("pauseGate.onResult("))
        assertFalse(verifyNow.substringBefore("private fun applyResult(").contains("pauseGate.onResult("))
        assertFalse(src.contains("apiKey"))
        assertFalse(src.contains("credentialFingerprint}"))
        assertFalse("ya no se reintenta solo por el texto", verifyNow.contains("reason?.contains"))
    }
}
