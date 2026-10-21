package com.alexisgordr.icdetector.core

/**
 * 3.1.0 — Preferencias de servicios externos que la app ya no usa. Se borran al arrancar el
 * servicio para que no quede en el teléfono ningún secreto ni estado de una versión anterior.
 *
 * - WiGLE: retirado antes de 3.0 (usuario, token y límite de peticiones).
 * - OpenCellID: verificación externa retirada en 3.1.0 (clave de API, proxy SOCKS5 y pausa).
 * - Latencia: sonda de red de H12, retirada en 3.1.0 (interruptor de Ajustes).
 *
 * Es el ÚNICO sitio de app/src/main que nombra los servicios externos: el test que comprueba que no queda
 * código de red lo permite solo aquí.
 */
object LegacyPreferences {
    val KEYS: List<String> = listOf(
        "wigle_api_name",
        "wigle_api_token",
        "wigle_rate_limited_until",
        "opencellid_key",
        "proxy_enabled",
        "opencellid_pause",
        "latency_detection_enabled"
    )
}
