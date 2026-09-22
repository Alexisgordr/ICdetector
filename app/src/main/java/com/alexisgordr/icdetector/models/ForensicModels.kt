package com.alexisgordr.icdetector.models

enum class ForensicCaseState { CAPTURING, POST_CAPTURE, READY, INTERRUPTED }
enum class ForensicCaseOrigin { ALARM, TRUST_CONTRADICTION }

data class ForensicCase(
    val id: Long,
    val caseCode: String,
    val createdAt: String,
    val updatedAt: String,
    val closedAt: String?,
    val state: ForensicCaseState,
    val origin: ForensicCaseOrigin,
    val cellIdentity: String,
    val highestPhase: Int,
    val confirmed: Boolean,
    val sampleCount: Int
)

/**
 * Resultado de aplicar el tope de muestras forenses — v2.8.0.
 *
 * El recorte anterior borraba muestras sueltas por antigüedad y dejaba casos mutilados: un
 * paquete al que le faltan los primeros minutos sigue apareciendo en la lista como si estuviera
 * completo, y eso es peor que no tenerlo, porque se exporta y se analiza creyéndolo íntegro.
 * Ahora se borran casos cerrados enteros, del más antiguo al más reciente, y esto cuenta qué pasó.
 *
 * @param casesDeleted casos cerrados eliminados por completo.
 * @param samplesDeleted muestras que se fueron con ellos.
 * @param remainingSamples muestras que quedan en la tabla tras el recorte.
 * @param overCapacity true si, agotados los casos cerrados, el total sigue por encima del tope —
 *   es decir, los casos abiertos solos ya lo superan. No se tocan, pero conviene saberlo.
 */
data class ForensicPruneResult(
    val casesDeleted: Int = 0,
    val samplesDeleted: Int = 0,
    val remainingSamples: Int = 0,
    val overCapacity: Boolean = false
)

data class ForensicSample(
    val id: Long,
    val caseId: Long,
    val wallTimeMs: Long,
    val elapsedTimeMs: Long,
    val event: String,
    val payloadJson: String
)
