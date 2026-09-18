package com.alexisgordr.icdetector.network

import com.alexisgordr.icdetector.core.VerificationDecision
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.VerificationStatus
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Cliente de WiGLE.
 *
 * Igual que [OpenCellIdClient]: aquí se construye la consulta y se extraen campos; el significado
 * de cada respuesta lo decide [VerificationDecision], que es puro y está probado aparte.
 *
 * ── LOS PARÁMETROS ───────────────────────────────────────────────────────────────────────────
 * Hasta v2.1 la consulta se construía con `mcc`, `mnc`, `lac` y `cellid`. **WiGLE no tiene esos
 * parámetros** en `/api/v2/cell/search`: los suyos son el operador (MCC y MNC pegados, "21401"), el
 * área y la Cell ID. Un parámetro desconocido no da error — se ignora en silencio —, así que la app
 * llevaba meses lanzando una búsqueda SIN FILTRAR y quedándose con el primer resultado del índice,
 * que no tenía nada que ver con la celda preguntada. De ahí salían las coordenadas a cientos de
 * kilómetros, y de ahí venían las verificaciones que resultaron no ser reales.
 *
 * Los nombres usados por el cliente Android oficial de WiGLE son `cell_op`, `cell_net` y
 * `cell_id`. La respuesta devuelve la identidad empaquetada en `results[].id`, con formato
 * `MCC+MNC_AREA_CELLID`; ese campo se vuelve a interpretar y se compara antes de verificar.
 *
 * ── LA COMPROBACIÓN QUE FALTABA ──────────────────────────────────────────────────────────────
 * Ninguna respuesta se da por buena sin comprobar **a qué celda corresponde**. Es la defensa que
 * habría evitado el problema entero sin depender de acertar con el nombre del parámetro.
 */
object WigleClient {

    /** Clasificación pura para poder probar la cuota sin realizar peticiones reales. */
    internal fun isRateLimited(httpCode: Int, message: String?): Boolean =
        httpCode == 429 ||
            message?.contains("too many queries", ignoreCase = true) == true ||
            message?.contains("rate limit", ignoreCase = true) == true ||
            message?.contains("quota", ignoreCase = true) == true

    fun tryWigleSync(
        cell: CellData,
        wigleApiName: String,
        wigleApiToken: String,
        isProxyEnabled: Boolean,
        client: OkHttpClient
    ): VerificationOutcome {
        val currentClient = if (isProxyEnabled) {
            client.newBuilder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050)))
                .build()
        } else {
            client
        }

        return try {
            val credentials = okhttp3.Credentials.basic(wigleApiName.trim(), wigleApiToken.trim())

            // Operador tal y como lo identifica WiGLE: MCC y MNC concatenados, conservando el cero
            // a la izquierda que reporta el módem (214 + 01 = "21401", no "2141").
            val operador = "${cell.mcc}${cell.mnc}"
            val url = buildString {
                append("https://api.wigle.net/api/v2/cell/search")
                append("?cell_op=").append(operador)
                append("&cell_net=").append(cell.tac)
                append("&cell_id=").append(cell.cellId)
                append("&resultsPerPage=5")
            }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", credentials)
                .header("User-Agent", "ICdetection/2.1 (Android)")
                .header("Accept", "application/json")
                .build()
            currentClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                val json = runCatching { JSONObject(body) }.getOrNull()
                val crudo = body.take(160).replace(Regex("\\s+"), " ")
                val mensajeApi = json?.optTexto("message")
                val rateLimited = isRateLimited(response.code, mensajeApi)

                // WiGLE suele contestar 429 sin Retry-After, pero si lo proporciona respetamos
                // el número de segundos. La pausa de seguridad cuando falta se decide en el
                // servicio, que además la persiste entre reinicios.
                val retryAfterMillis = response.header("Retry-After")
                    ?.trim()
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?.let { seconds -> seconds.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L }

                val results = json?.optJSONArray("results")
                val esperada = VerificationDecision.Identity(
                    mcc = cell.mcc, mnc = cell.mnc, area = cell.tac,
                    cellId = cell.cellId, radio = cell.radioTech
                )

                // Primer registro cuya identidad coincide con la preguntada, si lo hay.
                val coincidente = (0 until (results?.length() ?: 0))
                    .asSequence()
                    .mapNotNull { results?.optJSONObject(it) }
                    .firstOrNull { VerificationDecision.identityMatches(esperada, leerIdentidad(it)) }

                val veredicto = VerificationDecision.forWigle(
                    httpCode = response.code,
                    success = json?.optBoolean("success", false) ?: false,
                    resultCount = results?.length() ?: 0,
                    identityOk = coincidente != null,
                    mensajeApi = mensajeApi,
                    crudo = crudo
                )

                VerificationOutcome(
                    status = veredicto.status,
                    source = VerificationSource.WIGLE,
                    record = if (veredicto.status == VerificationStatus.VERIFIED) coincidente else null,
                    reason = veredicto.reason,
                    failure = if (rateLimited) VerificationFailure.RATE_LIMITED else VerificationFailure.NONE,
                    retryAfterMillis = retryAfterMillis
                )
            }
        } catch (_: Exception) {
            VerificationOutcome(
                status = VerificationStatus.ERROR,
                source = VerificationSource.WIGLE,
                reason = "WiGLE: sin conexión o respuesta ilegible."
            )
        }
    }

    /**
     * Identidad de un registro de WiGLE.
     *
     * La API celular oficial devuelve `id`, `trilat`, `trilong`, `ssid`, `gentype`, `attributes`
     * y `channel`. Se conserva compatibilidad defensiva con variantes antiguas, pero nunca se da
     * por buena la ausencia de una identidad comprobable.
     */
    private fun leerIdentidad(r: JSONObject): VerificationDecision.Reported {
        VerificationDecision.reportedFromWigleId(
            id = r.optTexto("id") ?: r.optTexto("netid"),
            radio = r.optTexto("gentype") ?: r.optTexto("type") ?: r.optTexto("radio")
        )?.let { return it }

        val operador = r.optTexto("operator") ?: r.optTexto("cell_op") ?: r.optTexto("cellOp")
        // WiGLE da el operador como MCC+MNC pegados: los tres primeros dígitos son el MCC.
        val mcc = operador?.takeIf { it.length >= 4 }?.substring(0, 3)
        val mnc = operador?.takeIf { it.length >= 4 }?.substring(3)
        return VerificationDecision.Reported(
            mcc = r.optTexto("mcc") ?: mcc,
            mnc = r.optTexto("mnc") ?: mnc,
            area = r.optTexto("lac") ?: r.optTexto("tac") ?: r.optTexto("cell_net") ?: r.optTexto("cellNet"),
            cellId = r.optTexto("cellid") ?: r.optTexto("cellId") ?: r.optTexto("cid")
                ?: r.optTexto("cell_id") ?: r.optTexto("bsid"),
            radio = RadioTech.fromApi(r.optTexto("gentype") ?: r.optTexto("type") ?: r.optTexto("radio"))
        )
    }
}
