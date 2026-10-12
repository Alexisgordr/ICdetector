package com.alexisgordr.icdetector.storage

import com.alexisgordr.icdetector.core.StableSiteFeatureState
import com.alexisgordr.icdetector.core.StableSiteMaturityPolicy
import com.alexisgordr.icdetector.core.StableSiteQuery
import com.alexisgordr.icdetector.core.StableSiteQuery.PageCursor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * 3.0 — Consultas de Stable-Site contra las tablas reales (el DDL se lee de CellDbHelper.kt): orden,
 * paginación, contadores iguales a los del export, sitios sin vecinas y episodios sin sitio.
 */
class StableSiteReadQueryTest {
    private lateinit var db: Connection

    private val tables = mapOf(
        "TABLE_SITE_CELLS" to "site_cell_evidence", "TABLE_SITE_DAYS" to "site_cell_days", "TABLE_SITE_EVENTS" to "site_cell_events",
        "TABLE_SITE_MOTION_DAYS" to "site_motion_days", "TABLE_SITE_HOLDS" to "site_holds",
        "TABLE_SITE_SHADOW_TRIGGERS" to "site_shadow_triggers", "TABLE_SITE_RF_NEIGHBOURS" to "site_rf_neighbours",
        "TABLE_SITE_RF_NEIGHBOUR_DAYS" to "site_rf_neighbour_days"
    )

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        val helper = listOf(File("src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt")).first { it.exists() }.readText()
        tables.forEach { (const, name) -> assertTrue(helper.contains("const val $const = \"$name\"")) }
        val ddl = Regex("""db\.execSQL\("(CREATE (?:TABLE|INDEX) IF NOT EXISTS [^"]*\${'$'}TABLE_SITE[^"]*)"\)""").findAll(helper)
            .map { m -> tables.entries.fold(m.groupValues[1]) { s, (k, v) -> s.replace("\$$k", v) } }.toList()
        assertTrue(ddl.size >= 10)
        ddl.forEach(::exec)

        // Sitio maduro con vecinas completas, sitio solo RF, sitio sin vecinas.
        for (d in 1..8) {
            exec("INSERT INTO site_cell_days VALUES ('mature','214-07-1-100-LTE','SERVING','2026-10-0$d')")
            exec("INSERT INTO site_cell_days VALUES ('rfonly','214-07-1-200-LTE','SERVING','2026-10-0$d')")
            exec("INSERT INTO site_rf_neighbour_days VALUES ('rfonly','RFCTX:v1:LTE:1850:7','2026-10-0$d')")
        }
        for (d in 1..4) exec("INSERT INTO site_cell_days VALUES ('mature','214-07-1-101-LTE','NEIGHBOUR','2026-10-0$d')")
        for (d in 1..3) listOf("mature", "rfonly", "bare").forEach { exec("INSERT INTO site_motion_days VALUES ('$it','2026-10-0$d','STATIC_CONFIRMED',10,300,5)") }
        exec("INSERT INTO site_cell_days VALUES ('bare','214-07-1-300-LTE','SERVING','2026-10-01')")
        exec("INSERT INTO site_cell_evidence VALUES ('mature','214-07-1-100-LTE','SERVING',1000,9000,40)")
        exec("INSERT INTO site_cell_evidence VALUES ('mature','214-07-1-101-LTE','NEIGHBOUR',1000,8000,12)")
        exec("INSERT INTO site_cell_evidence VALUES ('rfonly','214-07-1-200-LTE','SERVING',1000,7000,40)")
        exec("INSERT INTO site_cell_evidence VALUES ('bare','214-07-1-300-LTE','SERVING',1000,6000,3)")
        exec("INSERT INTO site_rf_neighbours VALUES ('rfonly','RFCTX:v1:LTE:1850:7','LTE',1850,7,NULL,NULL,NULL,1000,7000,25)")
        // Episodios: uno registrado en sombra, uno solo observado, uno sin sitio (sin ubicación).
        exec("INSERT INTO site_shadow_triggers (episode_id, site_key, previous_identity, candidate_identity, first_seen_ms, last_seen_ms, shadow_recorded, feature_state, corroborated) VALUES ('e1','mature','214-07-1-100-LTE','214-07-1-999-LTE',5000,5100,1,'SHADOW_READY',0)")
        exec("INSERT INTO site_shadow_triggers (episode_id, site_key, previous_identity, candidate_identity, first_seen_ms, last_seen_ms, shadow_recorded, corroborated) VALUES ('e2','mature','214-07-1-100-LTE','214-07-1-101-LTE',6000,6100,0,1)")
        exec("INSERT INTO site_shadow_triggers (episode_id, previous_identity, candidate_identity, first_seen_ms, last_seen_ms) VALUES ('e3','214-07-1-100-LTE','214-07-1-999-LTE',7000,7100)")
    }

    @After fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun <T> rows(q: Pair<String, Array<String>>, read: (ResultSet) -> T): List<T> = db.prepareStatement(q.first).use { st ->
        q.second.forEachIndexed { i, a -> st.setString(i + 1, a) }                          // como Android: texto
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
    }

    @Test fun `site counters match the export definitions and the policy gives the current state`() {
        val sites = rows(StableSiteQuery.sitesSql(null)) { rs ->
            rs.getString("site_key") to StableSiteMaturityPolicy.evaluate(rs.getInt("serving_days"), rs.getInt("static_days"),
                rs.getInt("full_neighbour_days"), rs.getInt("rf_neighbour_days"), rs.getInt("serving_observations"))
        }
        assertEquals(listOf("mature", "rfonly", "bare"), sites.map { it.first })                         // más reciente primero
        assertEquals(StableSiteFeatureState.ACTIVE, sites[0].second.state)
        assertEquals("MATURE_RF_NEIGHBOUR_ONLY", sites[1].second.reason)
        assertEquals("NO_NEIGHBOUR_DATA", sites[2].second.capability.name)
    }

    @Test fun `site pages advance with text-bound cursors`() {
        val first = rows(StableSiteQuery.sitesSql(null, limit = 1)) { it.getString("site_key") to it.getLong("last_seen") }
        assertEquals(2, first.size)
        val second = rows(StableSiteQuery.sitesSql(PageCursor(first[0].second, first[0].first), limit = 1)) { it.getString("site_key") }
        assertEquals("rfonly", second[0])
    }

    @Test fun `cells, RF fingerprints and episodes per site`() {
        assertEquals(listOf("214-07-1-100-LTE|SERVING|8", "214-07-1-101-LTE|NEIGHBOUR|4"),
            rows(StableSiteQuery.cellsSql("mature", null)) { "${it.getString("cell_identity")}|${it.getString("role")}|${it.getInt("days")}" })
        assertEquals(listOf("RFCTX:v1:LTE:1850:7|8"), rows(StableSiteQuery.rfSql("rfonly", null)) { "${it.getString("fingerprint")}|${it.getInt("days")}" })
        assertTrue(rows(StableSiteQuery.rfSql("bare", null)) { it.getString(1) }.isEmpty())
        assertEquals(listOf("e2", "e1"), rows(StableSiteQuery.episodesSql("mature", StableSiteQuery.EpisodeFilter.ALL, null)) { it.getString("episode_id") })
        assertEquals(listOf("e1"), rows(StableSiteQuery.episodesSql("mature", StableSiteQuery.EpisodeFilter.SHADOW_RECORDED, null)) { it.getString("episode_id") })
        val paged = rows(StableSiteQuery.episodesSql("mature", StableSiteQuery.EpisodeFilter.ALL, PageCursor(6000, "e2"))) { it.getString("episode_id") }
        assertEquals(listOf("e1"), paged)
    }

    @Test fun `route groups include episodes without a site, kept as stored`() {
        val sites = rows(StableSiteQuery.routeSitesSql("214-07-1-100-LTE", "214-07-1-999-LTE", 9_000)) { (it.getString("site_key") ?: "-") + ":" + it.getInt("episodes") }
        assertEquals(listOf("mature:1", "-:1"), sites)
        val noSite = rows(StableSiteQuery.routeEpisodesSql("214-07-1-100-LTE", "214-07-1-999-LTE", 9_000, null, null)) { it.getString("episode_id") }
        assertEquals(listOf("e3"), noSite)
        // Ruta vista por última vez antes de que empezaran los episodios (incluido el margen): ninguno.
        val before = 5_000L - StableSiteQuery.ROUTE_ASSOCIATION_TOLERANCE_MS - 1
        assertTrue("posteriores a la ruta", rows(StableSiteQuery.routeSitesSql("214-07-1-100-LTE", "214-07-1-999-LTE", before)) { it.getString(1) }.isEmpty())
    }

    @Test fun `more than 200 route episodes are all reachable through the pages, none twice`() {
        exec("DELETE FROM site_shadow_triggers")
        val from = "214-07-1-1-LTE"; val to = "214-07-1-2-LTE"; val routeLastSeen = 1_000_000L
        val expected = mutableSetOf<String>()
        fun episode(id: String, site: String?, prev: String, cand: String, at: Long) = exec(
            "INSERT INTO site_shadow_triggers (episode_id, site_key, previous_identity, candidate_identity, first_seen_ms, last_seen_ms) " +
                "VALUES ('$id', ${site?.let { "'$it'" } ?: "NULL"}, '$prev', '$cand', $at, $at)")
        // 120 en A, 100 en B y 50 sin sitio; varios comparten milisegundo (desempate por id).
        listOf("A" to 120, "B" to 100, null to 50).forEach { (site, n) ->
            repeat(n) { i -> val id = "${site ?: "none"}-$i"; episode(id, site, from, to, 10_000L + (i / 3) * 1_000L); expected += id }
        }
        // Fuera: posterior al margen de la ruta, otra ruta, otra celda anterior.
        repeat(30) { i -> episode("late-$i", "A", from, to, routeLastSeen + StableSiteQuery.ROUTE_ASSOCIATION_TOLERANCE_MS + 1 + i) }
        repeat(20) { i -> episode("other-$i", "A", from, "214-07-1-3-LTE", 20_000L + i) }
        repeat(20) { i -> episode("prev-$i", "B", "214-07-1-9-LTE", to, 20_000L + i) }

        val groups = rows(StableSiteQuery.routeSitesSql(from, to, routeLastSeen)) { it.getString("site_key") to it.getInt("episodes") }
        assertEquals(listOf("A" to 120, "B" to 100, null to 50), groups)
        val seen = mutableListOf<String>()
        groups.forEach { (site, _) ->
            var cursor: PageCursor? = null
            var pages = 0
            do {
                val page = rows(StableSiteQuery.routeEpisodesSql(from, to, routeLastSeen, site, cursor, limit = 50)) {
                    Triple(it.getString("episode_id"), it.getLong("first_seen_ms"), it.getString("site_key"))
                }
                val shown = page.take(50)
                assertTrue(shown.all { it.third == site })
                seen += shown.map { it.first }
                cursor = shown.lastOrNull()?.let { PageCursor(it.second, it.first) }
                assertTrue("la paginación no avanza", ++pages <= 5)
            } while (page.size > 50)
        }
        assertEquals(270, seen.size)
        assertEquals("ninguno repetido", seen.size, seen.toSet().size)
        assertEquals("ninguno inaccesible ni de más", expected, seen.toSet())
    }
}
