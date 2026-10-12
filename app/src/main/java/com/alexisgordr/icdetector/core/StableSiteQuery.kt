package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.RadioTech

/**
 * 3.0 — Consulta de Stable-Site (Historial → Topología). Pura: modelos de lectura y consultas SQL de
 * solo lectura sobre las tablas que ya existen. No cambia el aprendizaje, H1–H16, scores, alarmas ni
 * retenciones, y no reconstruye coordenadas: los sitios se muestran por su clave guardada.
 *
 * El estado de un sitio es el ACTUAL, calculado con [StableSiteMaturityPolicy] sobre sus contadores
 * (igual que el export `sites.csv`). El estado guardado en un episodio es el de ESE momento.
 */
object StableSiteQuery {
    const val PAGE_SIZE = 50

    /**
     * Margen de consulta para relacionar episodios con una ruta: el episodio no empieza después de la
     * última vez que se vio la ruta. Es solo un criterio de consulta, no una prueba de asociación.
     */
    const val ROUTE_ASSOCIATION_TOLERANCE_MS = 2L * 60L * 1000L

    /** Filtro de la lista de sitios por estado actual. */
    enum class SiteFilter(val states: Set<StableSiteFeatureState>) {
        ALL(StableSiteFeatureState.entries.toSet()),
        ACTIVE(setOf(StableSiteFeatureState.ACTIVE)),
        SHADOW_READY(setOf(StableSiteFeatureState.SHADOW_READY)),
        LEARNING(setOf(StableSiteFeatureState.LEARNING, StableSiteFeatureState.BOOTSTRAP))
    }

    /** Filtro de episodios: todos los observados, o solo los que quedaron registrados en sombra. */
    enum class EpisodeFilter { ALL, SHADOW_RECORDED }

    /** Cursor de paginación por clave: (instante, clave de desempate), de más reciente a más antiguo. */
    data class PageCursor(val timeMs: Long, val key: String)

    /** Ficha de una petición: lo que se pidió (sitio, filtro) y su generación. */
    data class RequestToken(val scope: String, val generation: Int)

    fun accepts(current: RequestToken, response: RequestToken) = current == response

    fun hasMore(fetched: Int, pageSize: Int = PAGE_SIZE) = fetched > pageSize

    /** Una página ya filtrada, con el cursor desde el que seguir. */
    data class Page<T>(val items: List<T>, val next: PageCursor?, val hasMore: Boolean)

    /**
     * Pide lotes de [pageSize] (+1 para saber si hay más) desde [start] y conserva los que cumplen
     * [keep] hasta reunir una página o acabar. Ninguna fila se pierde: el cursor apunta a la última
     * fila examinada, no a la última conservada. [maxBatches] acota el trabajo de una sola petición.
     */
    fun <T> filteredPage(
        start: PageCursor?,
        fetch: (PageCursor?) -> List<T>,
        cursorOf: (T) -> PageCursor,
        keep: (T) -> Boolean = { true },
        pageSize: Int = PAGE_SIZE,
        maxBatches: Int = 20
    ): Page<T> {
        val items = mutableListOf<T>()
        var cursor = start
        var more = true
        var batches = 0
        while (more && items.size < pageSize && batches < maxBatches) {
            val raw = fetch(cursor)
            val batch = raw.take(pageSize)
            items += batch.filter(keep)
            more = raw.size > pageSize
            if (batch.isNotEmpty()) cursor = cursorOf(batch.last())
            batches++
            if (batch.isEmpty()) more = false
        }
        return Page(items, cursor, more)
    }

    /** Añade una página sin repetir claves ya mostradas. */
    fun <T> append(existing: List<T>, page: List<T>, key: (T) -> String): List<T> {
        val seen = existing.mapTo(HashSet(), key)
        return existing + page.filter { seen.add(key(it)) }
    }

    // Android pasa los argumentos como texto: los instantes se comparan con CAST para no comparar
    // un número con un texto (que en SQLite siempre resulta menor).
    private fun keyset(timeExpr: String, keyExpr: String) =
        "($timeExpr < CAST(? AS INTEGER) OR ($timeExpr = CAST(? AS INTEGER) AND $keyExpr < ?))"

    private fun cursorArgs(c: PageCursor) = listOf(c.timeMs.toString(), c.timeMs.toString(), c.key)

    /**
     * Sitios con los contadores que usa la política (mismas definiciones que el export). El filtro
     * por estado se aplica después, en memoria, con la política: la regla no se copia en SQL.
     */
    fun sitesSql(after: PageCursor?, limit: Int = PAGE_SIZE): Pair<String, Array<String>> {
        val stats = "SELECT s.site_key AS site_key, " +
            "SUM(CASE WHEN s.role='SERVING' THEN s.observations ELSE 0 END) AS serving_observations, " +
            "(SELECT COUNT(DISTINCT d.day) FROM site_cell_days d WHERE d.site_key=s.site_key AND d.role='SERVING') AS serving_days, " +
            "(SELECT COUNT(DISTINCT m.day) FROM site_motion_days m WHERE m.site_key=s.site_key AND m.state='STATIC_CONFIRMED') AS static_days, " +
            "(SELECT COUNT(DISTINCT d.day) FROM site_cell_days d WHERE d.site_key=s.site_key AND d.role='NEIGHBOUR') AS full_neighbour_days, " +
            "(SELECT COUNT(DISTINCT r.day) FROM site_rf_neighbour_days r WHERE r.site_key=s.site_key) AS rf_neighbour_days, " +
            "MIN(s.first_seen_ms) AS first_seen, MAX(s.last_seen_ms) AS last_seen " +
            "FROM site_cell_evidence s GROUP BY s.site_key"
        val where = after?.let { " WHERE " + keyset("last_seen", "site_key") } ?: ""
        val sql = "SELECT * FROM ($stats)$where ORDER BY last_seen DESC, site_key DESC LIMIT ${limit + 1}"
        return sql to (after?.let(::cursorArgs) ?: emptyList()).toTypedArray()
    }

    /** Celdas con identidad completa observadas en el sitio (servidora o vecina). */
    fun cellsSql(siteKey: String, after: PageCursor?, limit: Int = PAGE_SIZE): Pair<String, Array<String>> {
        val key = "(c.cell_identity || '|' || c.role)"
        val where = "c.site_key=?" + (after?.let { " AND " + keyset("c.last_seen_ms", key) } ?: "")
        val sql = "SELECT c.cell_identity, c.role, c.observations, c.first_seen_ms, c.last_seen_ms, " +
            "(SELECT COUNT(*) FROM site_cell_days d WHERE d.site_key=c.site_key AND d.cell_identity=c.cell_identity AND d.role=c.role) AS days, " +
            "$key AS row_key FROM site_cell_evidence c WHERE $where ORDER BY c.last_seen_ms DESC, row_key DESC LIMIT ${limit + 1}"
        return sql to (listOf(siteKey) + (after?.let(::cursorArgs) ?: emptyList())).toTypedArray()
    }

    /** Huellas RF del sitio (sin Cell ID: contexto local, no identidades celulares completas). */
    fun rfSql(siteKey: String, after: PageCursor?, limit: Int = PAGE_SIZE): Pair<String, Array<String>> {
        val where = "r.site_key=?" + (after?.let { " AND " + keyset("r.last_seen_ms", "r.fingerprint") } ?: "")
        val sql = "SELECT r.fingerprint, r.radio, r.arfcn, r.pci, r.mcc, r.mnc, r.tac, r.observations, r.first_seen_ms, r.last_seen_ms, " +
            "(SELECT COUNT(*) FROM site_rf_neighbour_days d WHERE d.site_key=r.site_key AND d.fingerprint=r.fingerprint) AS days " +
            "FROM site_rf_neighbours r WHERE $where ORDER BY r.last_seen_ms DESC, r.fingerprint DESC LIMIT ${limit + 1}"
        return sql to (listOf(siteKey) + (after?.let(::cursorArgs) ?: emptyList())).toTypedArray()
    }

    /** Episodios del sitio, de más reciente a más antiguo, todos o solo los registrados en sombra. */
    fun episodesSql(siteKey: String, filter: EpisodeFilter, after: PageCursor?, limit: Int = PAGE_SIZE): Pair<String, Array<String>> {
        val recorded = if (filter == EpisodeFilter.SHADOW_RECORDED) " AND e.shadow_recorded=1" else ""
        val where = "e.site_key=?$recorded" + (after?.let { " AND " + keyset("e.first_seen_ms", "e.episode_id") } ?: "")
        return "SELECT $EPISODE_COLUMNS FROM site_shadow_triggers e WHERE $where ORDER BY e.first_seen_ms DESC, e.episode_id DESC LIMIT ${limit + 1}" to
            (listOf(siteKey) + (after?.let(::cursorArgs) ?: emptyList())).toTypedArray()
    }

    /**
     * Episodios cuya celda anterior y nueva son las de la ruta, de cualquier sitio o sin sitio. La
     * comprobación de tiempo ([RouteAssociation]) se hace después, con los datos de la ruta.
     */
    fun routeEpisodesSql(fromIdentity: String, toIdentity: String, limit: Int = 200): Pair<String, Array<String>> =
        "SELECT $EPISODE_COLUMNS FROM site_shadow_triggers e WHERE e.previous_identity=? AND e.candidate_identity=? " +
            "ORDER BY e.first_seen_ms DESC, e.episode_id DESC LIMIT $limit" to arrayOf(fromIdentity, toIdentity)

    const val EPISODE_COLUMNS = "e.episode_id, e.site_key, e.previous_identity, e.candidate_identity, e.first_seen_ms, " +
        "e.last_seen_ms, e.evaluated_ms, e.closed_ms, e.shadow_recorded, e.reason, e.motion_state, e.feature_state, " +
        "e.globally_known, e.known_at_site, e.seen_as_neighbour, e.serving_days, e.neighbour_days, e.corroborated"
}

/** Un sitio con sus contadores. Su estado es el actual, calculado con la política. */
data class StableSiteSummary(
    val siteKey: String,
    val servingObservations: Int,
    val servingDays: Int,
    val staticDays: Int,
    val fullNeighbourDays: Int,
    val rfNeighbourDays: Int,
    val firstSeenMs: Long?,
    val lastSeenMs: Long?
) {
    val maturity: StableSiteMaturity get() =
        StableSiteMaturityPolicy.evaluate(servingDays, staticDays, fullNeighbourDays, rfNeighbourDays, servingObservations)
}

/** Progreso hacia ACTIVE con los umbrales de [StableSiteMaturityPolicy]: (valor, objetivo). */
object StableSiteProgress {
    data class Item(val value: Int, val target: Int) {
        val fraction: Float get() = if (target <= 0) 1f else (value.toFloat() / target).coerceIn(0f, 1f)
    }

    fun servingDays(s: StableSiteSummary) = Item(s.servingDays, StableSiteMaturityPolicy.ACTIVE_SERVING_DAYS)
    fun staticDays(s: StableSiteSummary) = Item(s.staticDays, StableSiteMaturityPolicy.ACTIVE_STATIC_DAYS)
    fun fullNeighbourDays(s: StableSiteSummary) = Item(s.fullNeighbourDays, StableSiteMaturityPolicy.ACTIVE_NEIGHBOUR_DAYS)
    fun rfNeighbourDays(s: StableSiteSummary) = Item(s.rfNeighbourDays, StableSiteMaturityPolicy.ACTIVE_NEIGHBOUR_DAYS)
    fun servingObservations(s: StableSiteSummary) = Item(s.servingObservations, StableSiteMaturityPolicy.ACTIVE_SERVING_OBSERVATIONS)
}

/** Celda con identidad completa observada en un sitio. */
data class StableSiteCell(
    val identity: String,
    val role: String,
    val observations: Int,
    val days: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long
) { val rowKey: String get() = "$identity|$role" }

/** Huella RF de una vecina sin Cell ID: tecnología, canal y PCI. No es una identidad celular. */
data class StableSiteRfFingerprint(
    val fingerprint: String,
    val radio: RadioTech,
    val arfcn: Int,
    val pci: Int,
    val mcc: String?,
    val mnc: String?,
    val tac: String?,
    val observations: Int,
    val days: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long
)

/**
 * Un episodio tal como se guardó. Su evidencia es la ÚLTIMA EVALUACIÓN GUARDADA del episodio (se
 * sobrescribe mientras dura; [evaluatedMs] dice cuándo), no la evidencia inicial ni el estado actual
 * del sitio. Los campos ausentes son null y se muestran como no disponibles.
 */
data class StableSiteEpisode(
    val episodeId: String,
    val siteKey: String?,
    val previousIdentity: String?,
    val candidateIdentity: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val evaluatedMs: Long?,
    val closedMs: Long?,
    /** El evaluador marcó este episodio en sombra (`shadow_recorded=1`). No es una alarma. */
    val shadowRecorded: Boolean,
    val reason: String?,
    val motionState: String?,
    val featureStateAtEpisode: String?,
    val globallyKnown: Boolean?,
    val knownAtSite: Boolean?,
    val seenAsNeighbour: Boolean?,
    val servingDays: Int?,
    val neighbourDays: Int?,
    val corroborated: Boolean
)

/**
 * Episodios Stable-Site de una ruta de Topología, solo con datos guardados:
 *  - identidades: la celda anterior y la nueva del episodio son las de la ruta (A→B, mismo formato);
 *  - tiempo: el episodio no empieza después de la última vez que se vio la ruta, con un margen que
 *    es solo un criterio de consulta, no una prueba de asociación.
 * La coincidencia A→B relaciona los episodios con la ruta AGREGADA, pero no permite atribuirlos a
 * una transición individual: la ruta no guarda la fecha de cada cambio. Varios sitios se devuelven
 * separados; los episodios sin `site_key` van aparte como «sin sitio asociado» (sin suponer el
 * motivo). Nunca se usa la ubicación ni el sitio actual.
 */
object RouteAssociation {
    data class SiteGroup(val siteKey: String?, val episodes: List<StableSiteEpisode>)

    fun associate(
        fromIdentity: String,
        toIdentity: String,
        routeLastSeenMs: Long,
        candidates: List<StableSiteEpisode>,
        toleranceMs: Long = StableSiteQuery.ROUTE_ASSOCIATION_TOLERANCE_MS
    ): List<SiteGroup> = candidates
        .filter { it.previousIdentity == fromIdentity && it.candidateIdentity == toIdentity }
        .filter { it.firstSeenMs <= routeLastSeenMs + toleranceMs }
        .groupBy { it.siteKey }
        .map { (site, episodes) -> SiteGroup(site, episodes.sortedByDescending { it.firstSeenMs }) }
        .sortedWith(compareBy<SiteGroup> { it.siteKey == null }.thenByDescending { g -> g.episodes.maxOf { it.firstSeenMs } })
}
