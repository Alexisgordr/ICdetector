package com.alexisgordr.icdetector.core

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 3.0 (V1) — Por qué falló una consulta a OpenCellID, según los códigos DOCUMENTADOS (tabla de errores
 * de la wiki de OpenCellID, página *API*), no según el texto de la respuesta:
 *
 * | código | HTTP | significado documentado         | clase                 |
 * |--------|------|---------------------------------|-----------------------|
 * | 1      | 200  | celda no encontrada             | (NOT_FOUND, no fallo) |
 * | 2      | 401  | API key inválida                | [INVALID_CREDENTIAL]  |
 * | 3      | 400  | datos de entrada inválidos      | [INVALID_INPUT]       |
 * | 4      | 403  | hace falta lista blanca         | [ACCESS_RESTRICTED]   |
 * | 5      | 500  | error interno del servidor      | [TRANSIENT]           |
 * | 6      | 503  | demasiadas peticiones           | [RATE_LIMITED]        |
 * | 7      | 429  | límite diario superado          | [DAILY_QUOTA]         |
 *
 * Solo el code 7 indica la cuota diaria: un HTTP 429 sin él se trata como [RATE_LIMITED].
 *
 * La página *Access and limits* da 1.000 créditos de API por usuario y día y recomienda no repetir
 * en el acto tras un límite o un error temporal; no documenta a qué hora se reinicia el día ni la
 * cabecera Retry-After. Fallos sin respuesta (red, Tor caído, TLS) y otros HTTP, [TRANSIENT].
 */
enum class ExternalFailure { NONE, INVALID_INPUT, INVALID_CREDENTIAL, ACCESS_RESTRICTED, RATE_LIMITED, DAILY_QUOTA, TRANSIENT }

object ExternalFailureClassifier {
    /** [apiCode] el campo `code` del cuerpo (-1 si no hay); [httpCode] null si no hubo respuesta. */
    fun classify(httpCode: Int?, apiCode: Int, temporaryNotice: Boolean = false): ExternalFailure {
        if (httpCode == null) return ExternalFailure.TRANSIENT
        when (apiCode) {
            1 -> return if (temporaryNotice) ExternalFailure.TRANSIENT else ExternalFailure.NONE
            2 -> return ExternalFailure.INVALID_CREDENTIAL
            3 -> return ExternalFailure.INVALID_INPUT
            4 -> return ExternalFailure.ACCESS_RESTRICTED
            5 -> return ExternalFailure.TRANSIENT
            6 -> return ExternalFailure.RATE_LIMITED
            7 -> return ExternalFailure.DAILY_QUOTA
        }
        return when (httpCode) {
            in 200..299 -> if (apiCode > 0) ExternalFailure.TRANSIENT else ExternalFailure.NONE
            401 -> ExternalFailure.INVALID_CREDENTIAL
            403 -> ExternalFailure.ACCESS_RESTRICTED
            400 -> ExternalFailure.INVALID_INPUT
            // 429 sin code 7: la documentación identifica la cuota diaria por code 7, no por el HTTP.
            // Sin ese código es una limitación temporal (Retry-After o espera creciente).
            429 -> ExternalFailure.RATE_LIMITED
            503 -> ExternalFailure.RATE_LIMITED
            else -> ExternalFailure.TRANSIENT
        }
    }
}

/** Cabecera HTTP Retry-After (RFC 9110): segundos o fecha HTTP. */
object RetryAfter {
    /** Más de dos días no es una espera creíble: se ignora. */
    const val MAX_MS = 48L * 60 * 60 * 1000

    /** Milisegundos a esperar, o null si falta o no es válida. Una fecha pasada vale 0. */
    fun parse(value: String?, nowCivilMs: Long): Long? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        if (v.all(Char::isDigit)) {
            val seconds = v.toLongOrNull() ?: return null
            return (seconds * 1000).takeIf { seconds <= MAX_MS / 1000 }
        }
        val at = try { ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } catch (_: Exception) { return null }
        return (at - nowCivilMs).coerceAtLeast(0).takeIf { it <= MAX_MS }
    }
}

/**
 * Una pausa global de consultas de red, asociada a la credencial ([fingerprint], ver
 * VerificationConfig.credentialFingerprint). [untilCivilMs]/[untilElapsedMs] null = hasta que cambie
 * la configuración.
 */
data class VerificationPauseState(
    val kind: ExternalFailure,
    val fingerprint: String,
    val untilCivilMs: Long?,
    val untilElapsedMs: Long?,
    val bootId: String?,
    val maxMs: Long?
) {
    fun serialize() = listOf(kind.name, fingerprint, untilCivilMs ?: "", untilElapsedMs ?: "", bootId ?: "", maxMs ?: "").joinToString("|")

    companion object {
        fun parse(text: String?): VerificationPauseState? {
            val p = text?.split("|")?.takeIf { it.size == 6 } ?: return null
            return try {
                VerificationPauseState(ExternalFailure.valueOf(p[0]), p[1].ifEmpty { return null }, p[2].ifEmpty { null }?.toLong(),
                    p[3].ifEmpty { null }?.toLong(), p[4].ifEmpty { null }, p[5].ifEmpty { null }?.toLong())
            } catch (_: Exception) { null }
        }
    }
}

interface VerificationPauseStore {
    fun load(): VerificationPauseState?
    fun save(state: VerificationPauseState?)
}

class InMemoryVerificationPauseStore : VerificationPauseStore {
    private var s: VerificationPauseState? = null
    override fun load() = s
    override fun save(state: VerificationPauseState?) { s = state }
}

/**
 * 3.0 (V1) — Pausa GLOBAL de las consultas a OpenCellID. Antes un ERROR se reintentaba al minuto por
 * celda, también con la clave inválida o el cupo agotado, y cambiar de celda volvía a consultar.
 *
 *  - Clave inválida o acceso restringido: sin consultas hasta que cambie la credencial.
 *  - Demasiadas peticiones: Retry-After si es válido; si no, espera creciente (1 min, 2, 4… hasta 30).
 *  - Cupo diario: Retry-After si es válido; si no, 24 h (la documentación da un cupo por día y no
 *    dice a qué hora se reinicia: no se supone medianoche UTC).
 *  - Fallo transitorio (red, Tor caído, 5xx): espera creciente (30 s, 1 min… hasta 15), solo en
 *    memoria; cualquier respuesta la reinicia.
 *  - Entrada inválida: no es global (afecta a esa celda); la decide el llamador.
 * Las pausas de credencial, límite y cupo se guardan y sobreviven al reinicio: en el mismo arranque
 * se mide con el reloj monotónico; tras otro arranque, con el civil, sin pasar nunca del máximo de
 * su clase (un reloj atrasado no la alarga sin límite). Solo aplican a la misma credencial: una
 * clave nueva no hereda la pausa de la anterior. Seguro entre hilos.
 */
class VerificationPauseGate(private val store: VerificationPauseStore = InMemoryVerificationPauseStore()) {
    private var persisted: VerificationPauseState? = store.load()
    private var transient: VerificationPauseState? = null
    private var rateStreak = 0
    private var transientStreak = 0

    /** La pausa que impide consultar ahora con [fingerprint], o null. */
    @Synchronized fun blocking(fingerprint: String, clock: MobilityClock): VerificationPauseState? {
        persisted?.let { p ->
            if (p.fingerprint == fingerprint) {
                val live = refresh(p, clock)
                if (live != null) return live
                persisted = null; store.save(null)
            }
        }
        transient?.let { p ->
            if (p.fingerprint == fingerprint && refresh(p, clock) != null) return p
            if (refresh(p, clock) == null) transient = null
        }
        return null
    }

    /**
     * Resultado de una consulta HECHA con la credencial [fingerprint] y la configuración aún vigente
     * (el llamador descarta las respuestas de una configuración anterior). Devuelve la pausa nueva.
     */
    @Synchronized fun onResult(fingerprint: String, failure: ExternalFailure, retryAfterMs: Long?, clock: MobilityClock): VerificationPauseState? =
        when (failure) {
            // Hubo respuesta interpretable: la red funciona y no hubo límite.
            ExternalFailure.NONE -> answered()
            ExternalFailure.INVALID_INPUT -> answered()
            ExternalFailure.INVALID_CREDENTIAL -> untilConfigChange(failure, fingerprint, clock)
            ExternalFailure.ACCESS_RESTRICTED -> untilConfigChange(failure, fingerprint, clock)
            ExternalFailure.RATE_LIMITED -> {
                transientStreak = 0; rateStreak++
                persist(timed(failure, fingerprint, retryAfterMs ?: backoff(RATE_BASE_MS, rateStreak, RATE_MAX_MS), clock))
            }
            ExternalFailure.DAILY_QUOTA -> {
                transientStreak = 0
                persist(timed(failure, fingerprint, retryAfterMs ?: DAILY_QUOTA_MS, clock))
            }
            ExternalFailure.TRANSIENT -> {
                transientStreak++
                timed(failure, fingerprint, backoff(TRANSIENT_BASE_MS, transientStreak, TRANSIENT_MAX_MS), clock).also { transient = it }
            }
        }

    /**
     * Cambió la configuración (clave o ruta). La pausa transitoria y las rachas se reinician (otra
     * ruta puede funcionar); las guardadas solo se borran si son de otra credencial.
     */
    @Synchronized fun onConfigChanged(fingerprint: String) {
        transient = null; transientStreak = 0; rateStreak = 0
        if (persisted != null && persisted?.fingerprint != fingerprint) { persisted = null; store.save(null) }
    }

    private fun answered(): VerificationPauseState? {
        transientStreak = 0; rateStreak = 0; transient = null
        return null
    }

    private fun untilConfigChange(kind: ExternalFailure, fp: String, c: MobilityClock) =
        persist(VerificationPauseState(kind, fp, null, null, c.bootId, null))

    private fun persist(p: VerificationPauseState): VerificationPauseState {
        // Consultas concurrentes: si ya hay una pausa igual o más larga para la misma credencial, se queda.
        val old = persisted
        if (old != null && old.fingerprint == p.fingerprint && old.bootId == p.bootId && endsAfter(old, p)) return old
        persisted = p; store.save(p)
        return p
    }

    private fun endsAfter(a: VerificationPauseState, b: VerificationPauseState): Boolean {
        val ea = a.untilElapsedMs ?: return true
        val eb = b.untilElapsedMs ?: return false
        return ea >= eb
    }

    private fun timed(kind: ExternalFailure, fp: String, ms: Long, c: MobilityClock): VerificationPauseState {
        val max = maxFor(kind, ms)
        val wait = ms.coerceIn(0, max)
        return VerificationPauseState(kind, fp, c.civilMs + wait, c.elapsedMs + wait, c.bootId, max)
    }

    /** El máximo que puede durar la pausa: el de la clase o el Retry-After aceptado. */
    private fun maxFor(kind: ExternalFailure, ms: Long) = when (kind) {
        ExternalFailure.DAILY_QUOTA -> maxOf(ms, DAILY_QUOTA_MS)
        ExternalFailure.RATE_LIMITED -> maxOf(ms, RATE_MAX_MS)
        else -> TRANSIENT_MAX_MS
    }.coerceAtMost(RetryAfter.MAX_MS)

    /** La pausa si sigue activa (acotada si hace falta), o null si ya terminó. */
    private fun refresh(p: VerificationPauseState, c: MobilityClock): VerificationPauseState? {
        if (p.untilElapsedMs == null && p.untilCivilMs == null) return p          // hasta cambiar la credencial
        if (p.bootId != null && p.bootId == c.bootId && p.untilElapsedMs != null) {
            return p.takeIf { c.elapsedMs < p.untilElapsedMs }
        }
        // Otro arranque, o sin número de arranque: reloj civil, nunca más allá del máximo de la clase.
        val remaining = (p.untilCivilMs ?: return null) - c.civilMs
        if (remaining <= 0) return null
        val max = p.maxMs ?: return p
        if (remaining <= max && p.bootId == c.bootId) return p
        // Se reancla al arranque actual: desde aquí se mide con el reloj monotónico.
        val wait = remaining.coerceAtMost(max)
        val anchored = p.copy(untilCivilMs = c.civilMs + wait, untilElapsedMs = c.elapsedMs + wait, bootId = c.bootId)
        if (persisted == p) { persisted = anchored; store.save(anchored) }
        if (transient == p) transient = anchored
        return anchored
    }

    private fun backoff(base: Long, streak: Int, max: Long) = (base shl (streak - 1).coerceIn(0, 20)).coerceAtMost(max)

    companion object {
        const val RATE_BASE_MS = 60_000L
        const val RATE_MAX_MS = 30 * 60_000L
        const val TRANSIENT_BASE_MS = 30_000L
        const val TRANSIENT_MAX_MS = 15 * 60_000L
        /** Cupo diario agotado sin Retry-After: un día (no se supone la hora de reinicio). */
        const val DAILY_QUOTA_MS = 24 * 60 * 60_000L
        /** Entrada inválida: esa celda se reintenta a la hora, sin pausa global. */
        const val INVALID_INPUT_RETRY_MS = 60 * 60_000L
        /** Preferencia interna; no viaja en la copia. Guarda la huella de la credencial, nunca la clave. */
        const val PREF_KEY = "opencellid_pause"

        /** Línea del terminal, sin URL, clave ni huella. */
        fun describe(p: VerificationPauseState, clock: MobilityClock): String {
            val minutes = p.untilElapsedMs?.let { ((it - clock.elapsedMs).coerceAtLeast(0) + 59_999) / 60_000 }
            return when (p.kind) {
                ExternalFailure.INVALID_CREDENTIAL -> "OpenCellID en pausa: la API key no es válida. No se consultará hasta que la cambies en Ajustes."
                ExternalFailure.ACCESS_RESTRICTED -> "OpenCellID en pausa: la API key no tiene acceso (código 4). No se consultará hasta que cambies la configuración."
                ExternalFailure.RATE_LIMITED -> "OpenCellID en pausa por demasiadas peticiones: se reanudará en $minutes min. No dice nada de la antena."
                ExternalFailure.DAILY_QUOTA -> "OpenCellID en pausa: cupo diario agotado. Se reanudará en $minutes min. No dice nada de la antena."
                else -> "OpenCellID sin respuesta (red, proxy o servidor): se reintentará en $minutes min."
            }
        }
    }
}
