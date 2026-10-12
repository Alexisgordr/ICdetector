package com.alexisgordr.icdetector.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 3.0 — Historial → Topología → Stable-Site: solo lectura, en sombra, sin umbrales copiados, con
 * cargas cancelables y textos que no confunden estado actual, episodio y asociación con rutas.
 */
class StableSiteScreenTest {
    private val app = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun ui(name: String) = File(app, "java/com/alexisgordr/icdetector/ui/$name").readText()
    private fun strings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(app, "res/$dir/strings.xml"))
        val n = doc.getElementsByTagName("string")
        return (0 until n.length).associate { i -> n.item(i).attributes.getNamedItem("name").nodeValue to n.item(i).textContent }
    }

    @Test fun `the screen only reads and raises nothing`() {
        val screen = ui("StableSiteScreen.kt")
        listOf("writableDatabase", "resetStableSiteLearning", "applyStableSiteDecision", "observeStableSiteEpisode", "execSQL",
            "Notification", "ToneGenerator", "securityAlerts", "STABLE_SITE_ENFORCEMENT_ENABLED", "Location", "latitude").forEach {
            assertFalse("la pantalla usa $it", screen.contains(it))
        }
        assertTrue(screen.contains("StableSiteReadStore.sites(dbHelper.readableDatabase"))
    }

    @Test fun `state and progress come from the policy, without copied thresholds`() {
        val screen = ui("StableSiteScreen.kt")
        assertTrue(screen.contains("val m = site.maturity"))
        listOf("servingDays", "staticDays", "fullNeighbourDays", "rfNeighbourDays", "servingObservations").forEach {
            assertTrue(screen.contains("StableSiteProgress.$it(site)"))
        }
        assertFalse(Regex(""">=\s*\d|<\s*\d""").containsMatchIn(screen.substringAfter("private fun Progress").substringBefore("private fun CellRow")))
        assertFalse(screen.contains("StableSiteMaturityPolicy.evaluate"))
    }

    @Test fun `loads are cancelled on site or filter change and stale answers dropped`() {
        val screen = ui("StableSiteScreen.kt")
        val reset = screen.substringAfter("fun reset(newScope: String) {").substringBefore("fun load(")
        assertTrue(reset.contains("job?.cancel()") && reset.contains("generation++"))
        assertTrue(screen.contains("if (!StableSiteQuery.accepts(StableSiteQuery.RequestToken(scope, generation), token)) return@launch"))
        assertTrue(screen.contains("LaunchedEffect(filter) { sites.reset("))
        assertTrue(screen.contains("LaunchedEffect(site.siteKey, section, episodeFilter)"))
        assertFalse(screen.contains("runCatching"))
        assertEquals(2, Regex("""catch \(e: CancellationException\) \{\s*throw e""").findAll(screen).count())
    }

    @Test fun `route detail pages episodes per site in a lazy list, with stored data only`() {
        val screen = ui("StableSiteScreen.kt")
        val detail = screen.substringAfter("fun RouteDetailPanel(").substringBefore("/** Clave guardada abreviada")
        assertTrue(detail.contains("LazyColumn("))
        assertFalse("nada de pintar todos en una Column", detail.contains("episodes.forEach"))
        assertTrue(detail.contains("StableSiteReadStore.routeSites(dbHelper.readableDatabase, route.fromIdentity, route.toIdentity, route.lastSeenMs)"))
        assertTrue(detail.contains("StableSiteReadStore.routeEpisodes(dbHelper.readableDatabase, route.fromIdentity, route.toIdentity, route.lastSeenMs, site, after)"))
        assertTrue(detail.contains("onMore = { loadGroup(group.siteKey, first = false) }"))
        assertTrue(detail.contains("BackHandler(onBack = onBack)"))
        listOf("currentLocation", "getCurrentLocation", "StableSiteReadStore.sites").forEach { assertFalse(detail.contains(it)) }
        val history = ui("HistoryScreen.kt")
        assertTrue(history.contains("TopologyRouteCard(route) { selectedRoute = route }"))
        assertTrue(history.contains("RouteDetailPanel(dbHelper, route, routeStatusLabel(route.lastStatus), modifier) { selectedRoute = null }"))
        assertTrue("se mantienen ambas exportaciones", history.contains("R.string.export_topology") && history.contains("R.string.export_stable_site"))
        assertTrue(history.contains("onClick = { showStableSite = true }"))
        assertTrue(history.contains("StableSitePanel(dbHelper, modifier) { showStableSite = false }"))
    }

    @Test fun `texts say what the data is, in both languages`() {
        val es = strings("values-es")
        val en = strings("values")
        assertEquals("Sin contexto Stable-Site asociado.", es.getValue("stable_site_route_none"))
        assertEquals("Sin sitio asociado", es.getValue("stable_site_view_no_site"))
        assertEquals("EPISODIOS STABLE-SITE DE ESTA RUTA", es.getValue("stable_site_route_title"))
        assertEquals("ÚLTIMA EVALUACIÓN GUARDADA DEL EPISODIO", es.getValue("stable_site_view_last_evaluation"))
        assertTrue(es.getValue("stable_site_view_active_note").contains("sitio maduro, no que se aplique ninguna protección"))
        assertTrue(es.getValue("stable_site_view_shadow_note").contains("no añade alarmas ni bloquea el aprendizaje de confianza"))
        assertTrue(es.getValue("stable_site_view_rf_note").contains("no identidades celulares completas"))
        assertTrue(es.getValue("stable_site_route_note").contains("no una prueba de asociación"))
        assertTrue(es.getValue("stable_site_view_evaluated_at").contains("no es la evidencia inicial ni el estado actual del sitio"))
        assertTrue(es.getValue("stable_site_view_episodes_note").contains("Ninguno es una alarma"))
        val keys = en.keys.filter { it.startsWith("stable_site_view") || it.startsWith("stable_site_route") || it.startsWith("stable_site_state") ||
            it.startsWith("stable_site_capability") || it.startsWith("route_detail") || it == "view_stable_site" }
        assertTrue(keys.size >= 60)
        keys.forEach { k ->
            assertTrue("$k sin traducción", es.containsKey(k))
            listOf(en.getValue(k), es.getValue(k)).forEach { t ->
                listOf("attack", "ataque", "IMSI", "catcher", "anomal").forEach { assertFalse("$k menciona $it", t.contains(it, ignoreCase = true)) }
            }
        }
    }

    @Test fun `private classes have unique names in the ui package`() {
        val dir = File(app, "java/com/alexisgordr/icdetector/ui")
        val names = dir.listFiles()!!.filter { it.extension == "kt" }.flatMap { f ->
            Regex("""(?m)^private (?:enum |data )?(?:class|object) (\w+)""").findAll(f.readText()).map { it.groupValues[1] }.toList()
        }
        assertEquals("dos clases privadas con el mismo nombre no compilan", names.toSet().size, names.size)
    }
}
