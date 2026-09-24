package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.ServiceRegistrationState
import com.alexisgordr.icdetector.models.ServiceStateSnapshot

/** Un cambio de estado de servicio que merece quedar registrado. */
data class ServiceStateChange(
    val previous: ServiceStateSnapshot?,
    val current: ServiceStateSnapshot
)

/**
 * v2.10.4 — Detecta CAMBIOS de estado de servicio y descarta las repeticiones.
 *
 * Android entrega el ServiceState por callback y además se consulta en cada ciclo de celdas. La
 * mayoría de lecturas son idénticas a la anterior; guardar cada una llenaría la base de datos de
 * filas sin información. Solo se emite un [ServiceStateChange] cuando cambia la
 * [ServiceStateSnapshot.changeSignature].
 *
 * Es solo recolección: nada de aquí entra en una heurística ni cambia un score.
 */
class ServiceStateTracker {
    @Volatile
    var latest: ServiceStateSnapshot? = null
        private set

    /** Registra una lectura. Devuelve el cambio si lo hubo, o null si era una repetición. */
    @Synchronized
    fun observe(snapshot: ServiceStateSnapshot): ServiceStateChange? {
        val change = preview(snapshot) ?: return null
        commit(snapshot)
        return change
    }

    /** Calcula el cambio sin modificar el estado; permite persistir antes de hacerlo visible. */
    @Synchronized
    fun preview(snapshot: ServiceStateSnapshot): ServiceStateChange? {
        val previous = latest
        if (previous != null && previous.changeSignature == snapshot.changeSignature) return null
        return ServiceStateChange(previous, snapshot)
    }

    /** Confirma una lectura únicamente después de que su persistencia haya tenido éxito. */
    @Synchronized
    fun commit(snapshot: ServiceStateSnapshot) {
        latest = snapshot
    }

    companion object {
        /** Etiqueta en español para el terminal (la UI usa recursos traducidos). */
        fun label(state: ServiceRegistrationState): String = when (state) {
            ServiceRegistrationState.IN_SERVICE -> "EN SERVICIO"
            ServiceRegistrationState.OUT_OF_SERVICE -> "SIN SERVICIO"
            ServiceRegistrationState.EMERGENCY_ONLY -> "SOLO EMERGENCIAS"
            ServiceRegistrationState.POWER_OFF -> "RADIO APAGADA"
            ServiceRegistrationState.UNKNOWN -> "DESCONOCIDO"
        }

        /** Línea de terminal para un cambio. Pura para poder probarla. */
        fun terminalLine(change: ServiceStateChange): String {
            val current = change.current
            val transition = change.previous?.let { "${label(it.state)} → ${label(current.state)}" }
                ?: label(current.state)
            val network = current.operatorNumeric?.let { numeric ->
                current.operatorAlphaLong?.takeIf { it.isNotBlank() }?.let { "$numeric ($it)" } ?: numeric
            } ?: "?"
            val data = current.dataNetworkType?.let { tech ->
                when (current.dataRegistered) {
                    true -> "$tech registrado"
                    false -> "$tech no registrado"
                    null -> tech
                }
            } ?: "?"
            val parts = mutableListOf(
                "Estado de servicio: $transition",
                "red=$network",
                "SIM=${current.simOperator ?: "?"}",
                "datos=$data",
                "roaming=${if (current.roaming) "sí" else "no"}"
            )
            if (current.manualSelection) parts += "selección manual de red"
            if (current.operatorDiffersFromSim) parts += "red anunciada distinta de la SIM sin roaming"
            return parts.joinToString(" · ")
        }
    }
}

/**
 * Ordena atómicamente la persistencia y la publicación de un cambio de ServiceState.
 * La notificación solo puede ejecutarse después de que [insert] devuelva un rowId válido.
 */
class ServiceStatePersistence(
    private val tracker: ServiceStateTracker = ServiceStateTracker()
) {
    @Synchronized
    fun persist(
        snapshot: ServiceStateSnapshot,
        insert: (ServiceStateSnapshot) -> Long,
        notify: (ServiceStateChange) -> Unit
    ): Boolean {
        val latest = tracker.latest
        if (latest != null && snapshot.timestampMs < latest.timestampMs) return false
        val change = tracker.preview(snapshot) ?: return false
        val rowId = try { insert(snapshot) } catch (_: Exception) { -1L }
        if (rowId < 0L) return false
        tracker.commit(snapshot)
        notify(change)
        return true
    }
}
