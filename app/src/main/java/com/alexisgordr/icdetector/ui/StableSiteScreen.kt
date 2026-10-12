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
import com.alexisgordr.icdetector.core.RouteAssociation
import com.alexisgordr.icdetector.core.StableSiteCell
import com.alexisgordr.icdetector.core.StableSiteEpisode
import com.alexisgordr.icdetector.core.StableSiteFeatureState
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
private fun line(text: String, color: Color = Body, size: Int = 9) =
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = size.sp, lineHeight = (size + 3).sp)

/**
 * Historial → Topología → Ver Stable-Site: lista de sitios por su clave guardada, con su estado
 * ACTUAL. Al pulsar un sitio se abre su detalle; la flecha vuelve a la lista y después a Topología.
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

    val site = selected
    if (site != null) {
        StableSiteDetail(dbHelper, site, modifier) { selected = null }
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow(R.string.stable_site_view_back_topology, onBack) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.stable_site_view_title), color = Color.White, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
                line(stringResource(R.string.stable_site_view_shadow_note), Dim)
                line(stringResource(R.string.stable_site_view_active_note), Dim)
                line(stringResource(R.string.stable_site_view_key_note), Dim)
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

@Composable
private fun SiteCard(site: StableSiteSummary, onOpen: () -> Unit) {
    val m = site.maturity
    Card(onClick = onOpen, colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
        border = BorderStroke(1.dp, stateColor(m.state).copy(alpha = 0.45f)), shape = RoundedCornerShape(4.dp)) {
        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(shortKey(site.siteKey), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text(stringResource(stateLabel(m.state)), color = stateColor(m.state), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            }
            line(stringResource(R.string.stable_site_view_counts, site.servingDays, site.staticDays, site.servingObservations), Color(0xFFAAAAAA))
            line(stringResource(capabilityLabel(m.capability)) + " · " + (formatMs(site.lastSeenMs) ?: "-"), Dim)
        }
    }
}

/** Detalle de un sitio: estado ACTUAL, progreso, vecinas, celdas, huellas RF y episodios. */
@Composable
private fun StableSiteDetail(dbHelper: CellDbHelper, site: StableSiteSummary, modifier: Modifier, onBack: () -> Unit) {
    val cs = rememberCoroutineScope()
    var section by remember(site.siteKey) { mutableIntStateOf(0) }                  // 0 celdas, 1 RF, 2 episodios
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
    LaunchedEffect(site.siteKey, section) {
        when (section) {
            0 -> if (!cells.loaded) { cells.reset("cells|${site.siteKey}"); loadCells(true) }
            1 -> if (!rf.loaded) { rf.reset("rf|${site.siteKey}"); loadRf(true) }
        }
    }
    LaunchedEffect(site.siteKey, section, episodeFilter) {
        if (section == 2) { episodes.reset("episodes|${site.siteKey}|${episodeFilter.name}"); loadEpisodes(true) }
    }
    val m = site.maturity

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackRow(R.string.stable_site_view_back_sites, onBack) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(site.siteKey, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                line(stringResource(R.string.stable_site_view_current_state, stringResource(stateLabel(m.state)), m.reason), stateColor(m.state), 10)
                line(stringResource(R.string.stable_site_view_active_note), Dim)
                line(stringResource(capabilityLabel(m.capability)) + " — " + stringResource(capabilityNote(m.capability)), Body)
                line(stringResource(R.string.stable_site_view_seen, formatMs(site.firstSeenMs) ?: "-", formatMs(site.lastSeenMs) ?: "-"), Dim)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(R.string.stable_site_view_progress), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                Progress(R.string.stable_site_view_serving_days, StableSiteProgress.servingDays(site))
                Progress(R.string.stable_site_view_static_days, StableSiteProgress.staticDays(site))
                Progress(R.string.stable_site_view_full_neighbour_days, StableSiteProgress.fullNeighbourDays(site))
                Progress(R.string.stable_site_view_rf_neighbour_days, StableSiteProgress.rfNeighbourDays(site))
                Progress(R.string.stable_site_view_serving_observations, StableSiteProgress.servingObservations(site))
                line(stringResource(R.string.stable_site_view_neighbour_rule), Dim)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(R.string.stable_site_view_section_cells, R.string.stable_site_view_section_rf, R.string.stable_site_view_section_episodes)
                    .forEachIndexed { i, label -> FilterChip(selected = section == i, onClick = { section = i }, label = { Text(stringResource(label), fontSize = 10.sp) }) }
            }
        }
        when (section) {
            0 -> pagedItems(cells, R.string.stable_site_view_no_cells, key = { it.rowKey },
                onRetryFirst = { cells.reset("cells|${site.siteKey}"); loadCells(true) }, onMore = { loadCells(false) }) { CellRow(it) }
            1 -> {
                item { line(stringResource(R.string.stable_site_view_rf_note), Dim) }
                pagedItems(rf, R.string.stable_site_view_no_rf, key = { it.fingerprint },
                    onRetryFirst = { rf.reset("rf|${site.siteKey}"); loadRf(true) }, onMore = { loadRf(false) }) { RfRow(it) }
            }
            else -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        line(stringResource(R.string.stable_site_view_episodes_note), Dim)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = episodeFilter == StableSiteQuery.EpisodeFilter.ALL, onClick = { episodeFilter = StableSiteQuery.EpisodeFilter.ALL },
                                label = { Text(stringResource(R.string.stable_site_view_episodes_all), fontSize = 10.sp) })
                            FilterChip(selected = episodeFilter == StableSiteQuery.EpisodeFilter.SHADOW_RECORDED, onClick = { episodeFilter = StableSiteQuery.EpisodeFilter.SHADOW_RECORDED },
                                label = { Text(stringResource(R.string.stable_site_view_episodes_recorded), fontSize = 10.sp) })
                        }
                    }
                }
                pagedItems(episodes, R.string.stable_site_view_no_episodes, key = { it.episodeId },
                    onRetryFirst = { episodes.reset("episodes|${site.siteKey}|${episodeFilter.name}"); loadEpisodes(true) },
                    onMore = { loadEpisodes(false) }) { EpisodeCard(it) }
            }
        }
    }
}

@Composable
private fun Progress(label: Int, item: StableSiteProgress.Item) {
    Column {
        line(stringResource(label) + ": ${item.value}/${item.target}", Body)
        LinearProgressIndicator(progress = { item.fraction }, modifier = Modifier.fillMaxWidth().height(3.dp),
            color = Accent, trackColor = Color(0xFF252525))
    }
}

@Composable
private fun CellRow(c: StableSiteCell) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        line(c.identity + " · " + stringResource(if (c.role == "SERVING") R.string.stable_site_view_role_serving else R.string.stable_site_view_role_neighbour), Color.White)
        line(stringResource(R.string.stable_site_view_cell_counts, c.observations, c.days, formatMs(c.lastSeenMs) ?: "-"), Dim)
    }
}

@Composable
private fun RfRow(r: StableSiteRfFingerprint) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        line("${r.radio.name} · ARFCN ${r.arfcn} · PCI ${r.pci}" +
            (if (r.mcc != null && r.mnc != null) " · ${r.mcc}-${r.mnc}" else "") + (r.tac?.let { " · TAC $it" } ?: ""), Color.White)
        line(stringResource(R.string.stable_site_view_cell_counts, r.observations, r.days, formatMs(r.lastSeenMs) ?: "-"), Dim)
    }
}

/**
 * Un episodio tal como se guardó. Distingue los registrados en sombra (`shadow_recorded=1`) de los
 * solo observados; su evidencia es la ÚLTIMA EVALUACIÓN GUARDADA, y el estado del sitio que muestra
 * es el registrado en el episodio, no el actual. Los datos ausentes se dicen ausentes.
 */
@Composable
private fun EpisodeCard(e: StableSiteEpisode) {
    val border = if (e.shadowRecorded) Color(0xFFB39DDB) else Color(0xFF444444)
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)), border = BorderStroke(1.dp, border.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(4.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs(e.firstSeenMs) ?: "-", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                Text(stringResource(if (e.shadowRecorded) R.string.stable_site_view_episode_recorded else R.string.stable_site_view_episode_observed),
                    color = border, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            }
            line((e.previousIdentity ?: stringResource(R.string.profile_history_not_available)) + "  →  " + e.candidateIdentity, Body)
            line(e.siteKey?.let { stringResource(R.string.stable_site_view_episode_site, shortKey(it)) } ?: stringResource(R.string.stable_site_view_no_site), Dim)
            line(stringResource(R.string.stable_site_view_episode_period, formatMs(e.lastSeenMs) ?: "-",
                formatMs(e.closedMs) ?: stringResource(R.string.stable_site_view_episode_open)), Dim)
            HorizontalDivider(color = Color(0xFF222222))
            Text(stringResource(R.string.stable_site_view_last_evaluation), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 8.sp)
            if (e.evaluatedMs == null) {
                line(stringResource(R.string.stable_site_view_no_evaluation), Dim)
            } else {
                line(stringResource(R.string.stable_site_view_evaluated_at, formatMs(e.evaluatedMs) ?: "-"), Dim)
                val na = stringResource(R.string.profile_history_not_available)
                line(stringResource(R.string.stable_site_view_state_at_episode, e.featureStateAtEpisode ?: na), Body)
                line(stringResource(R.string.stable_site_view_episode_evidence, e.motionState ?: na, e.reason ?: na), Body)
                line(stringResource(R.string.stable_site_view_episode_flags, yesNo(e.knownAtSite), yesNo(e.globallyKnown), yesNo(e.seenAsNeighbour),
                    e.servingDays?.toString() ?: na, e.neighbourDays?.toString() ?: na, yesNo(e.corroborated)), Dim)
            }
        }
    }
}

@Composable
private fun yesNo(v: Boolean?): String = stringResource(when (v) { true -> R.string.stable_site_view_yes; false -> R.string.stable_site_view_no; null -> R.string.profile_history_not_available })

/**
 * Detalle de una ruta de Topología: «Episodios Stable-Site de esta ruta». Solo episodios con la misma
 * celda anterior y nueva (A→B) y que no empiezan después de la última vez de la ruta (margen de
 * consulta). Se relacionan con la ruta agregada, no con una transición concreta. Separados por sitio.
 */
@Composable
fun RouteStableSiteSection(dbHelper: CellDbHelper, route: CellTransitionSummary) {
    // LaunchedEffect cancela la consulta pendiente al cambiar de ruta o reintentar.
    val key = "${route.fromIdentity}>${route.toIdentity}"
    var groups by remember(key) { mutableStateOf<List<RouteAssociation.SiteGroup>?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    var attempt by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, attempt) {
        failed = false
        groups = null
        val result = try {
            Result.success(withContext(Dispatchers.IO) {
                StableSiteReadStore.routeEpisodeCandidates(dbHelper.readableDatabase, route.fromIdentity, route.toIdentity)
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        result.onSuccess { groups = RouteAssociation.associate(route.fromIdentity, route.toIdentity, route.lastSeenMs, it) }
            .onFailure { failed = true }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.stable_site_route_title), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
        line(stringResource(R.string.stable_site_route_note), Dim)
        val g = groups
        when {
            failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                line(stringResource(R.string.stable_site_view_error), Color(0xFFFFA000))
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.profile_history_retry), fontFamily = FontFamily.Monospace, fontSize = 9.sp) }
            }
            g == null -> line(stringResource(R.string.profile_history_loading), Dim)
            g.isEmpty() -> line(stringResource(R.string.stable_site_route_none), Body)
            else -> g.forEach { group ->
                Text(group.siteKey?.let { stringResource(R.string.stable_site_view_episode_site, shortKey(it)) } ?: stringResource(R.string.stable_site_view_no_site),
                    color = Accent, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                group.episodes.forEach { EpisodeCard(it) }
            }
        }
    }
}

/** Clave guardada abreviada para la lista; el detalle muestra la clave completa. */
private fun shortKey(key: String) = if (key.length > 18) key.take(10) + "…" + key.takeLast(6) else key

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
