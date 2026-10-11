package com.alexisgordr.icdetector.backup

import org.json.JSONObject

/**
 * 3.0 — Ajustes que viajan en la copia de seguridad. Lista cerrada: solo preferencias del usuario.
 * La clave de OpenCelliD (`opencellid_key`) no se copia nunca, ni el estado interno (última
 * ubicación aceptada, contadores, etc.).
 */
object BackupSettings {
    const val PREFS_FILE = "miniic_prefs"

    /** Clave → tipo. */
    val KEYS: Map<String, Type> = linkedMapOf(
        "app_language" to Type.STRING,
        "location_mode" to Type.STRING,
        "proxy_enabled" to Type.BOOLEAN,
        "latency_detection_enabled" to Type.BOOLEAN
    )

    const val NEVER_EXPORTED = "opencellid_key"

    enum class Type { STRING, BOOLEAN }

    /** De los valores actuales (solo los de [KEYS] con el tipo correcto) al JSON de la copia. */
    fun toJson(values: Map<String, *>): String = JSONObject().apply {
        KEYS.forEach { (key, type) ->
            val value = values[key] ?: return@forEach
            when {
                type == Type.STRING && value is String -> put(key, value)
                type == Type.BOOLEAN && value is Boolean -> put(key, value)
            }
        }
    }.toString(2)

    /** Del JSON de la copia a los valores que se pueden aplicar. Lo desconocido se ignora. */
    fun fromJson(json: String?): Map<String, Any> {
        if (json.isNullOrBlank()) return emptyMap()
        val o = try { JSONObject(json) } catch (_: Exception) { return emptyMap() }
        val out = linkedMapOf<String, Any>()
        KEYS.forEach { (key, type) ->
            if (!o.has(key)) return@forEach
            when (type) {
                Type.STRING -> (o.opt(key) as? String)?.takeIf { it.length <= 64 }?.let { out[key] = it }
                Type.BOOLEAN -> (o.opt(key) as? Boolean)?.let { out[key] = it }
            }
        }
        return out
    }
}
