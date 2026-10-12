package com.alexisgordr.icdetector.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexisgordr.icdetector.R
import com.alexisgordr.icdetector.core.NeighbourEvidenceCapability
import com.alexisgordr.icdetector.core.RouteSiteGroup
import com.alexisgordr.icdetector.core.StableSiteCell
import com.alexisgordr.icdetector.core.StableSiteEpisode
import com.alexisgordr.icdetector.core.StableSiteFeatureState
import com.alexisgordr.icdetector.core.StableSitePresentation
import com.alexisgordr.icdetector.core.StableSiteProgress
import com.alexisgordr.icdetector.core.StableSiteQuery
import com.alexisgordr.icdetector.core.StableSiteQuery.Page
import com.alexisgordr.icdetector.core.StableSiteQuery.PageCursor
import com.alexisgordr.icdetector.core.StableSiteRfFingerprint
import com.alexisgordr.icdetector.core.StableSiteSummary
import com.alexisgordr.icdetector.models.CellTransitionSummary
import com.alexisgordr.icdetector.storage.CellDbHelper
import com.alexisgordr.icdetector.storage.StableSiteReadStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/*
 * 3.0 — Historial → Topología → Stable-Site: consulta de solo lectura de lo que Stable-Site ha
 * aprendido. Stable-Site sigue en sombra: no añade alarmas ni bloquea el aprendizaje de confianza.
 * Nada de esta pantalla escribe en la base, cambia H1–H16, scores, alarmas ni retenciones.
 */

private val Dim = Color(0xFF777777)
private val Body = Color(0xFFCCCCCC)
private val Accent = Color(0xFF80CBC4)

private fun formatMs(ms: Long?): String? = ms?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it)) }

private enum class StableSiteLoadError { FIRST_PAGE, MORE }

/**
 * Una lista paginada con ficha de petición: cambiar de sitio o de filtro ([reset]) cancela la carga
 * pendiente y sube la generación, y una respuesta solo se aplica si su ficha sigue siendo la actual.
 * Los errores quedan como error (con reintento); la cancelación nunca se absorbe.
 */
private class PagedList<T>(private val keyOf: (T) -> String) {
    var items by mutableStateOf<List<T>>(emptyList())
    var hasMore by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<StableSiteLoadError?>(null)
    var loaded by mutableStateOf(false)
    private var next: PageCursor? = null
    private var scope = ""
    private var generation = 0
    var job: Job? = null

    fun reset(newScope: String) {
        job?.cancel()
        generation++
        scope = newScope
        items = emptyList(); hasMore = false; error = null; next = null; loaded = false
    }

    fun load(cs: CoroutineScope, first: Boolean, fetch: (PageCursor?) -> Page<T>) {
        val token = StableSiteQuery.RequestToken(scope, generation)
        val cursor = if (first) null else next
        job?.cancel()
        job = cs.launch {
            loading = true
            error = null
            val result = try {
                Result.success(withContext(Dispatchers.IO) { fetch(cursor) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (!StableSiteQuery.accepts(StableSiteQuery.RequestToken(scope, generation), token)) return@launch
            result.onSuccess { page ->
                items = if (first) page.items else StableSiteQuery.append(items, page.items, keyOf)
                next = page.next
                hasMore = page.hasMore
                loaded = true
            }.onFailure { error = if (first) StableSiteLoadError.FIRST_PAGE else StableSiteLoadError.MORE }
            loading = false
        }
    }
}

/** Elementos de una lista paginada dentro de un LazyColumn: carga, vacío, error y «Cargar más». */
private fun <T> LazyListScope.pagedItems(
    list: PagedList<T>,
    emptyText: Int,
    key: (T) -> String,
    onRetryFirst: () -> Unit,
    onMore: () -> Unit,
    row: @Composable (T) -> Unit
) {
    when {
        list.error == StableSiteLoadError.FIRST_PAGE -> item {
            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.stable_site_view_error), color = Color(0xFFFFA000), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                OutlinedButton(onClick = onRetryFirst, shape = RoundedCornerShape(4.dp)) {
                    Text(stringResource(R.string.profile_history_retry), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
        }
        !list.loaded -> item {
            Text(stringResource(R.string.profile_history_loading), color = Color(0xFF555555), fontFamily = FontFamily.Monospace,
                fontSize = 10.sp, modifier = Modifier.padding(12.dp))
        }
        list.items.isEmpty() && !list.hasMore -> item {
            Text(stringResource(emptyText), color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                modifier = Modifier.padding(12.dp))
        }
        else -> {
            items(list.items, key = key) { row(it) }
            if (list.error == StableSiteLoadError.MORE) item {
                Text(stringResource(R.string.stable_site_view_error_more), color = Color(0xFFFFA000), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            }
            if (list.hasMore) item {
                OutlinedButton(onClick = onMore, enabled = !list.loading, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp)) {
                    Text(stringResource(if (list.error == StableSiteLoadError.MORE) R.string.profile_history_retry else R.string.profile_history_load_more),
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun BackRow(label: Int, onBack: () -> Unit) {
    TextButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Accent)
        Text(stringResource(label), color = Accent, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

@Composable
private fun InfoLine(text: String, color: Color = Body, size: Int = 9) =
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = size.sp, lineHeight = (size + 3).sp)

/**
 * Historial → Topología → Ver Stable-Site: lista de sitios con su estado ACTUAL en lenguaje sencillo
 * y lo que les falta. Los datos técnicos están en el detalle, en «Ver detalles técnicos».
 */
@Composable
fun StableSitePanel(dbHelper: CellDbHelper, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val cs = rememberCoroutineScope()
    var filter by remember { mutableStateOf(StableSiteQuery.SiteFilter.ALL) }
    var selected by remember { mutableStateOf<StableSiteSummary?>(null) }
    val sites = remember { PagedList<StableSiteSummary> { it.siteKey } }
    fun loadSites(first: Boolean) = sites.load(cs, first) { after -> StableSiteReadStore.sites(dbHelper.readableDatabase, filter, after) }
    DisposableEffect(Unit) { onDispose { sites.job?.cancel() } }
    LaunchedEffect(filter) { sites.reset("sites|${filter.name}"); loadSites(first = true) }

    // «Atrás» del sistema: del detalle a la lista y de la lista a Topología (no cierra Historial).
    androidx.activity.compose.BackHandler { if (selected != null) selected = null else onBack() }
    val site = selected
    if (site != null) {
        StableSiteDetail(dbHelper, site, modifier) { selected = null }
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow(R.string.stable_site_view_back_topology, onBack) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.stable_site_simple_title), color = Color.White, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
                AnalysisOnly()
                InfoLine(stringResource(R.string.stable_site_simple_intro), Dim)
                InfoLine(stringResource(R.string.stable_site_view_shadow_note), Dim)
                InfoLine(stringResource(R.string.stable_site_view_key_note), Dim)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StableSiteQuery.SiteFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(stringResource(siteFilterLabel(f)), fontSize = 10.sp) })
                }
            }
        }
        pagedItems(sites, R.string.stable_site_view_no_sites, key = { it.siteKey },
            onRetryFirst = { sites.reset("sites|${filter.name}"); loadSites(first = true) },
            onMore = { loadSites(first = false) }) { s -> SiteCard(s) { selected = s } }
    }
}

/** «Solo análisis: no modifica las alarmas», siempre visible en las vistas de Stable-Site. */
@Composable
private fun AnalysisOnly() = InfoLine(stringResource(R.string.stable_site_analysis_only), Accent, 10)

@Composable
private fun SiteCard(site: StableSiteSummary, onOpen: () -> Unit) {
    val m = site.maturity
    val simple = StableSitePresentation.simpleState(m.state)
    Card(onClick = onOpen, colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
        border = BorderStroke(1.dp, stateColor(m.state).copy(alpha = 0.45f)), shape = RoundedCornerShape(4.dp)) {
        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(simpleStateLabel(simple)), color = stateColor(m.state), fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, fontSize = 11.sp)
            InfoLine(stringResource(missingLabel(StableSitePresentation.missing(m.reason))), Body)
            InfoLine(shortKey(site.siteKey), Dim, 9)
            InfoLine(stringResource(R.string.stable_site_simple_place_seen, formatMs(site.lastSeenMs) ?: stringResource(R.string.stable_site_no_info)), Dim)
        }
    }
}

/**
 * Detalle de un sitio. Arriba, lo sencillo: estado actual, qué falta y qué requisitos se cumplen, y
 * los cambios de celda. En «Ver detalles técnicos»: clave, códigos, contadores, fechas, celdas y
 * huellas RF. No se elimina información: solo cambia dónde se muestra.
 */
@Composable
private fun StableSiteDetail(dbHelper: CellDbHelper, site: StableSiteSummary, modifier: Modifier, onBack: () -> Unit) {
    val cs = rememberCoroutineScope()
    var technical by remember(site.siteKey) { mutableStateOf(false) }
    var techSection by remember(site.siteKey) { mutableIntStateOf(0) }            // 0 celdas, 1 huellas RF
    var episodeFilter by remember(site.siteKey) { mutableStateOf(StableSiteQuery.EpisodeFilter.ALL) }
    val cells = remember(site.siteKey) { PagedList<StableSiteCell> { it.rowKey } }
    val rf = remember(site.siteKey) { PagedList<StableSiteRfFingerprint> { it.fingerprint } }
    val episodes = remember(site.siteKey) { PagedList<StableSiteEpisode> { it.episodeId } }
    fun loadCells(first: Boolean) = cells.load(cs, first) { StableSiteReadStore.cells(dbHelper.readableDatabase, site.siteKey, it) }
    fun loadRf(first: Boolean) = rf.load(cs, first) { StableSiteReadStore.rfFingerprints(dbHelper.readableDatabase, site.siteKey, it) }
    fun loadEpisodes(first: Boolean) = episodes.load(cs, first) {
        StableSiteReadStore.episodes(dbHelper.readableDatabase, site.siteKey, episodeFilter, it)
    }
    DisposableEffect(site.siteKey) { onDispose { cells.job?.cancel(); rf.job?.cancel(); episodes.job?.cancel() } }
    LaunchedEffect(site.siteKey, technical, techSection) {
        if (!technical) return@LaunchedEffect
        when (techSection) {
            0 -> if (!cells.loaded) { cells.reset("cells|${site.siteKey}"); loadCells(true) }
            else -> if (!rf.loaded) { rf.reset("rf|${site.siteKey}"); loadRf(true) }
        }
    }
    LaunchedEffect(site.siteKey, episodeFilter) {
        episodes.reset("episodes|${site.siteKey}|${episodeFilter.name}"); loadEpisodes(true)
    }
    val m = site.maturity
    val simple = StableSitePresentation.simpleState(m.state)

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow(R.string.stable_site_view_back_sites, onBack) }
        // 1. Lo sencillo.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(simpleStateLabel(simple)), color = stateColor(m.state), fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
                InfoLine(stringResource(simpleStateNote(simple)), Body)
                InfoLine(stringResource(missingLabel(StableSitePresentation.missing(m.reason))), Body)
                AnalysisOnly()
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.stable_site_requirements_title), color = Color.White, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 9.sp)
                StableSitePresentation.requirements(site).forEach { r ->
                    InfoLine(stringResource(if (r.met) R.string.stable_site_requirement_met else R.string.stable_site_requirement_pending,
                        stringResource(requirementLabel(r.kind)), r.value, r.target), if (r.met) Accent else Body)
                }
                InfoLine(stringResource(R.string.stable_site_requirements_note), Dim)
            }
        }
        // 2. Detalles técnicos, desplegables.
        item {
            TextButton(onClick = { technical = !technical }) {
                Text(stringResource(if (technical) R.string.stable_site_hide_technical else R.string.stable_site_show_technical),
                    color = Accent, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
        if (technical) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoLine(site.siteKey, Color.White)
                    InfoLine(stringResource(R.string.stable_site_view_current_state, stringResource(stateLabel(m.state)), m.reason), stateColor(m.state))
                    InfoLine(stringResource(R.string.stable_site_view_active_note), Dim)
                    InfoLine(stringResource(capabilityLabel(m.capability)) + " (" + m.capability.name + ") — " + stringResource(capabilityNote(m.capability)), Body)
                    Text(stringResource(R.string.stable_site_view_progress), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                    Progress(R.string.stable_site_view_serving_days, StableSiteProgress.servingDays(site))
                    Progress(R.string.stable_site_view_static_days, StableSiteProgress.staticDays(site))
                    Progress(R.string.stable_site_view_full_neighbour_days, StableSiteProgress.fullNeighbourDays(site))
                    Progress(R.string.stable_site_view_rf_neighbour_days, StableSiteProgress.rfNeighbourDays(site))
                    Progress(R.string.stable_site_view_serving_observations, StableSiteProgress.servingObservations(site))
                    InfoLine(stringResource(R.string.stable_site_view_neighbour_rule), Dim)
                    InfoLine(stringResource(R.string.stable_site_view_seen, formatMs(site.firstSeenMs) ?: "-", formatMs(site.lastSeenMs) ?: "-"), Dim)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(R.string.stable_site_view_section_cells, R.string.stable_site_view_section_rf).forEachIndexed { i, label ->
                            FilterChip(selected = techSection == i, onClick = { techSection = i }, label = { Text(stringResource(label), fontSize = 10.sp) })
                        }
                    }
                }
            }
            if (techSection == 0) {
                pagedItems(cells, R.string.stable_site_view_no_cells, key = { "cell|" + it.rowKey },
                    onRetryFirst = { cells.reset("cells|${site.siteKey}"); loadCells(true) }, onMore = { loadCells(false) }) { CellRow(it) }
            } else {
                item { InfoLine(stringResource(R.string.stable_site_view_rf_note), Dim) }
                pagedItems(rf, R.string.stable_site_view_no_rf, key = { "rf|" + it.fingerprint },
                    onRetryFirst = { rf.reset("rf|${site.siteKey}"); loadRf(true) }, onMore = { loadRf(false) }) { RfRow(it) }
            }
        }
        // 3. Cambios de celda en este lugar (episodios), en lenguaje sencillo.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HorizontalDivider(color = Color(0xFF222222))
                Text(stringResource(R.string.stable_site_changes_title), color = Color.White, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 10.sp)
                InfoLine(stringResource(R.string.stable_site_changes_note), Dim)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = episodeFilter == StableSiteQuery.EpisodeFilter.ALL, onClick = { episodeFilter = StableSiteQuery.EpisodeFilter.ALL },
                        label = { Text(stringResource(R.string.stable_site_changes_all), fontSize = 10.sp) })
                    FilterChip(selected = episodeFilter == StableSiteQuery.EpisodeFilter.SHADOW_RECORDED, onClick = { episodeFilter = StableSiteQuery.EpisodeFilter.SHADOW_RECORDED },
                        label = { Text(stringResource(R.string.stable_site_changes_recorded), fontSize = 10.sp) })
                }
            }
        }
        pagedItems(episodes, R.string.stable_site_view_no_episodes, key = { "episode|" + it.episodeId },
            onRetryFirst = { episodes.reset("episodes|${site.siteKey}|${episodeFilter.name}"); loadEpisodes(true) },
            onMore = { loadEpisodes(false) }) { EpisodeCard(it) }
    }
}

@Composable
private fun Progress(label: Int, item: StableSiteProgress.Item) {
    Column {
        InfoLine(stringResource(label) + ": ${item.value}/${item.target}", Body)
        LinearProgressIndicator(progress = { item.fraction }, modifier = Modifier.fillMaxWidth().height(3.dp),
            color = Accent, trackColor = Color(0xFF252525))
    }
}

@Composable
private fun CellRow(c: StableSiteCell) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        InfoLine(c.identity + " · " + stringResource(if (c.role == "SERVING") R.string.stable_site_view_role_serving else R.string.stable_site_view_role_neighbour), Color.White)
        InfoLine(stringResource(R.string.stable_site_view_cell_counts, c.observations, c.days, formatMs(c.lastSeenMs) ?: "-"), Dim)
    }
}

@Composable
private fun RfRow(r: StableSiteRfFingerprint) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        InfoLine("${r.radio.name} · ARFCN ${r.arfcn} · PCI ${r.pci}" +
            (if (r.mcc != null && r.mnc != null) " · ${r.mcc}-${r.mnc}" else "") + (r.tac?.let { " · TAC $it" } ?: ""), Color.White)
        InfoLine(stringResource(R.string.stable_site_view_cell_counts, r.observations, r.days, formatMs(r.lastSeenMs) ?: "-"), Dim)
    }
}

/**
 * Un episodio en lenguaje sencillo, tal como se guardó:
 *  - «Novedad registrada en sombra» si `shadow_recorded` se marcó EN ALGÚN MOMENTO del episodio;
 *    «Cambio observado» si no. Esa marca es acumulada y no depende de la última evaluación.
 *  - «Última evaluación guardada»: su fecha, el estado del lugar en ESE momento y el motivo
 *    traducido. No es la evidencia inicial ni el estado actual del sitio.
 * Lo que falta se muestra como «Sin información suficiente». Los códigos, identidades y contadores
 * van en «Ver detalles técnicos».
 */
@Composable
private fun EpisodeCard(e: StableSiteEpisode) {
    var technical by remember(e.episodeId) { mutableStateOf(false) }
    val kind = StableSitePresentation.episodeKind(e)
    val novelty = kind == StableSitePresentation.EpisodeKind.NOVELTY_RECORDED_IN_SHADOW
    val border = if (novelty) Color(0xFFB39DDB) else Color(0xFF444444)
    val noInfo = stringResource(R.string.stable_site_no_info)
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)), border = BorderStroke(1.dp, border.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(4.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs(e.firstSeenMs) ?: noInfo, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                Text(stringResource(if (novelty) R.string.stable_site_kind_novelty else R.string.stable_site_kind_observed),
                    color = border, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            }
            InfoLine(stringResource(if (novelty) R.string.stable_site_kind_novelty_note else R.string.stable_site_kind_observed_note), Body)
            HorizontalDivider(color = Color(0xFF222222))
            Text(stringResource(R.string.stable_site_view_last_evaluation), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 8.sp)
            if (e.evaluatedMs == null) {
                InfoLine(noInfo, Dim)
            } else {
                InfoLine(stringResource(R.string.stable_site_view_evaluated_at, formatMs(e.evaluatedMs) ?: noInfo), Dim)
                InfoLine(stringResource(R.string.stable_site_state_then, StableSitePresentation.simpleStateOf(e.featureStateAtEpisode)
                    ?.let { stringResource(simpleStateLabel(it)) } ?: noInfo), Body)
                InfoLine(stringResource(episodeReasonLabel(StableSitePresentation.episodeReason(e.reason))), Body)
                InfoLine(stringResource(R.string.stable_site_motion_then, stringResource(motionLabel(StableSitePresentation.motion(e.motionState)))), Body)
            }
            TextButton(onClick = { technical = !technical }) {
                Text(stringResource(if (technical) R.string.stable_site_hide_technical else R.string.stable_site_show_technical),
                    color = Accent, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            }
            if (technical) {
                val na = stringResource(R.string.profile_history_not_available)
                InfoLine((e.previousIdentity ?: na) + "  →  " + e.candidateIdentity, Body)
                InfoLine(e.siteKey ?: stringResource(R.string.stable_site_view_no_site), Dim)
                InfoLine(stringResource(R.string.stable_site_view_episode_period, formatMs(e.lastSeenMs) ?: "-",
                    formatMs(e.closedMs) ?: stringResource(R.string.stable_site_view_episode_open)), Dim)
                InfoLine("shadow_recorded=${if (e.shadowRecorded) 1 else 0} · feature_state=${e.featureStateAtEpisode ?: "-"} · " +
                    "motion_state=${e.motionState ?: "-"} · reason=${e.reason ?: "-"}", Dim)
                InfoLine(stringResource(R.string.stable_site_view_episode_flags, yesNo(e.knownAtSite), yesNo(e.globallyKnown), yesNo(e.seenAsNeighbour),
                    e.servingDays?.toString() ?: na, e.neighbourDays?.toString() ?: na, yesNo(e.corroborated)), Dim)
            }
        }
    }
}

@Composable
private fun yesNo(v: Boolean?): String = stringResource(when (v) { true -> R.string.stable_site_view_yes; false -> R.string.stable_site_view_no; null -> R.string.profile_history_not_available })

/**
 * Detalle de una ruta de Topología, como vista propia (LazyColumn, nunca todos los episodios a la vez):
 * los datos guardados de la ruta y «Episodios Stable-Site de esta ruta». Primero se leen los sitios
 * con episodios de la ruta (identidades A→B y criterio de tiempo en SQL) y cada sitio pagina sus
 * episodios por separado, con «Cargar más». Se relacionan con la ruta agregada, no con una transición.
 */
@Composable
fun RouteDetailPanel(
    dbHelper: CellDbHelper,
    route: CellTransitionSummary,
    statusText: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val cs = rememberCoroutineScope()
    val routeKey = "${route.fromIdentity}>${route.toIdentity}@${route.lastSeenMs}"
    var groups by remember(routeKey) { mutableStateOf<List<RouteSiteGroup>?>(null) }
    var failed by remember(routeKey) { mutableStateOf(false) }
    var attempt by remember(routeKey) { mutableIntStateOf(0) }
    var technical by remember(routeKey) { mutableStateOf(false) }
    // Una lista paginada por sitio (null = sin sitio asociado), creada al conocer los grupos.
    val lists = remember(routeKey) { mutableMapOf<String?, PagedList<StableSiteEpisode>>() }
    fun listFor(site: String?) = lists.getOrPut(site) { PagedList { it.episodeId } }
    fun loadGroup(site: String?, first: Boolean) = listFor(site).load(cs, first) { after ->
        StableSiteReadStore.routeEpisodes(dbHelper.readableDatabase, route.fromIdentity, route.toIdentity, route.lastSeenMs, site, after)
    }
    DisposableEffect(routeKey) { onDispose { lists.values.forEach { it.job?.cancel() } } }
    LaunchedEffect(routeKey, attempt) {
        failed = false
        groups = null
        val result = try {
            Result.success(withContext(Dispatchers.IO) {
                StableSiteReadStore.routeSites(dbHelper.readableDatabase, route.fromIdentity, route.toIdentity, route.lastSeenMs)
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        result.onSuccess { found ->
            found.forEach { g -> listFor(g.siteKey).reset("route|$routeKey|${g.siteKey}"); loadGroup(g.siteKey, first = true) }
            groups = found
        }.onFailure { failed = true }
    }

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow(R.string.stable_site_view_back_topology, onBack) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.stable_site_route_simple, route.observations), color = Color.White, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 11.sp)
                InfoLine(stringResource(R.string.route_detail_last_seen, formatMs(route.lastSeenMs) ?: "-"), Dim)
                AnalysisOnly()
                TextButton(onClick = { technical = !technical }) {
                    Text(stringResource(if (technical) R.string.stable_site_hide_technical else R.string.stable_site_show_technical),
                        color = Accent, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
                if (technical) {
                    Text(stringResource(R.string.handover_route), color = Dim, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
                    InfoLine(stringResource(R.string.route_detail_identities, route.fromIdentity, route.toIdentity), Color.White)
                    InfoLine(stringResource(R.string.route_detail_counts, route.observations, route.trustedObservations, statusText, route.tripCount), Body)
                }
                HorizontalDivider(color = Color(0xFF222222))
                Text(stringResource(R.string.stable_site_route_title), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                InfoLine(stringResource(R.string.stable_site_route_note), Dim)
            }
        }
        val g = groups
        when {
            failed -> item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InfoLine(stringResource(R.string.stable_site_view_error), Color(0xFFFFA000))
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.profile_history_retry), fontFamily = FontFamily.Monospace, fontSize = 9.sp) }
                }
            }
            g == null -> item { InfoLine(stringResource(R.string.profile_history_loading), Dim) }
            g.isEmpty() -> item { InfoLine(stringResource(R.string.stable_site_route_none), Body) }
            else -> g.forEach { group ->
                val list = listFor(group.siteKey)
                item(key = "header|${group.siteKey}") {
                    Text((group.siteKey?.let { stringResource(R.string.stable_site_view_episode_site, shortKey(it)) }
                        ?: stringResource(R.string.stable_site_view_no_site)) + " · " +
                        stringResource(R.string.stable_site_route_group_count, group.episodes),
                        color = Accent, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                }
                pagedItems(list, R.string.stable_site_view_no_episodes, key = { "${group.siteKey}|${it.episodeId}" },
                    onRetryFirst = { list.reset("route|$routeKey|${group.siteKey}"); loadGroup(group.siteKey, first = true) },
                    onMore = { loadGroup(group.siteKey, first = false) }) { EpisodeCard(it) }
            }
        }
    }
}

/** Clave guardada abreviada para la lista; el detalle muestra la clave completa. */
private fun shortKey(key: String) = if (key.length > 18) key.take(10) + "…" + key.takeLast(6) else key

private fun simpleStateLabel(s: StableSitePresentation.SimpleState): Int = when (s) {
    StableSitePresentation.SimpleState.LEARNING_PLACE -> R.string.stable_site_simple_learning
    StableSitePresentation.SimpleState.REFERENCE_PREPARING -> R.string.stable_site_simple_preparing
    StableSitePresentation.SimpleState.REFERENCE_READY -> R.string.stable_site_simple_ready
    StableSitePresentation.SimpleState.NOT_ENOUGH_DATA -> R.string.stable_site_simple_not_enough
}

private fun simpleStateNote(s: StableSitePresentation.SimpleState): Int = when (s) {
    StableSitePresentation.SimpleState.LEARNING_PLACE -> R.string.stable_site_simple_learning_note
    StableSitePresentation.SimpleState.REFERENCE_PREPARING -> R.string.stable_site_simple_preparing_note
    StableSitePresentation.SimpleState.REFERENCE_READY -> R.string.stable_site_simple_ready_note
    StableSitePresentation.SimpleState.NOT_ENOUGH_DATA -> R.string.stable_site_simple_not_enough_note
}

private fun missingLabel(m: StableSitePresentation.Missing): Int = when (m) {
    StableSitePresentation.Missing.SERVING_DAYS -> R.string.stable_site_missing_serving_days
    StableSitePresentation.Missing.STATIC_DAYS -> R.string.stable_site_missing_static_days
    StableSitePresentation.Missing.SERVING_OBSERVATIONS -> R.string.stable_site_missing_serving_observations
    StableSitePresentation.Missing.NEIGHBOUR_DAYS -> R.string.stable_site_missing_neighbour_days
    StableSitePresentation.Missing.NO_NEIGHBOUR_DATA -> R.string.stable_site_missing_no_neighbour_data
    StableSitePresentation.Missing.NOTHING -> R.string.stable_site_missing_nothing
    StableSitePresentation.Missing.UNKNOWN -> R.string.stable_site_missing_unknown
}

private fun requirementLabel(k: StableSitePresentation.Requirement.Kind): Int = when (k) {
    StableSitePresentation.Requirement.Kind.SERVING_DAYS -> R.string.stable_site_requirement_serving_days
    StableSitePresentation.Requirement.Kind.STATIC_DAYS -> R.string.stable_site_requirement_static_days
    StableSitePresentation.Requirement.Kind.NEIGHBOUR_DAYS -> R.string.stable_site_requirement_neighbour_days
    StableSitePresentation.Requirement.Kind.SERVING_OBSERVATIONS -> R.string.stable_site_requirement_serving_observations
}

private fun episodeReasonLabel(r: StableSitePresentation.EpisodeReason): Int = when (r) {
    StableSitePresentation.EpisodeReason.FEW_PRIOR_SIGHTINGS_WHILE_STILL -> R.string.stable_site_reason_few_prior_sightings
    StableSitePresentation.EpisodeReason.NOVELTY_CONDITIONS_NOT_MET -> R.string.stable_site_reason_conditions_not_met
    StableSitePresentation.EpisodeReason.HOLD_APPLIED -> R.string.stable_site_reason_hold
    StableSitePresentation.EpisodeReason.NO_RELIABLE_LOCATION -> R.string.stable_site_reason_no_location
    StableSitePresentation.EpisodeReason.NO_SITE_DATA -> R.string.stable_site_reason_no_site_data
    StableSitePresentation.EpisodeReason.UNKNOWN -> R.string.stable_site_no_info
}

private fun motionLabel(m: StableSitePresentation.Motion): Int = when (m) {
    StableSitePresentation.Motion.STILL -> R.string.stable_site_motion_still
    StableSitePresentation.Motion.MOVING -> R.string.stable_site_motion_moving
    StableSitePresentation.Motion.UNKNOWN -> R.string.stable_site_no_info
}

private fun stateLabel(s: StableSiteFeatureState): Int = when (s) {
    StableSiteFeatureState.UNAVAILABLE -> R.string.stable_site_state_unavailable
    StableSiteFeatureState.BOOTSTRAP -> R.string.stable_site_state_bootstrap
    StableSiteFeatureState.LEARNING -> R.string.stable_site_state_learning
    StableSiteFeatureState.SHADOW_READY -> R.string.stable_site_state_shadow_ready
    StableSiteFeatureState.ACTIVE -> R.string.stable_site_state_active
}

private fun stateColor(s: StableSiteFeatureState): Color = when (s) {
    StableSiteFeatureState.ACTIVE -> Color(0xFF80CBC4)
    StableSiteFeatureState.SHADOW_READY -> Color(0xFF90A4AE)
    StableSiteFeatureState.LEARNING -> Color(0xFF78909C)
    StableSiteFeatureState.BOOTSTRAP -> Color(0xFF607D8B)
    StableSiteFeatureState.UNAVAILABLE -> Color(0xFF555555)
}

private fun capabilityLabel(c: NeighbourEvidenceCapability): Int = when (c) {
    NeighbourEvidenceCapability.FULL_NEIGHBOUR_IDENTITY -> R.string.stable_site_capability_full
    NeighbourEvidenceCapability.RF_NEIGHBOUR_ONLY -> R.string.stable_site_capability_rf
    NeighbourEvidenceCapability.NO_NEIGHBOUR_DATA -> R.string.stable_site_capability_none
}

private fun capabilityNote(c: NeighbourEvidenceCapability): Int = when (c) {
    NeighbourEvidenceCapability.FULL_NEIGHBOUR_IDENTITY -> R.string.stable_site_capability_full_note
    NeighbourEvidenceCapability.RF_NEIGHBOUR_ONLY -> R.string.stable_site_capability_rf_note
    NeighbourEvidenceCapability.NO_NEIGHBOUR_DATA -> R.string.stable_site_capability_none_note
}

private fun siteFilterLabel(f: StableSiteQuery.SiteFilter): Int = when (f) {
    StableSiteQuery.SiteFilter.ALL -> R.string.filter_all
    StableSiteQuery.SiteFilter.ACTIVE -> R.string.stable_site_state_active
    StableSiteQuery.SiteFilter.SHADOW_READY -> R.string.stable_site_state_shadow_ready
    StableSiteQuery.SiteFilter.LEARNING -> R.string.stable_site_state_learning
}
