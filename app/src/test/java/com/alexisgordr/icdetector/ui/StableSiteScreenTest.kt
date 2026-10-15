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
        assertTrue(screen.contains("LaunchedEffect(site.siteKey, technical, techSection)"))
        assertTrue(screen.contains("LaunchedEffect(site.siteKey, episodeFilter)"))
        assertFalse(screen.contains("runCatching"))
        // 3.0 — La tercera es la carga del episodio de un aviso silencioso (StableSiteEpisodeFocus).
        assertEquals(3, Regex("""catch \(e: CancellationException\) \{\s*throw e""").findAll(screen).count())
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
        assertTrue(history.contains("RouteDetailPanel(dbHelper, route, routeStatusLabel(route), modifier) { selectedRoute = null }"))
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
        assertTrue(es.getValue("stable_site_changes_note").contains("No son alertas"))
        val keys = en.keys.filter { it.startsWith("stable_site_") || it.startsWith("stable_site_state") ||
            it.startsWith("stable_site_capability") || it.startsWith("route_detail") || it == "view_stable_site" }
        assertTrue(keys.size >= 60)
        keys.forEach { k ->
            assertTrue("$k sin traducción", es.containsKey(k))
            listOf(en.getValue(k), es.getValue(k)).forEach { t ->
                listOf("attack", "ataque", "IMSI", "catcher", "anomal").forEach { assertFalse("$k menciona $it", t.contains(it, ignoreCase = true)) }
            }
        }
    }

    @Test fun `plain states, reasons and missing data read the same in both languages`() {
        val es = strings("values-es")
        val en = strings("values")
        mapOf(
            "stable_site_simple_learning" to ("Aprendiendo este lugar" to "Learning this place"),
            "stable_site_simple_preparing" to ("Referencia en preparación" to "Reference being prepared"),
            "stable_site_simple_ready" to ("Referencia preparada" to "Reference prepared"),
            "stable_site_simple_not_enough" to ("Sin datos suficientes" to "Not enough data"),
            "stable_site_analysis_only" to ("Solo análisis: no modifica las alarmas." to "Analysis only: it does not change alarms."),
            "stable_site_show_technical" to ("VER DETALLES TÉCNICOS" to "VIEW TECHNICAL DETAILS"),
            "stable_site_kind_observed" to ("Cambio observado" to "Observed change"),
            "stable_site_kind_novelty" to ("Novedad registrada en sombra" to "Novelty recorded in shadow"),
            "stable_site_no_info" to ("Sin información suficiente" to "Not enough information")
        ).forEach { (k, v) -> assertEquals(k, v.first, es.getValue(k)); assertEquals(k, v.second, en.getValue(k)) }
        assertTrue(es.getValue("stable_site_simple_ready_note").contains("no una protección activa"))
        assertTrue(en.getValue("stable_site_simple_ready_note").contains("not an active protection"))
        assertTrue("la marca es del episodio, no de la última evaluación",
            es.getValue("stable_site_kind_novelty_note").contains("aunque la última evaluación ya no lo cumpliera"))
        assertTrue(es.getValue("stable_site_requirements_note").contains("no es una cuenta atrás"))
        listOf("stable_site_reason_few_prior_sightings", "stable_site_reason_conditions_not_met", "stable_site_reason_hold",
            "stable_site_reason_no_location", "stable_site_reason_no_site_data").forEach {
            assertTrue(it, es.getValue(it).startsWith("Motivo: ") && en.getValue(it).startsWith("Reason: "))
            listOf("NEW_SERVING", "INSUFFICIENT", "HOLD", "NO_SITE").forEach { code -> assertFalse("$it deja el código $code", es.getValue(it).contains(code)) }
        }
        // Motivos sin afirmar más de lo guardado: una novedad también puede darse en SHADOW_READY, e
        // INSUFFICIENT_CONTEXT también cuando hay datos pero la celda es conocida u otra condición falla.
        assertEquals("Motivo: la celda tenía pocos antecedentes en este lugar mientras el móvil estaba quieto.",
            es.getValue("stable_site_reason_few_prior_sightings"))
        assertEquals("Reason: the cell had little prior history at this place while the phone was still.",
            en.getValue("stable_site_reason_few_prior_sightings"))
        assertEquals("Motivo: no se cumplieron las condiciones para registrar una novedad en esta evaluación.",
            es.getValue("stable_site_reason_conditions_not_met"))
        assertEquals("Reason: the conditions to record a novelty were not met in this evaluation.",
            en.getValue("stable_site_reason_conditions_not_met"))
        listOf(en, es).forEach { m ->
            listOf("stable_site_reason_few_prior_sightings", "stable_site_reason_conditions_not_met").forEach { k ->
                listOf("preparada", "prepared", "contexto", "context", "referencia", "reference").forEach {
                    assertFalse("$k dice $it", m.getValue(k).contains(it, ignoreCase = true))
                }
            }
        }
        // Formatos iguales en ambos idiomas: «X de Y» con los mismos argumentos.
        listOf("stable_site_requirement_met", "stable_site_requirement_pending", "stable_site_state_then", "stable_site_motion_then",
            "stable_site_simple_place_seen", "stable_site_route_simple").forEach { k ->
            val args = Regex("""%\d\$[sd]""")
            assertEquals(k, args.findAll(en.getValue(k)).map { it.value }.toList(), args.findAll(es.getValue(k)).map { it.value }.toList())
        }
        listOf(en, es).forEach { m -> m.filterKeys { it.startsWith("stable_site_") }.forEach { (k, t) -> assertFalse("$k usa porcentaje", t.contains("%%") || Regex("""\d\s?%""").containsMatchIn(t)) } }
    }

    @Test fun `the plain view keeps two levels and episodes never borrow current data`() {
        val screen = ui("StableSiteScreen.kt")
        val list = screen.substringAfter("fun StableSitePanel(").substringBefore("private fun AnalysisOnly")
        val detail = screen.substringAfter("private fun StableSiteDetail(").substringBefore("private fun Progress(")
        val card = screen.substringAfter("private fun EpisodeCard(").substringBefore("private fun yesNo(")
        val route = screen.substringAfter("fun RouteDetailPanel(").substringBefore("/** Clave guardada abreviada")
        listOf(list, detail, route).forEach { assertTrue(it.contains("AnalysisOnly()")) }
        listOf(detail, card, route).forEach {
            assertTrue(it.contains("R.string.stable_site_show_technical") && it.contains("if (technical)"))
        }
        // Lo técnico solo dentro del desplegable.
        val detailSimple = detail.substringBefore("if (technical) {")
        listOf("site.siteKey,", "stateLabel(m.state)", "capabilityLabel(", "R.string.stable_site_view_section_cells", "StableSiteProgress.").forEach {
            assertFalse("$it fuera de los detalles técnicos", detailSimple.substringAfter("LazyColumn(").contains(it))
        }
        assertTrue(detail.contains("StableSitePresentation.requirements(site)"))
        val cardSimple = card.substringBefore("if (technical) {")
        listOf("e.candidateIdentity", "e.reason ?:", "shadow_recorded=").forEach { assertFalse("$it fuera de los detalles técnicos", cardSimple.contains(it)) }
        // El episodio: tipo por su marca, última evaluación aparte, nada del estado actual del sitio.
        assertTrue(card.contains("StableSitePresentation.episodeKind(e)"))
        assertTrue(card.contains("if (e.evaluatedMs == null) {\n                InfoLine(noInfo, Dim)"))
        assertTrue(card.contains("StableSitePresentation.simpleStateOf(e.featureStateAtEpisode)"))
        listOf("site.", "maturity", "StableSiteMaturityPolicy", "System.currentTimeMillis").forEach { assertFalse("la tarjeta usa $it", card.contains(it)) }
        // Las rutas conservan los grupos por sitio y «Sin sitio asociado».
        assertTrue(route.contains("R.string.stable_site_view_no_site") && route.contains("R.string.stable_site_route_group_count"))
    }

    @Test fun `private classes have unique names in the ui package`() {
        val dir = File(app, "java/com/alexisgordr/icdetector/ui")
        val names = dir.listFiles()!!.filter { it.extension == "kt" }.flatMap { f ->
            Regex("""(?m)^private (?:enum |data )?(?:class|object) (\w+)""").findAll(f.readText()).map { it.groupValues[1] }.toList()
        }
        assertEquals("dos clases privadas con el mismo nombre no compilan", names.toSet().size, names.size)
    }
}
