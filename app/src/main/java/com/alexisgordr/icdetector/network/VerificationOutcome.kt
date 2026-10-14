package com.alexisgordr.icdetector.network

import com.alexisgordr.icdetector.models.VerificationStatus
import org.json.JSONObject

/** De dónde vino una respuesta de verificación. */
enum class VerificationSource { OPENCELLID }

/** Motivo técnico que puede requerir una política de reintento específica. */
enum class VerificationFailure {
    NONE,
    /** 3.0 (V4) — La configuración cambió durante la consulta: el resultado no se aplica. */
    CONFIG_CHANGED
}

/**
 * Resultado de consultar a una base pública.
 *
 * Vive en el paquete de red, y no en `models`, porque lleva un `JSONObject`: los modelos del
 * dominio no deben arrastrar una dependencia del transporte.
 *
 * Sustituye al `Triple<VerificationStatus, JSONObject?, String?>` que se devolvía antes. No es
 * cosmética: en un flujo con dos fuentes, cuatro caminos de salida y cinco estados posibles,
 * `first`/`second`/`third` obliga a recordar de memoria qué es cada uno, y un descuido ahí no lo
 * detecta el compilador. Los nombres sí.
 *
 * @property status veredicto (ver [VerificationStatus]).
 * @property source qué base contestó.
 * @property record respuesta cruda cuando hay verificación, para sacar la coordenada.
 * @property reason motivo en texto llano para el terminal, cuando no hay verificación.
 * @property failure fallo estructurado que el servicio debe gestionar sin analizar [reason].
 * @property externalFailure 3.0 (V1) — clase del fallo según los códigos documentados; decide la pausa.
 * @property retryAfterMillis pausa solicitada por la fuente (Retry-After válido), si la hay.
 * @property queriedAt cuándo se preguntó. Lo necesita el TTL de las verificaciones.
 */
data class VerificationOutcome(
    val status: VerificationStatus,
    val source: VerificationSource,
    val record: JSONObject? = null,
    val reason: String? = null,
    val failure: VerificationFailure = VerificationFailure.NONE,
    val externalFailure: com.alexisgordr.icdetector.core.ExternalFailure = com.alexisgordr.icdetector.core.ExternalFailure.NONE,
    val retryAfterMillis: Long? = null,
    val queriedAt: Long = System.currentTimeMillis()
)
