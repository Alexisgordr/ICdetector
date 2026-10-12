package com.alexisgordr.icdetector.core

/**
 * 3.0 — Cómo se cuenta Stable-Site a una persona no técnica (Historial → Topología). Pura: traduce
 * estados, requisitos y motivos guardados a categorías sencillas; la interfaz les pone el texto.
 * No calcula nada nuevo: el estado sale de [StableSiteMaturityPolicy] y los umbrales de sus
 * constantes. Nada de esto es una probabilidad ni una alerta, y Stable-Site no modifica las alarmas.
 */
object StableSitePresentation {

    /** Estado en lenguaje sencillo. [READY] es una referencia preparada, no una protección activa. */
    enum class SimpleState { LEARNING_PLACE, REFERENCE_PREPARING, REFERENCE_READY, NOT_ENOUGH_DATA }

    fun simpleState(state: StableSiteFeatureState): SimpleState = when (state) {
        StableSiteFeatureState.BOOTSTRAP -> SimpleState.LEARNING_PLACE
        StableSiteFeatureState.LEARNING -> SimpleState.LEARNING_PLACE
        StableSiteFeatureState.SHADOW_READY -> SimpleState.REFERENCE_PREPARING
        StableSiteFeatureState.ACTIVE -> SimpleState.REFERENCE_READY
        StableSiteFeatureState.UNAVAILABLE -> SimpleState.NOT_ENOUGH_DATA
    }

    /** Estado guardado en un episodio (texto); null si falta o no se reconoce. */
    fun simpleStateOf(stored: String?): SimpleState? =
        StableSiteFeatureState.entries.firstOrNull { it.name == stored }?.let(::simpleState)

    /** Lo que le falta al sitio, traducido del motivo interno de la política. */
    enum class Missing { SERVING_DAYS, STATIC_DAYS, SERVING_OBSERVATIONS, NEIGHBOUR_DAYS, NO_NEIGHBOUR_DATA, NOTHING, UNKNOWN }

    fun missing(maturityReason: String): Missing = when {
        maturityReason == "MATURE" || maturityReason.startsWith("MATURE_") -> Missing.NOTHING
        maturityReason == "NEEDS_SERVING_DAYS" -> Missing.SERVING_DAYS
        maturityReason == "NEEDS_STATIC_DAYS" -> Missing.STATIC_DAYS
        maturityReason == "NEEDS_SERVING_OBSERVATIONS" -> Missing.SERVING_OBSERVATIONS
        maturityReason == "NEEDS_FULL_NEIGHBOUR_DAYS" || maturityReason == "NEEDS_RF_NEIGHBOUR_DAYS" -> Missing.NEIGHBOUR_DAYS
        maturityReason == "NO_USABLE_NEIGHBOUR_DATA" -> Missing.NO_NEIGHBOUR_DATA
        else -> Missing.UNKNOWN
    }

    /** Un requisito de la referencia: cumplido o no, con su valor y su objetivo (no es una cuenta atrás). */
    data class Requirement(val kind: Kind, val met: Boolean, val value: Int, val target: Int) {
        enum class Kind { SERVING_DAYS, STATIC_DAYS, NEIGHBOUR_DAYS, SERVING_OBSERVATIONS }
    }

    /**
     * Requisitos para una referencia preparada, con las constantes de la política. Las vecinas se
     * cumplen con días de vecinas completas o con días de vecinas RF (el mayor de los dos).
     */
    fun requirements(site: StableSiteSummary): List<Requirement> = listOf(
        Requirement(Requirement.Kind.SERVING_DAYS, site.servingDays >= StableSiteMaturityPolicy.ACTIVE_SERVING_DAYS,
            site.servingDays, StableSiteMaturityPolicy.ACTIVE_SERVING_DAYS),
        Requirement(Requirement.Kind.STATIC_DAYS, site.staticDays >= StableSiteMaturityPolicy.ACTIVE_STATIC_DAYS,
            site.staticDays, StableSiteMaturityPolicy.ACTIVE_STATIC_DAYS),
        maxOf(site.fullNeighbourDays, site.rfNeighbourDays).let {
            Requirement(Requirement.Kind.NEIGHBOUR_DAYS, it >= StableSiteMaturityPolicy.ACTIVE_NEIGHBOUR_DAYS, it, StableSiteMaturityPolicy.ACTIVE_NEIGHBOUR_DAYS)
        },
        Requirement(Requirement.Kind.SERVING_OBSERVATIONS, site.servingObservations >= StableSiteMaturityPolicy.ACTIVE_SERVING_OBSERVATIONS,
            site.servingObservations, StableSiteMaturityPolicy.ACTIVE_SERVING_OBSERVATIONS)
    )

    /**
     * Tipo de episodio. [NOVELTY_RECORDED_IN_SHADOW] significa que en algún momento del episodio se
     * marcó como novedad en sombra (`shadow_recorded` se conserva aunque la última evaluación ya no
     * lo cumpla). [OBSERVED_CHANGE] es un cambio de celda observado, sin esa marca.
     */
    enum class EpisodeKind { OBSERVED_CHANGE, NOVELTY_RECORDED_IN_SHADOW }

    fun episodeKind(e: StableSiteEpisode) = if (e.shadowRecorded) EpisodeKind.NOVELTY_RECORDED_IN_SHADOW else EpisodeKind.OBSERVED_CHANGE

    /** Motivo de la ÚLTIMA evaluación guardada, traducido. [UNKNOWN]: ausente o no reconocido. */
    enum class EpisodeReason { NEW_CELL_WHILE_STILL_AT_REFERENCE, NOT_ENOUGH_CONTEXT, HOLD_APPLIED, NO_RELIABLE_LOCATION, NO_SITE_DATA, UNKNOWN }

    fun episodeReason(stored: String?): EpisodeReason = when (stored) {
        "NEW_SERVING_WHILE_STATIC_AT_MATURE_SITE" -> EpisodeReason.NEW_CELL_WHILE_STILL_AT_REFERENCE
        "INSUFFICIENT_CONTEXT" -> EpisodeReason.NOT_ENOUGH_CONTEXT
        "STABLE_SITE_NOVELTY_HOLD" -> EpisodeReason.HOLD_APPLIED
        "NO_RELIABLE_LOCATION" -> EpisodeReason.NO_RELIABLE_LOCATION
        "NO_SITE_DATA" -> EpisodeReason.NO_SITE_DATA
        else -> EpisodeReason.UNKNOWN
    }

    /** Movimiento guardado en la última evaluación. [UNKNOWN] también si falta. */
    enum class Motion { STILL, MOVING, UNKNOWN }

    fun motion(stored: String?): Motion = when (stored) {
        MotionState.STATIC_CONFIRMED.name -> Motion.STILL
        MotionState.MOVING.name -> Motion.MOVING
        else -> Motion.UNKNOWN
    }
}
