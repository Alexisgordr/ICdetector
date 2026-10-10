package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.ServiceStateSnapshot

/**
 * 3.0 (A03) — Contexto de una observación, fijado en el momento de RECIBIRLA: instante, posición
 * GPS y estado de servicio.
 *
 * Bug found during testing: el contexto se leía al guardar la fila, que ocurre al publicar el
 * resultado del análisis. Si el análisis tardaba, una lectura de celdas anterior quedaba con la
 * hora, la posición y el estado de servicio de un momento posterior. Ahora se captura al llegar
 * la lectura y viaja con ella hasta el historial.
 *
 * Sin dependencias de Android, para poder probar el recorrido completo en la JVM.
 */
data class ObservationContext(
    /** Instante de la observación, en milisegundos desde epoch (UTC). */
    val observedAtMs: Long,
    /** Posición en ese instante; null si no había un fix válido (desconocida, no "sin GPS"). */
    val fix: ObservedFix?,
    /** Último estado de servicio conocido en ese instante. */
    val service: ServiceStateSnapshot?,
    /** 3.0 — Modo de ubicación en ese instante ([LocationMode.storedValue]); null = desconocido. */
    val locationMode: String? = null
)

/** Posición GPS de una observación. [accuracyM] null si el fix no declaraba precisión. */
data class ObservedFix(val latitude: Double, val longitude: Double, val accuracyM: Float?)
