package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Banco de pruebas de la semántica de verificación.
 *
 * Cada caso reproduce una respuesta real de WiGLE u OpenCellID — cuota agotada, key inválida,
 * resultado vacío, identidad cambiada, 404 — y comprueba qué estado se emite. Esto es lo que no
 * existía cuando la app se pasó meses dando por verificadas antenas ajenas: la decisión vivía
 * enredada con la red y no había forma de probarla.
 *
 * La regla que vigilan todos estos tests: **NOT_FOUND solo ante una negativa explícita y fiable.**
 */
class VerificationDecisionTest {

    private val esperada = VerificationDecision.Identity(
        mcc = "214", mnc = "07", area = "31601", cellId = "123456", radio = RadioTech.LTE
    )

    // ── Identidad ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `identidad completa coincidente`() {
        assertTrue(
            VerificationDecision.identityMatches(
                esperada,
                VerificationDecision.Reported("214", "07", "31601", "123456", RadioTech.LTE)
            )
        )
    }

    @Test
    fun `el MNC se compara como numero, no como texto`() {
        // OpenCellID devuelve el MNC sin el cero a la izquierda: "7" donde el módem dice "07".
        // Comparando cadenas, toda antena de un operador con MNC de un dígito sería rechazada.
        assertTrue(
            VerificationDecision.identityMatches(
                esperada,
                VerificationDecision.Reported("214", "7", "31601", "123456", RadioTech.LTE)
            )
        )
    }

    @Test
    fun `una Cell ID distinta invalida la respuesta`() {
        assertFalse(
            VerificationDecision.identityMatches(
                esperada,
                VerificationDecision.Reported("214", "07", "31601", "999999", RadioTech.LTE)
            )
        )
    }

    @Test
    fun `un area distinta invalida la respuesta`() {
        assertFalse(
            VerificationDecision.identityMatches(
                esperada,
                VerificationDecision.Reported("214", "07", "99999", "123456", RadioTech.LTE)
            )
        )
    }

    @Test
    fun `un operador distinto invalida la respuesta`() {
        assertFalse(
            VerificationDecision.identityMatches(
                esperada,
                VerificationDecision.Reported("208", "07", "31601", "123456", RadioTech.LTE)
            )
        )
    }

    @Test
    fun `una tecnologia distinta invalida la respuesta`() {
        assertFalse(
            VerificationDecision.identityMatches(
                esperada,
                VerificationDecision.Reported("214", "07", "31601", "123456", RadioTech.GSM)
            )
        )
    }

    @Test
    fun `sin Cell ID en la respuesta no se verifica nada`() {
        // Este es el agujero exacto por el que pasaron las verificaciones falsas: una respuesta de
        // la que no se puede saber de qué celda habla no puede confirmar ninguna.
        assertFalse(
            VerificationDecision.identityMatches(
                esperada,
                VerificationDecision.Reported(mcc = "214", mnc = "07", area = "31601", cellId = null)
            )
        )
    }

    @Test
    fun `los campos ausentes no se comprueban`() {
        // Las APIs no devuelven siempre los mismos campos; exigir lo que no mandan rechazaría
        // respuestas buenas. Con la Cell ID correcta y el resto ausente, la respuesta vale.
        assertTrue(
            VerificationDecision.identityMatches(
                esperada,
                VerificationDecision.Reported(cellId = "123456")
            )
        )
    }

    @Test
    fun `un radio desconocido no es un desacuerdo`() {
        assertTrue(
            VerificationDecision.identityMatches(
                esperada,
                VerificationDecision.Reported("214", "07", "31601", "123456", RadioTech.UNKNOWN)
            )
        )
    }

    @Test
    fun `la identidad celular oficial de WiGLE se interpreta completa`() {
        val recibida = VerificationDecision.reportedFromWigleId(
            "21407_31601_123456", "LTE"
        )
        assertEquals(
            VerificationDecision.Reported("214", "07", "31601", "123456", RadioTech.LTE),
            recibida
        )
        assertTrue(VerificationDecision.identityMatches(esperada, recibida!!))
    }

    @Test
    fun `un id WiGLE ajeno o mal formado nunca verifica`() {
        val ajena = VerificationDecision.reportedFromWigleId("21407_31601_999999", "LTE")
        assertFalse(VerificationDecision.identityMatches(esperada, ajena!!))
        assertEquals(null, VerificationDecision.reportedFromWigleId("respuesta-sin-identidad"))
    }

    @Test
    fun `OpenCellID usa lac y cellid antes que auxiliares a cero`() {
        val recibida = VerificationDecision.reportedFromOpenCellId(
            mcc = "214", mnc = "7", lac = "31601", tac = "0",
            cellId = "123456", cid = "0", radio = "LTE"
        )
        assertTrue(VerificationDecision.identityMatches(esperada, recibida))
    }

    @Test
    fun `NOT_FOUND solo representa acuerdo negativo de todas las fuentes`() {
        assertEquals(
            VerificationStatus.NOT_FOUND,
            VerificationDecision.combine(VerificationStatus.NOT_FOUND, VerificationStatus.NOT_FOUND)
        )
        assertEquals(
            VerificationStatus.REJECTED,
            VerificationDecision.combine(VerificationStatus.NOT_FOUND, VerificationStatus.ERROR)
        )
        assertEquals(
            VerificationStatus.REJECTED,
            VerificationDecision.combine(VerificationStatus.ERROR, VerificationStatus.NOT_FOUND)
        )
        assertEquals(
            VerificationStatus.ERROR,
            VerificationDecision.combine(VerificationStatus.ERROR, VerificationStatus.ERROR)
        )
        assertEquals(
            VerificationStatus.REJECTED,
            VerificationDecision.combine(VerificationStatus.NOT_FOUND, VerificationStatus.REJECTED)
        )
        assertEquals(
            VerificationStatus.VERIFIED,
            VerificationDecision.combine(VerificationStatus.REJECTED, VerificationStatus.VERIFIED)
        )
    }

    // ── OpenCellID ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `opencellid con coordenada e identidad correcta verifica`() {
        val v = VerificationDecision.forOpenCellId(200, hasCoordinates = true, errorCode = -1, identityOk = true)
        assertEquals(VerificationStatus.VERIFIED, v.status)
    }

    @Test
    fun `opencellid con coordenada de otra celda se descarta, no se niega`() {
        val v = VerificationDecision.forOpenCellId(200, hasCoordinates = true, errorCode = -1, identityOk = false)
        assertEquals(VerificationStatus.REJECTED, v.status)
    }

    @Test
    fun `opencellid code 1 es la unica negativa`() {
        val v = VerificationDecision.forOpenCellId(200, hasCoordinates = false, errorCode = 1, identityOk = false)
        assertEquals(VerificationStatus.NOT_FOUND, v.status)
    }

    @Test
    fun `opencellid code 1 con indisponibilidad temporal es ERROR`() {
        val v = VerificationDecision.forOpenCellId(
            200, hasCoordinates = false, errorCode = 1, identityOk = false,
            mensajeApi = "Endpoint temporarily unavailable"
        )
        assertEquals(VerificationStatus.ERROR, v.status)
    }

    @Test
    fun `opencellid con cuota agotada es ERROR, nunca NOT_FOUND`() {
        assertEquals(
            VerificationStatus.ERROR,
            VerificationDecision.forOpenCellId(429, false, 7, false).status
        )
        assertEquals(
            VerificationStatus.ERROR,
            VerificationDecision.forOpenCellId(200, false, 7, false).status
        )
    }

    @Test
    fun `opencellid con key invalida es ERROR`() {
        assertEquals(VerificationStatus.ERROR, VerificationDecision.forOpenCellId(401, false, 2, false).status)
        assertEquals(VerificationStatus.ERROR, VerificationDecision.forOpenCellId(403, false, -1, false).status)
    }

    @Test
    fun `opencellid con 503 es ERROR`() {
        assertEquals(VerificationStatus.ERROR, VerificationDecision.forOpenCellId(503, false, 6, false).status)
    }

    @Test
    fun `un 404 no significa que la antena no exista`() {
        // La forma documentada de decir "no la tengo" es HTTP 200 con code 1. Un 404 es un endpoint
        // que no está donde se esperaba: un problema nuestro, no un dato sobre la antena.
        assertEquals(VerificationStatus.ERROR, VerificationDecision.forOpenCellId(404, false, -1, false).status)
    }

    @Test
    fun `opencellid con respuesta incomprensible es ERROR`() {
        assertEquals(VerificationStatus.ERROR, VerificationDecision.forOpenCellId(200, false, -1, false).status)
    }

    // ── WiGLE ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `wigle con un resultado de la celda preguntada verifica`() {
        val v = VerificationDecision.forWigle(200, success = true, resultCount = 1, identityOk = true)
        assertEquals(VerificationStatus.VERIFIED, v.status)
    }

    @Test
    fun `wigle con consulta valida y cero resultados es una negativa fiable`() {
        val v = VerificationDecision.forWigle(200, success = true, resultCount = 0, identityOk = false)
        assertEquals(VerificationStatus.NOT_FOUND, v.status)
    }

    @Test
    fun `wigle con resultados de otras celdas se descarta, no se niega`() {
        // El caso que hundió las verificaciones antiguas: la búsqueda no filtraba y devolvía
        // antenas ajenas. Ni verifica ni desmiente: descarta.
        val v = VerificationDecision.forWigle(200, success = true, resultCount = 5, identityOk = false)
        assertEquals(VerificationStatus.REJECTED, v.status)
    }

    @Test
    fun `wigle con success false es ERROR aunque el HTTP sea 200`() {
        // Así responde WiGLE cuando se agota la cuota o la cuenta no tiene acceso al endpoint de
        // celdas. Contarlo como "antena desconocida" contamina el historial entero.
        val v = VerificationDecision.forWigle(200, success = false, resultCount = 0, identityOk = false)
        assertEquals(VerificationStatus.ERROR, v.status)
    }

    @Test
    fun `wigle sin permiso para datos de celdas es ERROR`() {
        assertEquals(VerificationStatus.ERROR, VerificationDecision.forWigle(401, false, 0, false).status)
        assertEquals(VerificationStatus.ERROR, VerificationDecision.forWigle(403, false, 0, false).status)
    }

    @Test
    fun `wigle con 429 es ERROR`() {
        assertEquals(VerificationStatus.ERROR, VerificationDecision.forWigle(429, false, 0, false).status)
    }

    @Test
    fun `wigle con 404 es ERROR, no una negativa`() {
        assertEquals(VerificationStatus.ERROR, VerificationDecision.forWigle(404, false, 0, false).status)
    }

    // ── VERIFIED exige que TODO esté bien, no solo el contenido ──────────────────────────────

    @Test
    fun `una coordenada correcta con HTTP de error no verifica`() {
        // La comprobación empezaba por las coordenadas, así que un 401, un 404 o un 500 que
        // trajeran lat/lon con la identidad correcta se daban por buenos. No es un caso esperable
        // — pero una máquina de estados defensiva no se apoya en lo que espera ver.
        for (http in listOf(401, 403, 404, 429, 500, 503)) {
            val v = VerificationDecision.forOpenCellId(http, hasCoordinates = true, errorCode = -1, identityOk = true)
            assertEquals("HTTP $http no puede verificar", VerificationStatus.ERROR, v.status)
        }
    }

    @Test
    fun `una coordenada correcta con codigo de error de la API no verifica`() {
        for (code in listOf(2, 6, 7)) {
            val v = VerificationDecision.forOpenCellId(200, hasCoordinates = true, errorCode = code, identityOk = true)
            assertEquals("code $code no puede verificar", VerificationStatus.ERROR, v.status)
        }
        // El code 1 con coordenadas es una respuesta contradictoria: manda el error declarado.
        assertEquals(
            VerificationStatus.NOT_FOUND,
            VerificationDecision.forOpenCellId(200, hasCoordinates = true, errorCode = 1, identityOk = true).status
        )
    }

    @Test
    fun `VERIFIED solo es alcanzable con HTTP 2xx, sin error, con coordenada e identidad`() {
        val alcanzables = mutableListOf<String>()
        for (http in listOf(200, 201, 299, 301, 400, 401, 403, 404, 429, 500, 503)) {
            for (code in listOf(-1, 0, 1, 2, 6, 7, 99)) {
                for (coords in listOf(true, false)) {
                    for (ok in listOf(true, false)) {
                        if (VerificationDecision.forOpenCellId(http, coords, code, ok).status ==
                            VerificationStatus.VERIFIED
                        ) {
                            alcanzables.add("http=$http code=$code coords=$coords idOk=$ok")
                            assertTrue("VERIFIED con HTTP $http", http in 200..299)
                            assertTrue("VERIFIED con código de error $code", code <= 0)
                            assertTrue("VERIFIED sin coordenada", coords)
                            assertTrue("VERIFIED sin identidad comprobada", ok)
                        }
                    }
                }
            }
        }
        assertTrue("Debería existir al menos un camino a VERIFIED", alcanzables.isNotEmpty())
    }

    @Test
    fun `WiGLE tampoco verifica con un HTTP de error`() {
        for (http in listOf(401, 403, 404, 429, 500)) {
            assertEquals(
                "HTTP $http no puede verificar",
                VerificationStatus.ERROR,
                VerificationDecision.forWigle(http, success = true, resultCount = 1, identityOk = true).status
            )
        }
    }

    // ── La invariante que resume todo ────────────────────────────────────────────────────────

    @Test
    fun `solo dos respuestas en el mundo producen NOT_FOUND`() {
        val negativas = mutableListOf<String>()
        for (http in listOf(200, 401, 403, 404, 429, 500, 503)) {
            for (code in listOf(-1, 1, 2, 6, 7)) {
                for (coords in listOf(true, false)) {
                    for (ok in listOf(true, false)) {
                        if (VerificationDecision.forOpenCellId(http, coords, code, ok).status ==
                            VerificationStatus.NOT_FOUND
                        ) negativas.add("ocid http=$http code=$code coords=$coords idOk=$ok")
                    }
                }
            }
            for (success in listOf(true, false)) {
                for (n in listOf(0, 1, 5)) {
                    for (ok in listOf(true, false)) {
                        if (VerificationDecision.forWigle(http, success, n, ok).status ==
                            VerificationStatus.NOT_FOUND
                        ) negativas.add("wigle http=$http success=$success n=$n idOk=$ok")
                    }
                }
            }
        }
        // OpenCellID: HTTP 2xx declarando `code = 1`, con o sin coordenada — un cuerpo que dice
        // "cell not found" y a la vez trae coordenadas es contradictorio, y manda el error
        // declarado. WiGLE: HTTP 2xx, success, cero resultados.
        assertTrue(
            "Alguna combinación inesperada produce NOT_FOUND: $negativas",
            negativas.all {
                (it.startsWith("ocid") && it.contains("http=200") && it.contains("code=1")) ||
                    (it.startsWith("wigle") && it.contains("http=200") && it.contains("success=true") && it.contains("n=0"))
            }
        )
        assertTrue("Debería haber al menos una negativa de cada fuente", negativas.size >= 2)
    }
}
