package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.VerificationStatus

/**
 * Qué significa cada respuesta de WiGLE / OpenCellID. **Sin red, sin JSON, sin Android.**
 *
 * ── POR QUÉ ESTÁ AQUÍ Y NO DENTRO DE LOS CLIENTES ────────────────────────────────────────────
 * El fallo más caro de este proyecto no estuvo en pedir mal los datos —que también—, sino en
 * **interpretar mal la respuesta**: una búsqueda sin filtrar devolvía una antena cualquiera y la
 * app la daba por verificada. Aquello sobrevivió meses porque esa decisión vivía enredada entre
 * okhttp y `JSONObject`, donde no había forma de probarla sin levantar la red.
 *
 * Toda la semántica vive ahora en funciones puras sobre tipos primitivos. Los clientes se limitan
 * a extraer campos del JSON y preguntar aquí. Eso permite tener un banco de pruebas con las
 * respuestas reales de las dos APIs —cuota agotada, identidad cambiada, resultado vacío, key
 * inválida— comprobando exactamente la decisión que se tomó con cada una.
 *
 * ── LA REGLA QUE ORDENA TODO ─────────────────────────────────────────────────────────────────
 * [VerificationStatus.NOT_FOUND] es una afirmación sobre la ANTENA y solo se emite ante una
 * negativa explícita y fiable. Si la respuesta llega pero no convence, es
 * [VerificationStatus.REJECTED] — una afirmación sobre la RESPUESTA. Si no hay respuesta
 * interpretable, es [VerificationStatus.ERROR]. Un HTTP 404 nunca es "la antena no existe": es un
 * endpoint que no está donde se esperaba.
 */
object VerificationDecision {

    /** Código de error de OpenCellID que significa, literalmente, "cell not found". */
    const val OCID_CELL_NOT_FOUND = 1
    /** API key inválida. */
    const val OCID_INVALID_KEY = 2
    /** Demasiadas peticiones seguidas. */
    const val OCID_TOO_MANY_REQUESTS = 6
    /** Límite diario agotado (1000 consultas en la key gratuita). */
    const val OCID_DAILY_LIMIT = 7

    /** Lo que se preguntó. Todo lo que hay que comprobar en la respuesta. */
    data class Identity(
        val mcc: String,
        val mnc: String,
        val area: String,      // TAC en LTE/NR, LAC en GSM/UMTS
        val cellId: String,
        val radio: RadioTech = RadioTech.UNKNOWN
    )

    /** Lo que vino en la respuesta. Todo nulo salvo lo que la API haya incluido. */
    data class Reported(
        val mcc: String? = null,
        val mnc: String? = null,
        val area: String? = null,
        val cellId: String? = null,
        val radio: RadioTech = RadioTech.UNKNOWN
    )

    /** Veredicto y el motivo en texto llano, listo para el terminal. */
    data class Verdict(val status: VerificationStatus, val reason: String?)

    /**
     * ¿La respuesta corresponde a la celda que se preguntó?
     *
     * Reglas, y el orden importa:
     *  - Un campo **ausente** en la respuesta no se comprueba. Las APIs no devuelven siempre los
     *    mismos campos y exigir lo que no mandan sería rechazar respuestas buenas.
     *  - Un campo **presente** que no coincide invalida la respuesta entera. Basta uno.
     *  - Los identificadores numéricos se comparan **como números**: OpenCellID devuelve el MNC sin
     *    el cero a la izquierda ("7" donde el módem dice "07"), y comparar cadenas daría por
     *    distintas dos cosas idénticas.
     *  - La **Cell ID es obligatoria**: si la respuesta no la trae, no hay forma de saber de quién
     *    habla y no verifica nada. Es exactamente el agujero por el que se colaron meses de
     *    verificaciones falsas.
     */
    fun identityMatches(esperada: Identity, recibida: Reported): Boolean {
        val cidRecibida = recibida.cellId ?: return false
        if (!mismoNumero(esperada.cellId, cidRecibida)) return false

        if (recibida.mcc != null && !mismoNumero(esperada.mcc, recibida.mcc)) return false
        if (recibida.mnc != null && !mismoNumero(esperada.mnc, recibida.mnc)) return false
        if (recibida.area != null && !mismoNumero(esperada.area, recibida.area)) return false

        // El radio solo se compara si las dos partes lo conocen. UNKNOWN no es un desacuerdo.
        if (esperada.radio != RadioTech.UNKNOWN &&
            recibida.radio != RadioTech.UNKNOWN &&
            esperada.radio != recibida.radio
        ) return false

        return true
    }

    /** Compara dos identificadores como números; si alguno no lo es, compara el texto ya recortado. */
    private fun mismoNumero(a: String, b: String): Boolean {
        val na = a.trim().toLongOrNull()
        val nb = b.trim().toLongOrNull()
        return if (na != null && nb != null) na == nb else a.trim() == b.trim()
    }

    /**
     * Veredicto para una respuesta de **OpenCellID**.
     *
     * @param httpCode código HTTP.
     * @param hasCoordinates si el cuerpo trae `lat` y `lon`.
     * @param errorCode el campo `code` del cuerpo, o -1 si no viene.
     * @param identityOk resultado de [identityMatches] (irrelevante si no hay coordenadas).
     * @param crudo principio del cuerpo, para el terminal.
     */
    fun forOpenCellId(
        httpCode: Int,
        hasCoordinates: Boolean,
        errorCode: Int,
        identityOk: Boolean,
        crudo: String = ""
    ): Verdict {
        // ── ORDEN DE LAS COMPROBACIONES ──────────────────────────────────────────────────────
        // Primero el transporte, después el error de la API, y solo al final el contenido.
        //
        // Antes esto empezaba mirando si había coordenadas, y eso abría una puerta que no debería
        // existir: un HTTP 401, 404 o 500 que aun así trajera un `lat`/`lon` con la identidad
        // correcta se daba por VERIFIED. No es un caso que se espere ver — pero una máquina de
        // estados defensiva no se apoya en lo que espera ver. VERIFIED exige ahora las cuatro
        // cosas a la vez: HTTP 2xx, sin código de error, coordenada presente e identidad
        // compatible.
        if (httpCode !in 200..299) {
            return when (httpCode) {
                429 -> Verdict(VerificationStatus.ERROR,
                    "OpenCellID: límite diario de consultas agotado. NO significa que la antena no exista; se reintentará. Respuesta: $crudo")
                503 -> Verdict(VerificationStatus.ERROR,
                    "OpenCellID: demasiadas peticiones seguidas. Se reintentará.")
                401, 403 -> Verdict(VerificationStatus.ERROR,
                    "OpenCellID: la API key no es válida o no tiene permiso. Revísala en Ajustes. Respuesta: $crudo")
                // Un 404 es un endpoint que no responde donde se esperaba, no una antena
                // inexistente. La forma documentada de decir "no la tengo" es HTTP 200 con code 1.
                404 -> Verdict(VerificationStatus.ERROR,
                    "OpenCellID: HTTP 404 (endpoint no encontrado). No dice nada sobre la antena.")
                else -> Verdict(VerificationStatus.ERROR,
                    "OpenCellID: respuesta no interpretable (HTTP $httpCode). Respuesta: $crudo")
            }
        }

        // HTTP correcto, pero la API declara un error en el cuerpo.
        if (errorCode > 0) {
            return when (errorCode) {
                // La ÚNICA negativa fiable de OpenCellID. Llega con HTTP 200, de ahí que el código
                // HTTP por sí solo no valga para decidir nada aquí.
                OCID_CELL_NOT_FOUND -> Verdict(VerificationStatus.NOT_FOUND,
                    "OpenCellID: esta celda no está en su base. Respuesta: $crudo")
                OCID_DAILY_LIMIT -> Verdict(VerificationStatus.ERROR,
                    "OpenCellID: límite diario de consultas agotado. NO significa que la antena no exista; se reintentará. Respuesta: $crudo")
                OCID_TOO_MANY_REQUESTS -> Verdict(VerificationStatus.ERROR,
                    "OpenCellID: demasiadas peticiones seguidas. Se reintentará.")
                OCID_INVALID_KEY -> Verdict(VerificationStatus.ERROR,
                    "OpenCellID: la API key no es válida o no tiene permiso. Revísala en Ajustes. Respuesta: $crudo")
                else -> Verdict(VerificationStatus.ERROR,
                    "OpenCellID: error $errorCode de la API. Respuesta: $crudo")
            }
        }

        if (hasCoordinates) {
            return if (identityOk) {
                Verdict(VerificationStatus.VERIFIED, null)
            } else {
                Verdict(
                    VerificationStatus.REJECTED,
                    "OpenCellID: la respuesta no es de la celda preguntada. No verifica nada, y tampoco " +
                        "demuestra que la antena no esté. Respuesta: $crudo"
                )
            }
        }

        // HTTP 2xx, sin error declarado y sin coordenada: la API contestó algo que no permite
        // ni confirmar ni desmentir. No se sabe.
        return Verdict(VerificationStatus.ERROR,
            "OpenCellID: respuesta sin coordenadas y sin código de error. Respuesta: $crudo")
    }

    /**
     * Veredicto para una respuesta de **WiGLE**.
     *
     * @param httpCode código HTTP.
     * @param success el campo `success` del cuerpo (WiGLE contesta 200 con `success:false` cuando
     *   se agota la cuota o la cuenta no tiene acceso al endpoint de celdas).
     * @param resultCount número de registros devueltos.
     * @param identityOk si alguno de esos registros es la celda preguntada.
     */
    fun forWigle(
        httpCode: Int,
        success: Boolean,
        resultCount: Int,
        identityOk: Boolean,
        mensajeApi: String? = null,
        crudo: String = ""
    ): Verdict {
        if (httpCode in 200..299 && success) {
            return when {
                // Consulta válida, cero resultados: WiGLE no tiene esta celda. Negativa fiable.
                resultCount == 0 ->
                    Verdict(VerificationStatus.NOT_FOUND, "WiGLE: 0 resultados para esta celda. Respuesta: $crudo")

                identityOk -> Verdict(VerificationStatus.VERIFIED, null)

                // Vinieron registros, pero ninguno es la celda preguntada. Eso no demuestra que la
                // celda no esté: demuestra que esta respuesta no sirve para demostrarlo.
                else -> Verdict(
                    VerificationStatus.REJECTED,
                    "WiGLE: devolvió $resultCount registro(s), ninguno de la celda preguntada. " +
                        "Se descarta la respuesta; no se concluye nada sobre la antena. Respuesta: $crudo"
                )
            }
        }

        return when {
            httpCode in 200..299 ->
                Verdict(VerificationStatus.ERROR,
                    "WiGLE: consulta rechazada${if (mensajeApi != null) " ($mensajeApi)" else ""}. NO significa que la antena no exista. Respuesta: $crudo")

            httpCode == 401 || httpCode == 403 ->
                Verdict(VerificationStatus.ERROR,
                    "WiGLE: credenciales rechazadas o cuenta sin acceso a datos de celdas (HTTP $httpCode). " +
                        "La búsqueda de celdas de WiGLE no está abierta a todas las cuentas. Respuesta: $crudo")

            httpCode == 429 ->
                Verdict(VerificationStatus.ERROR, "WiGLE: límite de consultas alcanzado. Se reintentará. Respuesta: $crudo")

            httpCode == 404 ->
                Verdict(VerificationStatus.ERROR,
                    "WiGLE: HTTP 404 (endpoint no encontrado). No dice nada sobre la antena.")

            else -> Verdict(VerificationStatus.ERROR, "WiGLE: HTTP $httpCode. Respuesta: $crudo")
        }
    }
}
