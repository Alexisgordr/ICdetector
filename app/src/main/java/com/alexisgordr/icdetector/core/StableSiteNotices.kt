package com.alexisgordr.icdetector.core

/**
 * 3.0 — Avisos silenciosos de Stable-Site.
 *
 * Stable-Site sigue en modo sombra: STABLE_SITE_ENFORCEMENT_ENABLED no cambia, no hay retenciones,
 * y nada de esto toca securityScore, el bayesiano, LocalCellTrust, TemporalConfidence ni las
 * alarmas. Lo único nuevo es una notificación de importancia baja, sin sonido ni vibración, cuando
 * un lugar ya maduro registra un cambio de celda que sus criterios actuales consideran inusual.
 *
 * Cuándo se avisa ([shouldNotify]), todo a la vez:
 *  - la preferencia está activada (lo está por defecto; si el usuario la apaga, la madurez no la
 *    vuelve a encender: la madurez solo cambia el estado mostrado, nunca la preferencia);
 *  - el lugar está maduro (ACTIVE según StableSiteMaturityPolicy, sin recalibrar);
 *  - el teléfono está quieto (STATIC_CONFIRMED). Ese estado ya exige fixes recientes y precisos:
 *    el clasificador ignora fixes de más de 60 s (reloj monotónico) y los de mala precisión, y la
 *    clave del lugar solo existe con una ubicación de precisión ≤ 50 m;
 *  - hay datos para comparar (lugar y celda servidora anterior) y se cumplen los criterios de
 *    desviación que ya existían (StableSiteEvaluator.wouldTrigger);
 *  - ESTA evaluación es la que registra el episodio en sombra por primera vez
 *    (`shadow_recorded` 0 → 1, un UPDATE condicional y persistente). Por eso hay como mucho un aviso
 *    por episodio: no se repite en cada lectura, al abrir la app ni tras reiniciar el proceso. Un
 *    episodio que empezó en movimiento y se evalúa después en reposo avisa entonces, una vez;
 *  - el episodio empezó después de [PREF_NOTIFY_SINCE_MS]. Ese corte se fija en el primer arranque
 *    del servicio con esta versión y se mueve a «ahora» tras restaurar una copia: los episodios
 *    antiguos o restaurados nunca avisan.
 *
 * Si algo falta, se abstiene y el episodio se guarda igual. Llegar a la madurez no avisa por sí
 * mismo ni reproduce episodios anteriores: uno registrado antes de la madurez ya tiene
 * `shadow_recorded` = 1. Sin migración: el control usa columnas existentes y una preferencia.
 */
object StableSiteNotices {
    const val PREF_ENABLED = "stable_site_silent_notices"
    const val DEFAULT_ENABLED = true

    /** Corte (ms de reloj civil): episodios con first_seen_ms anterior nunca avisan. No viaja en la copia. */
    const val PREF_NOTIFY_SINCE_MS = "stable_site_notices_since_ms"

    /** Extra del Intent de la notificación: el episodio que hay que abrir. */
    const val EXTRA_EPISODE_ID = "com.alexisgordr.icdetector.STABLE_SITE_EPISODE_ID"

    /** Línea del terminal (en castellano, como las demás; se traduce al mostrarla). */
    const val NOTICE_LOG = "Aviso silencioso publicado: cambio inusual de celda en este lugar con el teléfono quieto. No confirma un ataque."

    enum class Status { LEARNING, READY, DISABLED_BY_USER }

    /** Lo que se enseña al usuario. La madurez nunca cambia la preferencia. */
    fun status(state: StableSiteFeatureState, enabled: Boolean): Status = when {
        !enabled -> Status.DISABLED_BY_USER
        state == StableSiteFeatureState.ACTIVE -> Status.READY
        else -> Status.LEARNING
    }

    fun shouldNotify(decision: StableSiteDecision, enabled: Boolean, notifySinceMs: Long?): Boolean {
        val record = decision.episodeRecord ?: return false
        val evidence = decision.evidence ?: return false
        return enabled &&
            notifySinceMs != null && record.firstSeenMs >= notifySinceMs &&
            record.newlyRecorded &&
            decision.wouldTrigger &&
            decision.featureState == StableSiteFeatureState.ACTIVE &&
            decision.siteKey != null &&
            evidence.motion.state == MotionState.STATIC_CONFIRMED &&
            evidence.previousIdentity != null
    }

    /** Id de notificación propio de cada episodio, fuera del rango de los ids fijos (202–205). */
    fun notificationId(episodeId: String): Int = 1_000_000 + (episodeId.hashCode() and 0x0FFFFF)
}
