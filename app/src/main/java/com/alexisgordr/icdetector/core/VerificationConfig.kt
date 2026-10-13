package com.alexisgordr.icdetector.core

import java.util.concurrent.atomic.AtomicReference

/**
 * 3.0 (V4) — Configuración de la verificación externa (OpenCellID), inmutable y COMPLETA: clave y
 * ruta de red (proxy Tor o conexión directa) van juntas. Antes eran dos campos sueltos que el
 * servicio leía por separado: una consulta podía usar la clave nueva con la ruta antigua o al revés.
 *
 * [generation] crece con cada cambio. Una consulta toma una instantánea al empezar y la usa entera;
 * su resultado solo se aplica si su generación sigue siendo la vigente (ver [VerificationConfigHolder]).
 */
data class VerificationConfig(val apiKey: String, val proxyEnabled: Boolean, val generation: Long) {
    val hasCredentials: Boolean get() = apiKey.isNotBlank() && !apiKey.startsWith("pk.YOUR")

    /**
     * Huella de la credencial para asociar pausas (V1) sin guardar la clave: SHA-256 de la clave,
     * recortado. Es interna (preferencias que no viajan en la copia) y nunca se registra.
     */
    val credentialFingerprint: String get() = fingerprint(apiKey)

    /** La clave nunca aparece en textos de depuración. */
    override fun toString() = "VerificationConfig(apiKey=[REDACTED], proxyEnabled=$proxyEnabled, generation=$generation)"

    companion object {
        fun fingerprint(apiKey: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(apiKey.trim().toByteArray(Charsets.UTF_8))
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Publica la configuración vigente de forma segura entre hilos: una sola referencia atómica a un
 * objeto inmutable (dos @Volatile independientes no garantizan que se lea una pareja coherente).
 *
 * Qué pasa con lo que ya estaba en marcha al cambiarla:
 *  - Cualquier cambio sube la generación: los resultados de consultas anteriores se DESCARTAN (no
 *    tocan cachés, pausas ni la pantalla) y la celda puede volver a consultarse con la nueva.
 *  - Un cambio de RUTA (proxy activado o desactivado) además CANCELA las peticiones en curso
 *    ([onRouteChanged]): una petición directa no sigue viva si ahora se exige Tor, ni al revés.
 *  - Antes de abrir la conexión se vuelve a comprobar [isCurrent]: no se inicia una petición con
 *    una configuración que ya no es la vigente.
 */
class VerificationConfigHolder(apiKey: String = "", proxyEnabled: Boolean = false) {
    private val ref = AtomicReference(VerificationConfig(apiKey.trim(), proxyEnabled, 0L))
    private val inFlight = java.util.concurrent.ConcurrentHashMap<Long, Pair<Long, () -> Unit>>()
    private val ids = java.util.concurrent.atomic.AtomicLong()

    /**
     * 3.0 (V4) — Comprobar la vigencia y aplicar los efectos de una respuesta debe ser indivisible
     * frente a un cambio de ajustes: [applyIfCurrent] toma el candado de LECTURA (varias respuestas
     * a la vez) y [update] el de ESCRITURA. Sin esto, cambiar los ajustes entre la comprobación y la
     * escritura dejaba aplicar una respuesta antigua a cachés, pausas y base.
     */
    private val lock = java.util.concurrent.locks.ReentrantReadWriteLock()

    enum class Change { NONE, CREDENTIAL, ROUTE, CREDENTIAL_AND_ROUTE }

    fun current(): VerificationConfig = ref.get()

    fun isCurrent(snapshot: VerificationConfig): Boolean = ref.get().generation == snapshot.generation

    /** Cambia clave y ruta a la vez. Devuelve qué cambió. */
    fun update(apiKey: String, proxyEnabled: Boolean, onChanged: (Change) -> Unit = {}): Change {
        val w = lock.writeLock()
        w.lock()
        try {
            // [onChanged] corre con el candado de escritura: ninguna respuesta se aplica entre el
            // cambio y la limpieza que haga el llamador (pausas, tiempos de reintento).
            return updateLocked(apiKey, proxyEnabled).also { if (it != Change.NONE) onChanged(it) }
        } finally { w.unlock() }
    }

    /**
     * Ejecuta [block] solo si [snapshot] sigue vigente, y ningún [update] puede colarse mientras
     * dura. Devuelve si se ejecutó. [block] no debe llamar a [update] (no hay subida de candado).
     */
    fun applyIfCurrent(snapshot: VerificationConfig, block: () -> Unit): Boolean {
        val r = lock.readLock()
        r.lock()
        try {
            if (!isCurrent(snapshot)) return false
            block()
            return true
        } finally { r.unlock() }
    }

    private fun updateLocked(apiKey: String, proxyEnabled: Boolean): Change {
        val key = apiKey.trim()
        var change = Change.NONE
        ref.updateAndGet { old ->
            val credential = old.apiKey != key
            val route = old.proxyEnabled != proxyEnabled
            change = when {
                credential && route -> Change.CREDENTIAL_AND_ROUTE
                route -> Change.ROUTE
                credential -> Change.CREDENTIAL
                else -> Change.NONE
            }
            if (change == Change.NONE) old else VerificationConfig(key, proxyEnabled, old.generation + 1)
        }
        if (change == Change.ROUTE || change == Change.CREDENTIAL_AND_ROUTE) onRouteChanged()
        return change
    }

    /**
     * Registra una petición en curso de [snapshot] con su forma de cancelarla. Si la configuración ya
     * cambió, la cancela en el acto. Devuelve la función para quitarla al terminar.
     */
    fun track(snapshot: VerificationConfig, cancel: () -> Unit): () -> Unit {
        val id = ids.incrementAndGet()
        inFlight[id] = snapshot.generation to cancel
        // Si la configuración cambió entre la instantánea y el registro, no se deja empezar.
        if (!isCurrent(snapshot)) { inFlight.remove(id); cancel() }
        return { inFlight.remove(id) }
    }

    /** Peticiones en curso (pruebas y diagnóstico). */
    fun inFlightCount(): Int = inFlight.size

    private fun onRouteChanged() {
        val generation = ref.get().generation
        inFlight.entries.filter { it.value.first < generation }.forEach { (id, entry) ->
            if (inFlight.remove(id) != null) runCatching { entry.second() }
        }
    }
}
