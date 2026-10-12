package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0 — Consulta de Stable-Site: política sin cambios, progreso, asociación con rutas y paginación. */
class StableSiteQueryTest {

    /** Copia literal de la regla anterior a nombrar los umbrales: la refactorización no cambia nada. */
    private fun previousPolicy(sd: Int, st: Int, fn: Int, rf: Int, ob: Int): StableSiteMaturity {
        val capability = when {
            fn >= 3 -> NeighbourEvidenceCapability.FULL_NEIGHBOUR_IDENTITY
            rf > 0 -> NeighbourEvidenceCapability.RF_NEIGHBOUR_ONLY
            fn > 0 -> NeighbourEvidenceCapability.FULL_NEIGHBOUR_IDENTITY
            else -> NeighbourEvidenceCapability.NO_NEIGHBOUR_DATA
        }
        val ready = fn >= 3 || rf >= 3
        val state = when {
            sd == 0 -> StableSiteFeatureState.BOOTSTRAP
            sd < 5 || st < 2 -> StableSiteFeatureState.LEARNING
            sd < 7 || st < 3 || !ready || ob < 30 -> StableSiteFeatureState.SHADOW_READY
            else -> StableSiteFeatureState.ACTIVE
        }
        val reason = when {
            state == StableSiteFeatureState.ACTIVE -> "MATURE_${capability.name}"
            sd < 7 -> "NEEDS_SERVING_DAYS"; st < 3 -> "NEEDS_STATIC_DAYS"; ob < 30 -> "NEEDS_SERVING_OBSERVATIONS"
            fn in 1..2 -> "NEEDS_FULL_NEIGHBOUR_DAYS"; rf in 1..2 -> "NEEDS_RF_NEIGHBOUR_DAYS"
            !ready && capability == NeighbourEvidenceCapability.NO_NEIGHBOUR_DATA -> "NO_USABLE_NEIGHBOUR_DATA"
            else -> "MATURE"
        }
        return StableSiteMaturity(state, capability, reason)
    }

    @Test fun `named thresholds keep exactly the same policy`() {
        for (sd in 0..9) for (st in 0..5) for (fn in 0..5) for (rf in 0..5) for (ob in listOf(0, 29, 30, 31, 500)) {
            assertEquals("$sd/$st/$fn/$rf/$ob", previousPolicy(sd, st, fn, rf, ob), StableSiteMaturityPolicy.evaluate(sd, st, fn, rf, ob))
        }
    }

    @Test fun `progress uses the policy thresholds and the state is the current one`() {
        val s = StableSiteSummary("k", servingObservations = 12, servingDays = 4, staticDays = 3, fullNeighbourDays = 0, rfNeighbourDays = 0, firstSeenMs = 1, lastSeenMs = 2)
        assertEquals(StableSiteProgress.Item(4, StableSiteMaturityPolicy.ACTIVE_SERVING_DAYS), StableSiteProgress.servingDays(s))
        assertEquals(StableSiteProgress.Item(12, StableSiteMaturityPolicy.ACTIVE_SERVING_OBSERVATIONS), StableSiteProgress.servingObservations(s))
        assertEquals(1f, StableSiteProgress.staticDays(s).fraction, 0f)
        assertEquals(StableSiteFeatureState.LEARNING, s.maturity.state)
        assertEquals("sitio sin vecinas", NeighbourEvidenceCapability.NO_NEIGHBOUR_DATA, s.maturity.capability)
    }

    private fun episode(id: String, site: String?, from: String?, to: String, at: Long, recorded: Boolean = false) = StableSiteEpisode(
        id, site, from, to, at, at + 1000, null, null, recorded, null, null, null, null, null, null, null, null, false
    )

    @Test fun `routes associate by stored identities and time, sites kept apart`() {
        val candidates = listOf(
            episode("1", "siteA", "A", "B", 1_000),
            episode("2", "siteB", "A", "B", 5_000, recorded = true),
            episode("3", null, "A", "B", 3_000),
            episode("4", "siteA", "A", "C", 2_000),                         // otra ruta
            episode("5", "siteA", "X", "B", 2_000),                         // otra celda anterior
            episode("6", "siteA", "A", "B", 10_000 + StableSiteQuery.ROUTE_ASSOCIATION_TOLERANCE_MS + 1)  // posterior a la ruta
        )
        val groups = RouteAssociation.associate("A", "B", routeLastSeenMs = 10_000, candidates = candidates)
        assertEquals(listOf("siteB", "siteA", null), groups.map { it.siteKey })
        assertEquals(listOf("1"), groups.first { it.siteKey == "siteA" }.episodes.map { it.episodeId })
        assertEquals(listOf("3"), groups.last().episodes.map { it.episodeId })
        assertTrue("sin asociación", RouteAssociation.associate("A", "Z", 10_000, candidates).isEmpty())
        assertTrue(RouteAssociation.associate("A", "B", 10_000, emptyList()).isEmpty())
    }

    @Test fun `filtered paging never loses rows and stops`() {
        val all = (1..23).map { it }                                      // 23 filas, de más nueva a más vieja
        fun fetch(after: StableSiteQuery.PageCursor?): List<Int> {
            val from = after?.key?.toInt() ?: 0
            return all.filter { it > from }.take(5 + 1)
        }
        val seen = mutableListOf<Int>()
        var cursor: StableSiteQuery.PageCursor? = null
        var pages = 0
        do {
            val page = StableSiteQuery.filteredPage(cursor, ::fetch, { StableSiteQuery.PageCursor(0, it.toString()) }, keep = { it % 3 == 0 }, pageSize = 5)
            seen += page.items
            cursor = page.next
            pages++
            assertTrue(pages < 10)
        } while (page.hasMore)
        assertEquals(all.filter { it % 3 == 0 }, seen)
        val none = StableSiteQuery.filteredPage(null, ::fetch, { StableSiteQuery.PageCursor(0, it.toString()) }, keep = { false }, pageSize = 5, maxBatches = 2)
        assertTrue(none.items.isEmpty() && none.hasMore)                 // acotado: devuelve control y permite seguir
    }

    @Test fun `stale responses are rejected and appends do not repeat`() {
        val token = StableSiteQuery.RequestToken("site:A|ALL", 2)
        assertTrue(StableSiteQuery.accepts(token, StableSiteQuery.RequestToken("site:A|ALL", 2)))
        assertFalse(StableSiteQuery.accepts(token, StableSiteQuery.RequestToken("site:B|ALL", 2)))
        assertFalse(StableSiteQuery.accepts(token, StableSiteQuery.RequestToken("site:A|ALL", 1)))
        assertEquals(listOf("a", "b", "c"), StableSiteQuery.append(listOf("a", "b"), listOf("b", "c", "c")) { it })
    }

    @Test fun `queries only read, never coordinates`() {
        val c = StableSiteQuery.PageCursor(5, "k")
        listOf(StableSiteQuery.sitesSql(c), StableSiteQuery.cellsSql("s", c), StableSiteQuery.rfSql("s", c),
            StableSiteQuery.episodesSql("s", StableSiteQuery.EpisodeFilter.SHADOW_RECORDED, c), StableSiteQuery.routeEpisodesSql("A", "B")
        ).forEach { (sql, _) ->
            assertTrue(sql.startsWith("SELECT "))
            listOf("INSERT", "UPDATE", "DELETE", "DROP").forEach { assertFalse(sql.uppercase().contains(it)) }
            listOf(" lat", " lon", "latitude", "longitude").forEach { assertFalse(sql.contains(it)) }
        }
        assertTrue(StableSiteQuery.episodesSql("s", StableSiteQuery.EpisodeFilter.SHADOW_RECORDED, null).first.contains("e.shadow_recorded=1"))
        assertFalse(StableSiteQuery.episodesSql("s", StableSiteQuery.EpisodeFilter.ALL, null).first.contains("shadow_recorded=1"))
    }
}
