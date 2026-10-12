package com.alexisgordr.icdetector.core

/**
 * 3.0 — Avisos de uso de red 2G/3G. Usar 2G/3G no es un ataque confirmado: también ocurre por falta
 * de cobertura 4G/5G o en llamadas sin VoLTE. Por eso se avisa una vez por episodio y según la
 * preferencia del usuario; el motor (reglas, score, alarmas) no cambia.
 */
enum class LegacyNetworkAlertMode(val storedValue: String) {
    /** Sin aviso (solo una línea en el terminal por episodio). */
    OFF("OFF"),
    /** Notificación silenciosa. Predeterminado. */
    NOTIFY("NOTIFY"),
    /** Notificación y tono: solo por elección explícita. */
    NOTIFY_SOUND("NOTIFY_SOUND");

    companion object {
        const val PREF_KEY = "legacy_network_alert_mode"
        val DEFAULT = NOTIFY

        /** Ausente o desconocido (instalaciones y copias anteriores): [DEFAULT]. */
        fun fromStored(value: String?): LegacyNetworkAlertMode =
            entries.firstOrNull { it.storedValue == value } ?: DEFAULT
    }
}

object LegacyNetwork {
    fun isLegacy(networkType: String?): Boolean = networkType != null && (networkType.contains("2G") || networkType.contains("3G"))
    fun isModern(networkType: String?): Boolean = networkType != null && (networkType.contains("4G") || networkType.contains("5G"))
}

/**
 * Episodios continuos de red 2G/3G:
 *  - **Empieza** con la primera observación 2G/3G sin episodio abierto. Solo entonces se avisa.
 *  - **Sigue abierto** mientras lleguen observaciones 2G/3G, aunque haya huecos sin servicio o
 *    vueltas breves a 4G/5G de menos de [closeAfterMs].
 *  - **Se cierra** tras [closeAfterMs] seguidos observando 4G/5G. Cualquier interrupción de esa
 *    racha — una lectura 2G/3G, una lectura sin servicio o de tipo desconocido, o [interrupt]
 *    (el servicio no tiene celda activa) — reinicia SOLO la cuenta de cierre: el episodio sigue
 *    abierto y la cuenta empieza de nuevo con la siguiente lectura 4G/5G.
 *  - Tras cerrarse, la siguiente observación 2G/3G abre un episodio nuevo.
 */
class LegacyNetworkEpisodeGate(private val closeAfterMs: Long = CLOSE_AFTER_MS) {
    enum class Trigger {
        /** Cambio desde 4G/5G en la observación anterior. */
        CHANGE_FROM_MODERN,
        /** Uso de 2G/3G sin un cambio inmediato desde 4G/5G (p. ej. al arrancar o tras sin servicio). */
        USE
    }

    @Volatile var open = false
        private set
    private var modernSinceMs: Long? = null

    /** @return el tipo de episodio si esta observación ABRE uno nuevo (hay que avisar); si no, null. */
    @Synchronized
    fun observe(networkType: String?, previousNetworkType: String?, nowMs: Long): Trigger? {
        when {
            LegacyNetwork.isLegacy(networkType) -> {
                modernSinceMs = null
                if (open) return null
                open = true
                return if (LegacyNetwork.isModern(previousNetworkType)) Trigger.CHANGE_FROM_MODERN else Trigger.USE
            }
            LegacyNetwork.isModern(networkType) -> if (open) {
                val since = modernSinceMs ?: nowMs.also { modernSinceMs = it }
                if (nowMs - since >= closeAfterMs) { open = false; modernSinceMs = null }
            }
            else -> interrupt()
        }
        return null
    }

    /** Sin servicio o sin celda activa: no hay 4G/5G continuo. Reinicia la cuenta de cierre. */
    @Synchronized
    fun interrupt() { modernSinceMs = null }

    @Synchronized
    fun reset() { open = false; modernSinceMs = null }

    companion object {
        /** 60 s seguidos en 4G/5G cierran el episodio. */
        const val CLOSE_AFTER_MS = 60_000L
    }
}
