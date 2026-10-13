package com.alexisgordr.icdetector.core

import java.util.UUID

const val MOBILITY_FAMILIARITY_ENABLED = true

enum class MobilityFamiliarity { UNKNOWN_ON_ROUTE, OBSERVED_ON_ROUTE, KNOWN_ON_ROUTE }
/**
 * 3.0 (G3) — CLOCK_CHANGED: el reloj civil saltó respecto al monotónico durante el viaje; se cierra
 * en su última hora civil observada para que sus fechas guardadas sigan siendo coherentes.
 */
enum class MobilityTripCloseReason { STATIC_CONFIRMED, SAME_SERVING_TIMEOUT, MAX_DURATION, ABANDONED, CLOCK_CHANGED }

data class MobilityFamiliarityConfig(
    val priorTripsRequired: Int = 3,
    val goodEdgesRequired: Int = 2,
    val minimumDistinctCells: Int = 3,
    // Provisional field parameters. They must be calibrated from exported field evidence.
    val staticCloseDurationMs: Long = 3 * 60_000L,
    val sameServingCloseDurationMs: Long = 10 * 60_000L,
    val maxTripDurationMs: Long = 6 * 60 * 60_000L,
    val resumeWindowMs: Long = 15 * 60_000L,
    val closedTripDetailRetentionMs: Long = 90L * 24 * 60 * 60_000L
)

data class MobilityEdge(val from: String, val to: String)

/**
 * 3.0 (G3) — Una lectura de reloj. [civilMs] (System.currentTimeMillis) solo da las fechas que se
 * guardan; [elapsedMs] (SystemClock.elapsedRealtime, cuenta el reposo profundo) mide los
 * intervalos. [bootId] identifica el arranque: un tiempo monotónico de otro arranque no se compara
 * nunca. null si no se conoce, y entonces no se puede demostrar continuidad tras un reinicio.
 */
data class MobilityClock(val civilMs: Long, val elapsedMs: Long, val bootId: String?)

/**
 * 3.0 (G3) — Referencias monotónicas del viaje abierto, válidas solo en el arranque [bootId]. Se
 * guardan aparte (preferencia interna, sin esquema nuevo ni copia de seguridad) para poder
 * demostrar continuidad si el proceso se reinicia dentro del mismo arranque.
 */
data class MobilityTripAnchor(
    val tripId: String,
    val bootId: String?,
    val startedElapsedMs: Long,
    val lastSeenElapsedMs: Long,
    val lastSeenCivilMs: Long,
    val sameServingSinceElapsedMs: Long,
    val staticSinceElapsedMs: Long?
) {
    fun serialize(): String = listOf(tripId, bootId ?: "", startedElapsedMs, lastSeenElapsedMs, lastSeenCivilMs,
        sameServingSinceElapsedMs, staticSinceElapsedMs ?: "").joinToString("|")

    companion object {
        /** Preferencia interna donde el servicio guarda el ancla. No viaja en la copia de seguridad. */
        const val PREF_KEY = "mobility_trip_anchor"

        fun parse(text: String?): MobilityTripAnchor? {
            val p = text?.split("|")?.takeIf { it.size == 7 } ?: return null
            return try {
                MobilityTripAnchor(p[0].ifEmpty { return null }, p[1].ifEmpty { null }, p[2].toLong(), p[3].toLong(),
                    p[4].toLong(), p[5].toLong(), p[6].ifEmpty { null }?.toLong())
            } catch (_: NumberFormatException) { null }
        }
    }
}

interface MobilityAnchorStore {
    fun load(): MobilityTripAnchor?
    fun save(anchor: MobilityTripAnchor?)
}

/** Sin persistencia: tras reiniciar el proceso no hay continuidad demostrable. */
class InMemoryMobilityAnchorStore : MobilityAnchorStore {
    private var anchor: MobilityTripAnchor? = null
    override fun load() = anchor
    override fun save(anchor: MobilityTripAnchor?) { this.anchor = anchor }
}

/**
 * 3.0 (G2) — Única política de familiaridad histórica, la misma para el servicio (en directo) y
 * para Geometry/exportación. Mide lo aprendido de VIAJES ANTERIORES YA CONFIRMADOS: cada arista
 * cuenta los viajes cerrados válidos que la recorrieron (trip_count). Una celda es:
 *  - KNOWN_ON_ROUTE: al menos [MobilityFamiliarityConfig.goodEdgesRequired] (2) aristas suyas, de
 *    entrada o de salida, recorridas en [MobilityFamiliarityConfig.priorTripsRequired] (3) viajes;
 *  - OBSERVED_ON_ROUTE: alguna arista suya aparece en algún viaje anterior;
 *  - UNKNOWN_ON_ROUTE: ninguna.
 * El viaje abierto nunca cuenta (sus aristas solo suman al cerrarse), así que un viaje no puede
 * validarse a sí mismo. Es memoria contextual, no confianza ni legitimidad.
 */
object MobilityFamiliarityPolicy {
    data class Assessment(val familiarity: MobilityFamiliarity, val goodEdges: Int, val maxPriorTrips: Int)

    /** [priorTripCounts]: viajes anteriores confirmados de cada arista incidente en la celda. */
    fun assess(priorTripCounts: Collection<Int>, config: MobilityFamiliarityConfig = MobilityFamiliarityConfig()): Assessment {
        val good = priorTripCounts.count { it >= config.priorTripsRequired }
        val familiarity = when {
            good >= config.goodEdgesRequired -> MobilityFamiliarity.KNOWN_ON_ROUTE
            priorTripCounts.any { it > 0 } -> MobilityFamiliarity.OBSERVED_ON_ROUTE
            else -> MobilityFamiliarity.UNKNOWN_ON_ROUTE
        }
        return Assessment(familiarity, good, priorTripCounts.maxOrNull() ?: 0)
    }

    /** ¿La arista por la que se acaba de llegar está aprendida (≥ K viajes anteriores)? */
    fun arrivalKnown(arrivalPriorTrips: Int, config: MobilityFamiliarityConfig = MobilityFamiliarityConfig()): Boolean =
        arrivalPriorTrips >= config.priorTripsRequired
}
data class MobilityTrip(
    val id: String,
    val startedAtMs: Long,
    val lastSeenMs: Long,
    val hasMoving: Boolean,
    val lastServing: String?,
    val sameServingSinceMs: Long,
    val staticSinceMs: Long?
)

interface MobilityFamiliarityStore {
    fun openTrip(): MobilityTrip?
    fun createTrip(trip: MobilityTrip, firstCell: String)
    fun updateTrip(trip: MobilityTrip)
    fun addTripCell(tripId: String, identity: String)
    fun addTripEdge(tripId: String, edge: MobilityEdge)
    fun tripCells(tripId: String): Set<String>
    fun tripEdges(tripId: String): Set<MobilityEdge>
    fun priorTripCounts(edges: Set<MobilityEdge>): Map<MobilityEdge, Int>
    /** 3.0 (G2) — Todas las aristas guardadas que entran o salen de [identity], con sus viajes anteriores confirmados. */
    fun historicalEdgeCounts(identity: String): Map<MobilityEdge, Int>
    fun commitMobilityTrip(tripId: String, reason: MobilityTripCloseReason, closedAtMs: Long)
    fun discardMobilityTrip(tripId: String, reason: MobilityTripCloseReason, closedAtMs: Long)
    fun pruneMobilityTripDetails(beforeMs: Long)
}

/**
 * [familiarity]/[goodEdges]: familiaridad HISTÓRICA de la celda actual ([MobilityFamiliarityPolicy]),
 * la misma que muestra Geometry. [arrivalPriorTrips]: lo observado en ESTE viaje, la arista por la
 * que se llegó a la celda y sus viajes anteriores confirmados; null si no hubo llegada (misma celda
 * o primera celda del viaje). Son conceptos distintos y se presentan por separado.
 */
data class MobilityObservation(
    val familiarity: MobilityFamiliarity,
    val tripId: String? = null,
    val goodEdges: Int = 0,
    val event: String? = null,
    val arrivalPriorTrips: Int? = null
)

/**
 * Service-owned trip state machine. Evaluation is deliberately read-only and always precedes a
 * possible commit, so the open trip cannot validate itself.
 *
 * 3.0 (G3) — Relojes. Los intervalos (duración máxima, reposo, misma servidora, reanudación) se
 * miden con el reloj monotónico de [MobilityClock] y solo dentro del mismo arranque; el reloj civil
 * solo fecha lo que se guarda. Si el civil se separa del monotónico más de [CIVIL_JUMP_TOLERANCE_MS]
 * (cambio de hora manual, zona, red), el viaje se cierra en su última hora civil observada
 * (CLOCK_CHANGED): sus fechas no pueden quedar invertidas ni estirarse. Tras reiniciar el proceso,
 * el viaje solo se reanuda si hay continuidad demostrable (mismo arranque, su ancla monotónica, y
 * un hueco dentro de la ventana de reanudación); si no, se cierra como ABANDONED en su última hora
 * observada, sin inventar duración. Los límites no cambian.
 */
class MobilityFamiliarityEngine(
    private val store: MobilityFamiliarityStore,
    private val config: MobilityFamiliarityConfig = MobilityFamiliarityConfig(),
    private val enabled: Boolean = MOBILITY_FAMILIARITY_ENABLED,
    private val anchors: MobilityAnchorStore = InMemoryMobilityAnchorStore(),
    private val newTripId: () -> String = { UUID.randomUUID().toString() }
) {
    constructor(
        store: MobilityFamiliarityStore,
        config: MobilityFamiliarityConfig,
        enabled: Boolean,
        newTripId: () -> String
    ) : this(store, config, enabled, InMemoryMobilityAnchorStore(), newTripId)

    private var recovered = false
    /**
     * 3.0 (G4) — Tras reanudar un viaje después de reiniciar el proceso: hora monotónica de la última
     * observación guardada. La primera observación solo enlaza con la celda anterior si el hueco
     * cabe en [CoverageContinuity.MAX_GAP_MS] (continuidad demostrable); si no, referencia nueva.
     */
    private var resumedFromElapsedMs: Long? = null
    private var anchor: MobilityTripAnchor? = null
    private var persistedAnchor: MobilityTripAnchor? = null

    /** Pruebas: relojes civil y monotónico que avanzan juntos en un solo arranque. */
    internal fun observe(identity: String, motion: MotionEvidence, nowMs: Long, serviceContinuous: Boolean = true): MobilityObservation =
        observe(identity, motion, MobilityClock(nowMs, nowMs, SAME_BOOT), serviceContinuous)

    internal fun recover(nowMs: Long): MobilityObservation = recover(MobilityClock(nowMs, nowMs, SAME_BOOT))

    /**
     * [serviceContinuous] (G4): false si desde la observación anterior hubo una pérdida de servicio
     * o un hueco (CoverageContinuity). Entonces la celda es una referencia nueva del viaje y no se
     * crea ninguna arista desde la anterior (ver [HandoverReference]).
     */
    @Synchronized
    fun observe(identity: String, motion: MotionEvidence, clock: MobilityClock, serviceContinuous: Boolean = true): MobilityObservation {
        if (!enabled || identity.isBlank()) return MobilityObservation(MobilityFamiliarity.UNKNOWN_ON_ROUTE)
        recoverIfNeeded(clock)
        var trip = store.openTrip()
        var clockEvent: String? = null
        val open = trip
        if (open != null) {
            val a = anchor?.takeIf { it.tripId == open.id && it.bootId == clock.bootId && clock.elapsedMs >= it.lastSeenElapsedMs }
            val reason = when {
                a == null -> MobilityTripCloseReason.ABANDONED
                civilJumped(a, clock) -> MobilityTripCloseReason.CLOCK_CHANGED
                else -> null
            }
            if (reason != null) {
                // Sin referencia monotónica válida o con el reloj civil desplazado: se cierra en su
                // última hora civil observada y, si hay movimiento, empieza un viaje nuevo.
                val committed = close(open, reason, open.lastSeenMs)
                clockEvent = if (committed) "trip_closed reason=${reason.name}" else "trip_discarded reason=${reason.name}"
                trip = null
            }
        }
        if (trip == null) {
            if (motion.state != MotionState.MOVING) return MobilityObservation(MobilityFamiliarity.UNKNOWN_ON_ROUTE, event = clockEvent)
            trip = MobilityTrip(newTripId(), clock.civilMs, clock.civilMs, true, identity, clock.civilMs, null)
            store.createTrip(trip, identity)
            setAnchor(MobilityTripAnchor(trip.id, clock.bootId, clock.elapsedMs, clock.elapsedMs, clock.civilMs, clock.elapsedMs, null), force = true)
            // 3.0 (G2) — La primera celda del viaje también tiene historia: la misma política que en
            // Geometry. Sin llegada observada todavía (arrivalPriorTrips = null).
            val first = MobilityFamiliarityPolicy.assess(store.historicalEdgeCounts(identity).values, config)
            return MobilityObservation(first.familiarity, trip.id, first.goodEdges, event = clockEvent ?: "trip_started", arrivalPriorTrips = null)
        }
        val a = anchor!!

        val priorServing = trip.lastServing
        val afterRestart = resumedFromElapsedMs?.let { clock.elapsedMs - it in 0..CoverageContinuity.MAX_GAP_MS }
        resumedFromElapsedMs = null
        val step = HandoverReference.step(priorServing, identity, serviceContinuous && afterRestart != false)
        // 3.0 (G2) — Se evalúa ANTES de incorporar esta observación al viaje, y solo con viajes
        // anteriores confirmados: el viaje abierto no puede validarse a sí mismo.
        val historical = MobilityFamiliarityPolicy.assess(store.historicalEdgeCounts(identity).values, config)
        val arrivalEdge = if (step == HandoverReference.Step.HANDOVER) MobilityEdge(priorServing!!, identity) else null
        val arrivalPriorTrips = arrivalEdge?.let { store.priorTripCounts(setOf(it))[it] ?: 0 }
        // La celda es la misma o no con o sin interrupción: el temporizador de «misma servidora» no cambia.
        val sameServing = priorServing == identity
        var event: String? = if (step == HandoverReference.Step.NEW_REFERENCE_AFTER_INTERRUPTION && !sameServing)
            "reference_reset after interruption" else null
        val sameServingSinceElapsed = if (sameServing) a.sameServingSinceElapsedMs else clock.elapsedMs
        val staticSinceElapsed = if (motion.state == MotionState.STATIC_CONFIRMED) a.staticSinceElapsedMs ?: clock.elapsedMs else null
        trip = trip.copy(
            lastSeenMs = clock.civilMs,
            hasMoving = trip.hasMoving || motion.state == MotionState.MOVING,
            lastServing = identity,
            // Fechas civiles guardadas (informativas); las decisiones usan las monotónicas.
            sameServingSinceMs = if (sameServing) trip.sameServingSinceMs else clock.civilMs,
            staticSinceMs = if (staticSinceElapsed == null) null else trip.staticSinceMs ?: clock.civilMs
        )
        store.addTripCell(trip.id, identity)
        if (arrivalEdge != null) {
            if (arrivalEdge !in store.tripEdges(trip.id)) event = "edge_seen ${arrivalEdge.from}->${arrivalEdge.to}"
            store.addTripEdge(trip.id, arrivalEdge)
        }
        store.updateTrip(trip)
        setAnchor(a.copy(lastSeenElapsedMs = clock.elapsedMs, lastSeenCivilMs = clock.civilMs,
            sameServingSinceElapsedMs = sameServingSinceElapsed, staticSinceElapsedMs = staticSinceElapsed))

        var result = MobilityObservation(historical.familiarity, trip.id, historical.goodEdges, event ?: clockEvent, arrivalPriorTrips)

        val closeReason = when {
            clock.elapsedMs - a.startedElapsedMs >= config.maxTripDurationMs -> MobilityTripCloseReason.MAX_DURATION
            staticSinceElapsed != null && clock.elapsedMs - staticSinceElapsed >= config.staticCloseDurationMs -> MobilityTripCloseReason.STATIC_CONFIRMED
            motion.state != MotionState.MOVING && clock.elapsedMs - sameServingSinceElapsed >= config.sameServingCloseDurationMs -> MobilityTripCloseReason.SAME_SERVING_TIMEOUT
            else -> null
        }
        if (closeReason != null) {
            val committed = close(trip, closeReason, clock.civilMs)
            result = result.copy(event = if (committed) "trip_closed reason=${closeReason.name}" else "trip_discarded reason=${closeReason.name}")
        }
        return result
    }

    /**
     * Al arrancar el proceso. Reanuda el viaje abierto solo con continuidad demostrable: su ancla
     * monotónica, del MISMO arranque, y un hueco dentro de la ventana de reanudación y de la
     * duración máxima, sin salto del reloj civil. Si no, lo cierra como ABANDONED en su última hora
     * civil observada (se confirma solo si ya era válido).
     */
    @Synchronized fun recover(clock: MobilityClock): MobilityObservation {
        if (!enabled) return MobilityObservation(MobilityFamiliarity.UNKNOWN_ON_ROUTE)
        recovered = true
        val trip = store.openTrip()
        if (trip == null) { setAnchor(null, force = true); return MobilityObservation(MobilityFamiliarity.UNKNOWN_ON_ROUTE) }
        val saved = anchors.load()?.takeIf { it.tripId == trip.id }
        if (saved != null && continuityAfterRestart(saved, clock)) {
            anchor = saved; persistedAnchor = saved
            resumedFromElapsedMs = saved.lastSeenElapsedMs
            return MobilityObservation(MobilityFamiliarity.UNKNOWN_ON_ROUTE, trip.id, event = "trip_resumed")
        }
        val committed = close(trip, MobilityTripCloseReason.ABANDONED, trip.lastSeenMs)
        return MobilityObservation(
            MobilityFamiliarity.UNKNOWN_ON_ROUTE,
            trip.id,
            event = if (committed) "trip_closed reason=ABANDONED" else "trip_discarded reason=INVALID_ABANDONED"
        )
    }

    private fun continuityAfterRestart(a: MobilityTripAnchor, clock: MobilityClock): Boolean =
        a.bootId != null && a.bootId == clock.bootId &&
            clock.elapsedMs - a.lastSeenElapsedMs in 0..config.resumeWindowMs &&
            clock.elapsedMs - a.startedElapsedMs < config.maxTripDurationMs &&
            !civilJumped(a, clock)

    /** El civil avanzó distinto del monotónico desde la última observación (en cualquier sentido). */
    private fun civilJumped(a: MobilityTripAnchor, clock: MobilityClock): Boolean {
        val expected = a.lastSeenCivilMs + (clock.elapsedMs - a.lastSeenElapsedMs)
        return kotlin.math.abs(clock.civilMs - expected) > CIVIL_JUMP_TOLERANCE_MS
    }

    private fun recoverIfNeeded(clock: MobilityClock) { if (!recovered) recover(clock) }

    /**
     * Guarda el ancla. Para no escribir en cada lectura, la hora de la última observación se guarda
     * como mucho cada [ANCHOR_PERSIST_INTERVAL_MS]: tras un reinicio el hueco parece algo mayor, que
     * es lo conservador. Los cambios de viaje o de referencias se guardan siempre.
     */
    private fun setAnchor(next: MobilityTripAnchor?, force: Boolean = false) {
        anchor = next
        val saved = persistedAnchor
        val mustSave = force || next == null || saved == null || saved.tripId != next.tripId ||
            saved.sameServingSinceElapsedMs != next.sameServingSinceElapsedMs ||
            saved.staticSinceElapsedMs != next.staticSinceElapsedMs ||
            next.lastSeenElapsedMs - saved.lastSeenElapsedMs >= ANCHOR_PERSIST_INTERVAL_MS
        if (!mustSave) return
        anchors.save(next)
        persistedAnchor = next
    }

    private fun close(trip: MobilityTrip, reason: MobilityTripCloseReason, closedAtMs: Long): Boolean {
        val valid = trip.hasMoving && store.tripCells(trip.id).size >= config.minimumDistinctCells
        if (valid) store.commitMobilityTrip(trip.id, reason, closedAtMs)
        else store.discardMobilityTrip(trip.id, reason, closedAtMs)
        store.pruneMobilityTripDetails(closedAtMs - config.closedTripDetailRetentionMs)
        setAnchor(null, force = true)
        return valid
    }

    companion object {
        /** Desviación civil/monotónica tolerada (correcciones NTP pequeñas). Más allá, salto de reloj. */
        const val CIVIL_JUMP_TOLERANCE_MS = 120_000L
        const val ANCHOR_PERSIST_INTERVAL_MS = 30_000L
        private const val SAME_BOOT = "test-boot"
    }
}
