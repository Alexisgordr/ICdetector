package com.alexisgordr.icdetector.network

import com.alexisgordr.icdetector.core.ExternalFailure
import com.alexisgordr.icdetector.core.ExternalFailureClassifier
import com.alexisgordr.icdetector.core.RetryAfter
import com.alexisgordr.icdetector.core.VerificationDecision
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.VerificationStatus
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Cliente de OpenCellID.
 *
 * Aquí solo se construye la consulta y se extraen campos. **Qué significa cada respuesta lo decide
 * [VerificationDecision]**, que es código puro y tiene su propio banco de pruebas; esta clase no
 * toma decisiones de semántica.
 *
 * ── LA CONSULTA ──────────────────────────────────────────────────────────────────────────────
 * Parámetros documentados: `key`, `mcc`, `mnc`, `lac`, `cellid`, `radio` y `format`. El `radio` se
 * envía siempre que se conozca la tecnología: sin él, la API puede devolver el primer registro que
 * cuadre con el resto de campos, y una misma Cell ID puede existir en tecnologías distintas.
 *
 * ── LA RESPUESTA ─────────────────────────────────────────────────────────────────────────────
 * OpenCellID devuelve `mcc`, `mnc`, `lac`, `cellid` y `radio` junto a la coordenada, así que la
 * identidad se comprueba **entera**, no solo la Cell ID. Comprobar un campo cuando puedes comprobar
 * cinco es dejar cuatro puertas abiertas.
 */
object OpenCellIdClient {

    /** Proxy SOCKS de Orbot/Tor. */
    val TOR_PROXY = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050))

    /**
     * 3.0 (V4) — Cliente para una configuración. Con proxy, el proxy es EXPLÍCITO: OkHttp no prueba
     * otra ruta si no está disponible (sin ProxySelector ni salida directa de reserva); la petición
     * falla. Sin proxy, `Proxy.NO_PROXY`: tampoco hereda un proxy del sistema por sorpresa.
     */
    fun clientFor(config: com.alexisgordr.icdetector.core.VerificationConfig, base: OkHttpClient): OkHttpClient =
        base.newBuilder().proxy(if (config.proxyEnabled) TOR_PROXY else Proxy.NO_PROXY).build()

    /**
     * Consulta con una INSTANTÁNEA de la configuración ([config]): clave y ruta de la misma versión
     * durante toda la consulta. [holder] (si se da) registra la llamada para cancelarla si cambia la
     * ruta, y comprueba justo antes de conectar que la configuración sigue vigente.
     */
    fun tryOpenCellIdSyncWithData(
        cell: CellData,
        config: com.alexisgordr.icdetector.core.VerificationConfig,
        client: OkHttpClient,
        holder: com.alexisgordr.icdetector.core.VerificationConfigHolder? = null
    ): VerificationOutcome {
        if (holder != null && !holder.isCurrent(config)) return configChanged()
        val currentClient = clientFor(config, client)
        val openCellIdKey = config.apiKey

        return try {
            val cleanKey = openCellIdKey.trim()
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("opencellid.org")
                .addPathSegments("cell/get")
                .addQueryParameter("key", cleanKey)
                .addQueryParameter("mcc", cell.mcc)
                .addQueryParameter("mnc", cell.mnc)
                .addQueryParameter("lac", cell.tac)
                .addQueryParameter("cellid", cell.cellId)
                .apply { cell.radioTech.apiName?.let { addQueryParameter("radio", it) } }
                .addQueryParameter("format", "json")
                .build()
            val request = Request.Builder().url(url).build()
            val call = currentClient.newCall(request)
            val untrack = holder?.track(config) { call.cancel() } ?: {}
            try {
            if (call.isCanceled()) return configChanged()
            call.execute().use { response ->
                val body = response.body.string()
                val json = runCatching { JSONObject(body) }.getOrNull()

                // Principio de la respuesta cruda, para el terminal. La clave viaja en la URL, que
                // NO se registra; el cuerpo no la contiene.
                // El error "API Key not known" puede repetir literalmente la clave enviada.
                // Nunca dejarla en el terminal ni en un informe compartido.
                val cuerpoSeguro = if (cleanKey.isNotEmpty()) {
                    body.replace(cleanKey, "[REDACTED]")
                } else body
                val crudo = cuerpoSeguro
                    .take(160)
                    .replace(Regex("\\s+"), " ")

                val hasCoords = json != null && json.has("lat") && json.has("lon")
                val identityOk = hasCoords && VerificationDecision.identityMatches(
                    esperada = VerificationDecision.Identity(
                        mcc = cell.mcc, mnc = cell.mnc, area = cell.tac,
                        cellId = cell.cellId, radio = cell.radioTech
                    ),
                    recibida = VerificationDecision.reportedFromOpenCellId(
                        mcc = json!!.optTexto("mcc"),
                        mnc = json.optTexto("mnc"),
                        // `lac` es el parámetro canónico también para TAC. `tac`/`cid` pueden
                        // aparecer como auxiliares a cero y solo son fallback si falta el canónico.
                        lac = json.optTexto("lac"),
                        tac = json.optTexto("tac"),
                        cellId = json.optTexto("cellid"),
                        cid = json.optTexto("cid"),
                        radio = json.optTexto("radio")
                    )
                )

                val mensajeApi = listOfNotNull(
                    json?.optTexto("error"), json?.optTexto("message"), json?.optTexto("notice")
                ).joinToString(" ").takeIf { it.isNotBlank() }
                val veredicto = VerificationDecision.forOpenCellId(
                    httpCode = response.code,
                    hasCoordinates = hasCoords,
                    errorCode = json?.optInt("code", -1) ?: -1,
                    identityOk = identityOk,
                    mensajeApi = mensajeApi,
                    crudo = crudo
                )

                // 3.0 (V1) — Clase del fallo por código HTTP y código de la API (documentados), no por
                // el texto. Retry-After solo cuenta en los límites, y solo si es válido.
                val apiCode = json?.optInt("code", -1) ?: -1
                val failure = ExternalFailureClassifier.classify(response.code, apiCode,
                    temporaryNotice = apiCode == VerificationDecision.OCID_CELL_NOT_FOUND && VerificationDecision.isTemporaryNotice(mensajeApi))
                val retryAfter = if (failure == ExternalFailure.RATE_LIMITED || failure == ExternalFailure.DAILY_QUOTA)
                    RetryAfter.parse(response.header("Retry-After"), System.currentTimeMillis()) else null

                VerificationOutcome(
                    status = veredicto.status,
                    source = VerificationSource.OPENCELLID,
                    record = if (veredicto.status == VerificationStatus.VERIFIED) json else null,
                    reason = veredicto.reason,
                    externalFailure = failure,
                    retryAfterMillis = retryAfter
                )
            }
            } finally {
                untrack()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // 3.0 (V4) — Cancelada por un cambio de ruta, o la configuración cambió mientras tanto:
            // no es un fallo de red de la configuración nueva.
            if (holder != null && !holder.isCurrent(config)) return configChanged()
            // IOException (red) o JSONException (respuesta malformada): no se sabe.
            VerificationOutcome(
                status = VerificationStatus.ERROR,
                source = VerificationSource.OPENCELLID,
                reason = "OpenCellID: sin conexión o respuesta ilegible.",
                externalFailure = ExternalFailure.TRANSIENT
            )
        }
    }
}

private fun configChanged() = VerificationOutcome(
    status = VerificationStatus.ERROR,
    source = VerificationSource.OPENCELLID,
    reason = "OpenCellID: consulta anulada porque cambió la configuración (clave o ruta).",
    failure = VerificationFailure.CONFIG_CHANGED
)

/**
 * Valor de un campo como texto, sea número o cadena en el JSON, o null si no está.
 *
 * Las dos APIs mezclan tipos entre respuestas (`"cellid": 12345` y `"cellid": "12345"` son ambas
 * reales), así que leerlo con `optString` y comparar a ciegas daría diferencias donde no las hay.
 */
internal fun JSONObject.optTexto(campo: String): String? {
    if (!has(campo) || isNull(campo)) return null
    return when (val v = opt(campo)) {
        is String -> v.takeIf { it.isNotBlank() }
        is Number -> v.toString()
        else -> v?.toString()?.takeIf { it.isNotBlank() }
    }
}
