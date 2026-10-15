package com.alexisgordr.icdetector.storage

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.alexisgordr.icdetector.core.RouteSiteGroup
import com.alexisgordr.icdetector.core.StableSiteCell
import com.alexisgordr.icdetector.core.StableSiteEpisode
import com.alexisgordr.icdetector.core.StableSiteQuery
import com.alexisgordr.icdetector.core.StableSiteQuery.Page
import com.alexisgordr.icdetector.core.StableSiteQuery.PageCursor
import com.alexisgordr.icdetector.core.StableSiteRfFingerprint
import com.alexisgordr.icdetector.core.StableSiteSummary
import com.alexisgordr.icdetector.models.RadioTech

/**
 * 3.0 — Lectura de Stable-Site para Historial → Topología. Solo SELECT sobre las tablas existentes:
 * no escribe, no cambia el aprendizaje ni el esquema. Las consultas están en [StableSiteQuery].
 */
object StableSiteReadStore {

    fun sites(db: SQLiteDatabase, filter: StableSiteQuery.SiteFilter, start: PageCursor?): Page<StableSiteSummary> =
        StableSiteQuery.filteredPage(
            start = start,
            fetch = { after -> query(db, StableSiteQuery.sitesSql(after)) { c ->
                StableSiteSummary(
                    siteKey = c.str("site_key")!!, servingObservations = c.int("serving_observations") ?: 0,
                    servingDays = c.int("serving_days") ?: 0, staticDays = c.int("static_days") ?: 0,
                    fullNeighbourDays = c.int("full_neighbour_days") ?: 0, rfNeighbourDays = c.int("rf_neighbour_days") ?: 0,
                    firstSeenMs = c.long("first_seen"), lastSeenMs = c.long("last_seen")
                )
            } },
            cursorOf = { PageCursor(it.lastSeenMs ?: 0L, it.siteKey) },
            keep = { it.maturity.state in filter.states }
        )

    fun cells(db: SQLiteDatabase, siteKey: String, start: PageCursor?): Page<StableSiteCell> =
        StableSiteQuery.filteredPage(
            start = start,
            fetch = { after -> query(db, StableSiteQuery.cellsSql(siteKey, after)) { c ->
                StableSiteCell(c.str("cell_identity")!!, c.str("role")!!, c.int("observations") ?: 0, c.int("days") ?: 0,
                    c.long("first_seen_ms") ?: 0L, c.long("last_seen_ms") ?: 0L)
            } },
            cursorOf = { PageCursor(it.lastSeenMs, it.rowKey) }
        )

    fun rfFingerprints(db: SQLiteDatabase, siteKey: String, start: PageCursor?): Page<StableSiteRfFingerprint> =
        StableSiteQuery.filteredPage(
            start = start,
            fetch = { after -> query(db, StableSiteQuery.rfSql(siteKey, after)) { c ->
                StableSiteRfFingerprint(
                    fingerprint = c.str("fingerprint")!!,
                    radio = RadioTech.entries.firstOrNull { it.name == c.str("radio") } ?: RadioTech.UNKNOWN,
                    arfcn = c.int("arfcn") ?: 0, pci = c.int("pci") ?: 0, mcc = c.str("mcc"), mnc = c.str("mnc"), tac = c.str("tac"),
                    observations = c.int("observations") ?: 0, days = c.int("days") ?: 0,
                    firstSeenMs = c.long("first_seen_ms") ?: 0L, lastSeenMs = c.long("last_seen_ms") ?: 0L
                )
            } },
            cursorOf = { PageCursor(it.lastSeenMs, it.fingerprint) }
        )

    fun episodes(db: SQLiteDatabase, siteKey: String, filter: StableSiteQuery.EpisodeFilter, start: PageCursor?): Page<StableSiteEpisode> =
        StableSiteQuery.filteredPage(
            start = start,
            fetch = { after -> query(db, StableSiteQuery.episodesSql(siteKey, filter, after), ::episode) },
            cursorOf = { PageCursor(it.firstSeenMs, it.episodeId) }
        )

    /** 3.0 — El episodio de un aviso silencioso; null si la retención o un reinicio ya lo borró. */
    fun episodeById(db: SQLiteDatabase, episodeId: String): StableSiteEpisode? =
        query(db, StableSiteQuery.episodeByIdSql(episodeId), ::episode).firstOrNull()

    /** Sitios con episodios de la ruta y su número (criterio de tiempo aplicado en SQL). */
    fun routeSites(db: SQLiteDatabase, fromIdentity: String, toIdentity: String, routeLastSeenMs: Long): List<RouteSiteGroup> =
        query(db, StableSiteQuery.routeSitesSql(fromIdentity, toIdentity, routeLastSeenMs)) { c ->
            RouteSiteGroup(c.str("site_key"), c.int("episodes") ?: 0)
        }

    /** Una página de episodios de la ruta en un sitio (null: sin sitio asociado). */
    fun routeEpisodes(
        db: SQLiteDatabase, fromIdentity: String, toIdentity: String, routeLastSeenMs: Long, siteKey: String?, start: PageCursor?
    ): Page<StableSiteEpisode> = StableSiteQuery.filteredPage(
        start = start,
        fetch = { after -> query(db, StableSiteQuery.routeEpisodesSql(fromIdentity, toIdentity, routeLastSeenMs, siteKey, after), ::episode) },
        cursorOf = { PageCursor(it.firstSeenMs, it.episodeId) }
    )

    private fun episode(c: Cursor) = StableSiteEpisode(
        episodeId = c.str("episode_id")!!, siteKey = c.str("site_key"), previousIdentity = c.str("previous_identity"),
        candidateIdentity = c.str("candidate_identity")!!, firstSeenMs = c.long("first_seen_ms") ?: 0L,
        lastSeenMs = c.long("last_seen_ms") ?: 0L, evaluatedMs = c.long("evaluated_ms"), closedMs = c.long("closed_ms"),
        shadowRecorded = c.int("shadow_recorded") == 1, reason = c.str("reason"), motionState = c.str("motion_state"),
        featureStateAtEpisode = c.str("feature_state"), globallyKnown = c.int("globally_known")?.let { it == 1 },
        knownAtSite = c.int("known_at_site")?.let { it == 1 }, seenAsNeighbour = c.int("seen_as_neighbour")?.let { it == 1 },
        servingDays = c.int("serving_days"), neighbourDays = c.int("neighbour_days"), corroborated = c.int("corroborated") == 1
    )

    private fun <T> query(db: SQLiteDatabase, q: Pair<String, Array<String>>, read: (Cursor) -> T): List<T> =
        db.rawQuery(q.first, q.second).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    private fun Cursor.str(n: String) = getColumnIndexOrThrow(n).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(n: String) = getColumnIndexOrThrow(n).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.int(n: String) = getColumnIndexOrThrow(n).let { if (isNull(it)) null else getInt(it) }
}
