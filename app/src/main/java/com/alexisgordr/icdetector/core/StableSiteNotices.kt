package com.alexisgordr.icdetector.core

/**
 * 3.0 — Avisos silenciosos de Stable-Site.
 *
 * Stable-Site sigue en modo sombra: STABLE_SITE_ENFORCEMENT_ENABLED no cambia, no hay retenciones,
 * y nada de esto toca securityScore, el bayesiano, LocalCellTrust, TemporalConfidence ni las
 * alarmas. Lo único nuevo es una notificación de importancia baja, sin sonido ni vibración, cuando
 * un lugar ya maduro registra un cambio de celda que sus criterios actuales consideran inusual.
 *
 * Qué episodios pueden avisar: solo los que EMPEZARON con el sitio ya maduro. Cuando el servicio ve
 * el cambio de servidora (el ciclo que abre el episodio), anota si el sitio evaluado en ese mismo
 * ciclo estaba ACTIVE; si lo estaba, el episodio queda «pendiente de aviso» ([PREF_PENDING_EPISODE]).
 * Un episodio que empezó en aprendizaje, sin ubicación fiable o antes de esta versión no queda
 * pendiente y no avisa nunca, aunque el sitio madure mientras sigue abierto. Basta una preferencia
 * porque solo hay un episodio abierto a la vez: abrir uno cierra los demás.
 *
 * Cuándo se avisa ([decide]), todo a la vez:
 *  - el episodio de la evaluación es el pendiente;
 *  - el lugar sigue maduro (ACTIVE según StableSiteMaturityPolicy, sin recalibrar);
 *  - el teléfono está quieto (STATIC_CONFIRMED). Ese estado ya exige fixes recientes y precisos:
 *    el clasificador ignora fixes de más de 60 s (reloj monotónico) y los de mala precisión, y la
 *    clave del lugar solo existe con una ubicación de precisión ≤ 50 m;
 *  - hay datos para comparar (lugar y celda servidora anterior) y se cumplen los criterios de
 *    desviación que ya existían (StableSiteEvaluator.wouldTrigger).
 * Un episodio que empezó en movimiento en un sitio maduro avisa cuando después se evalúa quieto.
 *
 * Como mucho un aviso por episodio: el pendiente se borra (de forma síncrona) ANTES de intentar
 * notificar, así que ni otra lectura, ni abrir la app, ni reiniciar el proceso lo repiten. Si la
 * preferencia está apagada se consume igual, sin publicar: encenderla después no reproduce
 * episodios.
 * Una restauración borra el pendiente: los episodios restaurados no avisan. La madurez nunca cambia
 * la preferencia, solo el estado mostrado. Sin migración: una preferencia interna, no copiada.
 */
object StableSiteNotices {
    const val PREF_ENABLED = "stable_site_silent_notices"
    const val DEFAULT_ENABLED = true

    /** Id del episodio abierto que empezó con el sitio maduro y aún no ha avisado. No viaja en la copia. */
    const val PREF_PENDING_EPISODE = "stable_site_notice_pending_episode"

    /** Extra del Intent de la notificación: el episodio que hay que abrir. */
    const val EXTRA_EPISODE_ID = "com.alexisgordr.icdetector.STABLE_SITE_EPISODE_ID"

    /** Línea del terminal (en castellano, como las demás; se traduce al mostrarla). */
    const val LOG_POSTED = "Aviso silencioso publicado: cambio inusual de celda en este lugar con el teléfono quieto. No confirma un ataque."

    enum class Status { LEARNING, READY, DISABLED_BY_USER }

    /** Lo que se enseña al usuario. La madurez nunca cambia la preferencia. */
    fun status(state: StableSiteFeatureState, enabled: Boolean): Status = when {
        !enabled -> Status.DISABLED_BY_USER
        state == StableSiteFeatureState.ACTIVE -> Status.READY
        else -> Status.LEARNING
    }

    /**
     * Pendiente tras observar la servidora. Solo cambia en el ciclo que abre un episodio: pasa a ese
     * episodio si el sitio evaluado en ese ciclo estaba maduro, y si no se vacía. Sin episodio
     * abierto también se vacía. En cualquier otro caso se conserva.
     */
    fun pendingAfterObservation(pending: String?, observation: StableSiteEpisodeObservation, siteAtStart: StableSiteDecision): String? = when {
        observation.created -> observation.episodeId.takeIf {
            siteAtStart.siteKey != null && siteAtStart.featureState == StableSiteFeatureState.ACTIVE
        }
        observation.episodeId == null -> null
        else -> pending
    }

    enum class Action {
        /** No hacer nada: el pendiente se conserva. */
        NONE,
        /** Consumir el pendiente sin publicar (preferencia apagada). */
        CONSUME_SILENTLY,
        /** Consumir el pendiente y publicar el aviso. */
        NOTIFY
    }

    fun decide(decision: StableSiteDecision, enabled: Boolean, pendingEpisodeId: String?): Action {
        val evidence = decision.evidence ?: return Action.NONE
        val eligible = pendingEpisodeId != null && decision.episodeId == pendingEpisodeId &&
            decision.wouldTrigger &&
            decision.featureState == StableSiteFeatureState.ACTIVE &&
            decision.siteKey != null &&
            evidence.motion.state == MotionState.STATIC_CONFIRMED &&
            evidence.previousIdentity != null
        return when {
            !eligible -> Action.NONE
            enabled -> Action.NOTIFY
            else -> Action.CONSUME_SILENTLY
        }
    }

    /** Id de notificación propio de cada episodio, fuera del rango de los ids fijos (202–205). */
    fun notificationId(episodeId: String): Int = 1_000_000 + (episodeId.hashCode() and 0x0FFFFF)
}
