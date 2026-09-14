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

    fun tryOpenCellIdSyncWithData(
        cell: CellData,
        openCellIdKey: String,
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

        val radioParam = cell.radioTech.apiName?.let { "&radio=$it" } ?: ""
        val url = "https://opencellid.org/cell/get?key=$openCellIdKey&mcc=${cell.mcc}&mnc=${cell.mnc}" +
            "&lac=${cell.tac}&cellid=${cell.cellId}$radioParam&format=json"
        val request = Request.Builder().url(url).build()

        return try {
            currentClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                val json = runCatching { JSONObject(body) }.getOrNull()

                // Principio de la respuesta cruda, para el terminal. La clave viaja en la URL, que
                // NO se registra; el cuerpo no la contiene.
                val crudo = body.take(160).replace(Regex("\\s+"), " ")

                val hasCoords = json != null && json.has("lat") && json.has("lon")
                val identityOk = hasCoords && VerificationDecision.identityMatches(
                    esperada = VerificationDecision.Identity(
                        mcc = cell.mcc, mnc = cell.mnc, area = cell.tac,
                        cellId = cell.cellId, radio = cell.radioTech
                    ),
                    recibida = VerificationDecision.Reported(
                        mcc = json!!.optTexto("mcc"),
                        mnc = json.optTexto("mnc"),
                        // En LTE/NR OpenCellID puede devolver el área como `tac`; en GSM/UMTS como
                        // `lac`. Es el mismo campo con dos nombres según la tecnología.
                        area = json.optTexto("tac") ?: json.optTexto("lac"),
                        cellId = json.optTexto("cellid") ?: json.optTexto("cid"),
                        radio = RadioTech.fromApi(json.optTexto("radio"))
                    )
                )

                val veredicto = VerificationDecision.forOpenCellId(
                    httpCode = response.code,
                    hasCoordinates = hasCoords,
                    errorCode = json?.optInt("code", -1) ?: -1,
                    identityOk = identityOk,
                    crudo = crudo
                )

                VerificationOutcome(
                    status = veredicto.status,
                    source = VerificationSource.OPENCELLID,
                    record = if (veredicto.status == VerificationStatus.VERIFIED) json else null,
                    reason = veredicto.reason
                )
            }
        } catch (_: Exception) {
            // IOException (red) o JSONException (respuesta malformada): no se sabe.
            VerificationOutcome(
                status = VerificationStatus.ERROR,
                source = VerificationSource.OPENCELLID,
                reason = "OpenCellID: sin conexión o respuesta ilegible."
            )
        }
    }
}

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
