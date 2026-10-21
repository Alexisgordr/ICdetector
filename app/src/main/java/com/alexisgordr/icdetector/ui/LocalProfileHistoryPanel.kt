package com.alexisgordr.icdetector.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexisgordr.icdetector.R
import com.alexisgordr.icdetector.backup.FirstRunPolicy
import com.alexisgordr.icdetector.core.profile.AbstainReason
import com.alexisgordr.icdetector.core.profile.DeviationLevel
import com.alexisgordr.icdetector.core.profile.EvaluationEntry
import com.alexisgordr.icdetector.core.profile.EvidenceParser
import com.alexisgordr.icdetector.core.profile.H13History
import com.alexisgordr.icdetector.core.profile.LocalProfileOption
import com.alexisgordr.icdetector.core.profile.LocalProfileSummary
import com.alexisgordr.icdetector.core.profile.ProfileEvaluationQuery
import com.alexisgordr.icdetector.core.profile.ProfileEvaluationQuery.LevelFilter
import com.alexisgordr.icdetector.core.profile.ProfileMetric
import com.alexisgordr.icdetector.core.profile.ProfileMode
import com.alexisgordr.icdetector.storage.CellDbHelper
import com.alexisgordr.icdetector.storage.LocalProfileStore
import com.alexisgordr.icdetector.storage.ObservationTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * 3.0 — Historial → Perfil local: evaluaciones del perfil local ya guardadas, de la más reciente a
 * la más antigua, por páginas. Solo lectura de `profile_evaluations` y de la fila de historial
 * enlazada: no recalcula nada ni usa el perfil actual como referencia de una evaluación pasada.
 * Incluye observaciones normales (sin desviación) y abstenciones, no solo filas con alarma.
 *
 * No crea alertas, notificaciones ni sonidos, y no toca scores, umbrales, aprendizaje ni reglas.
 */
@Composable
fun LocalProfileHistoryPanel(
    dbHelper: CellDbHelper,
    modifier: Modifier = Modifier,
    /** Abre la celda en la pestaña Antenas (búsqueda por identidad). */
    onOpenCell: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(LevelFilter.ALL) }
    // Generación de la lista: aumenta con cada cambio de filtro o reintento de la primera página.
    // Junto con el filtro forma la ficha que debe llevar una respuesta para aplicarse.
    var generation by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<EvaluationEntry>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    /** Fallo de lectura: de la primera página (lista vacía) o de «Cargar más» (lista conservada). */
    var error by remember { mutableStateOf<LoadError?>(null) }
    var loadMoreJob by remember { mutableStateOf<Job?>(null) }
    var counts by remember { mutableStateOf<Map<DeviationLevel, Int>>(emptyMap()) }
    var lastResetMs by remember { mutableStateOf<Long?>(null) }
    val restored = remember {
        context.getSharedPreferences("miniic_prefs", Context.MODE_PRIVATE).getBoolean(FirstRunPolicy.PREF_HISTORY_RESTORED, false)
    }

    // La cancelación se relanza: solo los fallos reales de lectura se convierten en error.
    suspend fun load(f: LevelFilter, after: ProfileEvaluationQuery.PageCursor?): Result<List<EvaluationEntry>> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(LocalProfileStore.evaluationsPage(dbHelper.readableDatabase, f, after))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun current() = ProfileEvaluationQuery.RequestToken(filter, generation)

    LaunchedEffect(filter, generation) {
        // Cambiar de filtro o reintentar cancela un «Cargar más» pendiente y empieza una lista nueva:
        // nada pedido con el filtro anterior puede añadirse a esta.
        loadMoreJob?.cancel()
        loadMoreJob = null
        val token = current()
        entries = emptyList()
        hasMore = false
        error = null
        loading = true
        withContext(Dispatchers.IO) {
            try {
                val db = dbHelper.readableDatabase
                Pair(LocalProfileStore.evaluationLevelCounts(db), LocalProfileStore.lastEventMs(db, LocalProfileOption.RESET_EVENT))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null                                            // se conservan los recuentos anteriores
            }
        }?.let { (c, r) -> counts = c; lastResetMs = r }
        val result = load(token.filter, null)
        if (!ProfileEvaluationQuery.accepts(current(), token)) return@LaunchedEffect
        result.onSuccess { page ->
            hasMore = ProfileEvaluationQuery.hasMore(page.size)
            entries = page.take(ProfileEvaluationQuery.PAGE_SIZE)
        }.onFailure { error = LoadError.FIRST_PAGE }
        loading = false
    }

    fun loadMore() {
        val token = current()
        val cursor = ProfileEvaluationQuery.cursorAfter(entries)
        loadMoreJob?.cancel()
        loadMoreJob = scope.launch {
            loading = true
            error = null
            val result = load(token.filter, cursor)
            // Si entretanto cambió el filtro o se reinició la lista, esta respuesta ya no vale.
            if (!ProfileEvaluationQuery.accepts(current(), token)) return@launch
            result.onSuccess { page ->
                hasMore = ProfileEvaluationQuery.hasMore(page.size)
                entries = ProfileEvaluationQuery.append(entries, page.take(ProfileEvaluationQuery.PAGE_SIZE))
            }.onFailure { error = LoadError.MORE }                    // la lista y «hay más» se conservan
            loading = false
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.profile_history_note), color = Color(0xFF888888), fontFamily = FontFamily.Monospace,
            fontSize = 9.sp, lineHeight = 12.sp)
        if (restored) {
            Text(stringResource(R.string.profile_history_restored_note), color = Color(0xFF888888), fontFamily = FontFamily.Monospace,
                fontSize = 9.sp, lineHeight = 12.sp)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LevelFilter.entries.forEach { f ->
                val n = if (f == LevelFilter.ALL) counts.values.sum() else f.levels.sumOf { counts[it] ?: 0 }
                FilterChip(selected = filter == f, onClick = { filter = f },
                    label = { Text("${stringResource(filterLabel(f))} ($n)", fontSize = 10.sp) })
            }
        }
        when {
            error == LoadError.FIRST_PAGE -> Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.profile_history_error), color = Color(0xFFFFA000), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                OutlinedButton(onClick = { generation++ }, shape = RoundedCornerShape(4.dp)) {
                    Text(stringResource(R.string.profile_history_retry), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
            loading && entries.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.profile_history_loading), color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            entries.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(if (counts.isEmpty()) R.string.profile_history_empty else R.string.profile_history_empty_filter),
                    color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.evaluationId }) { entry ->
                    EvaluationCard(entry, LocalProfileSummary.fromPreviousProfile(entry.evaluatedAtMs, lastResetMs), onOpenCell)
                }
                if (error == LoadError.MORE) item {
                    Text(stringResource(R.string.profile_history_error_more), color = Color(0xFFFFA000), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
                if (hasMore) item {
                    OutlinedButton(
                        onClick = { loadMore() },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(stringResource(if (error == LoadError.MORE) R.string.profile_history_retry else R.string.profile_history_load_more),
                            fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

private enum class LoadError { FIRST_PAGE, MORE }

@Composable
private fun EvaluationCard(entry: EvaluationEntry, beforeReset: Boolean, onOpenCell: (String) -> Unit) {
    var expanded by remember(entry.evaluationId) { mutableStateOf(false) }
    val obs = entry.observation
    val color = levelColor(entry.level)
    val dim = Color(0xFF777777)
    Card(
        onClick = { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val cell = obs?.cid?.let { "CID $it · ${obs.radio.name}" } ?: entry.contextKey
                Text(cell, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                val levelText = entry.level?.let { stringResource(levelLabel(it)) } ?: "?"
                Text(levelText + (entry.score?.let { " · " + String.format(Locale.ROOT, "%.2f", it) } ?: ""),
                    color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
            Text(observedText(entry), color = dim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            Text(
                listOfNotNull(
                    stringResource(modeLabel(entry.mode)),
                    stringResource(R.string.profile_history_metrics, entry.metricsEvaluated, entry.metricsTotal),
                    entry.abstainReason?.let { stringResource(R.string.profile_history_reason, stringResource(abstainReasonLabel(it))) }
                ).joinToString(" · "),
                color = Color(0xFFAAAAAA), fontFamily = FontFamily.Monospace, fontSize = 9.sp
            )
            if (expanded) EvaluationDetail(entry, beforeReset, onOpenCell)
        }
    }
}

@Composable
private fun EvaluationDetail(entry: EvaluationEntry, beforeReset: Boolean, onOpenCell: (String) -> Unit) {
    val obs = entry.observation
    val body = Color(0xFFCCCCCC)
    val dim = Color(0xFF777777)
    @Composable fun line(text: String, c: Color = body) = Text(text, color = c, fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 12.sp)
    @Composable fun title(res: Int) = Text(stringResource(res), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)

    HorizontalDivider(color = Color(0xFF222222))
    title(R.string.profile_history_section_evaluation)
    line(stringResource(R.string.profile_history_evaluated_at, formatMs(entry.evaluatedAtMs)))
    line(stringResource(R.string.profile_history_algorithm, entry.algorithmVersion, entry.profileRevision ?: "-"))
    entry.abstainReason?.let { line(stringResource(R.string.profile_history_reason_code, it.name), dim) }
    line(stringResource(when {
        entry.holdout -> R.string.profile_history_holdout
        entry.learned -> R.string.profile_history_learned
        else -> R.string.profile_history_not_learned
    }))
    // 3.0 (L2) — Una evaluación para todas las filas de la misma observación.
    if (entry.observationRows > 1) line(pluralStringResource(R.plurals.profile_history_observation_rows, entry.observationRows, entry.observationRows), dim)
    if (beforeReset) line(stringResource(R.string.local_profile_previous), dim)

    // Valores y referencia: SOLO lo guardado en la evaluación. Sin evidencia no hay referencia.
    title(R.string.profile_history_section_values)
    val evidence = EvidenceParser.parse(entry.evidence).associateBy { it.metric }
    ProfileMetric.entries.forEach { metric ->
        val e = evidence[metric]
        val name = stringResource(if (metric == ProfileMetric.SIGNAL_DBM) R.string.profile_metric_dbm else R.string.profile_metric_rsrq)
        if (e != null) {
            line(stringResource(R.string.profile_history_metric_with_reference, name, e.observed,
                fmt(e.mean), fmt(e.spread), String.format(Locale.ROOT, "%.2f", e.deviation)))
        } else {
            val observed = if (metric == ProfileMetric.SIGNAL_DBM) obs?.dbm else obs?.rsrq
            val shown = observed?.takeIf { metric.isValid(it) }?.toString() ?: stringResource(R.string.profile_history_not_available)
            line(stringResource(R.string.profile_history_metric_without_reference, name, shown), dim)
        }
    }

    title(R.string.profile_history_section_observation)
    if (obs == null) {
        line(stringResource(R.string.profile_history_observation_missing), dim)
    } else {
        line(stringResource(R.string.profile_history_identity, obs.mcc ?: "?", obs.mnc ?: "?", obs.tac ?: "?", obs.cid ?: "?", obs.radio.name))
        line(listOfNotNull(
            obs.dbm?.takeIf { ProfileMetric.SIGNAL_DBM.isValid(it) }?.let { "$it dBm" },
            obs.rsrq?.let { "RSRQ $it" }, obs.sinr?.let { "SINR $it" }, obs.score?.let { "score $it" }
        ).joinToString(" · ").ifEmpty { stringResource(R.string.profile_history_not_available) })
        val englishUi = LocalConfiguration.current.locales[0].language != "es"
        val reason = obs.failedHeuristics.orEmpty().ifEmpty { "-" }
        line(stringResource(R.string.profile_history_saved_reason, if (englishUi) localizeTerminalLine(reason) else reason))
        line(listOfNotNull(obs.locationMode, obs.appVersion).joinToString(" · ").ifEmpty { "-" }, dim)
        val h13 = H13History.of(obs)
        line(stringResource(R.string.profile_history_h13, stringResource(h13Label(h13))))
        line(stringResource(R.string.profile_history_h13_note), dim)
        if (obs.cid != null) {
            OutlinedButton(
                onClick = { onOpenCell(listOfNotNull(obs.cid, obs.mcc, obs.mnc).joinToString(" ")) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB39DDB)),
                shape = RoundedCornerShape(4.dp)
            ) { Text(stringResource(R.string.profile_history_open_cell), fontFamily = FontFamily.Monospace, fontSize = 9.sp) }
        }
    }
}

@Composable
private fun observedText(entry: EvaluationEntry): String {
    val obs = entry.observation
    return when {
        obs?.observedAtMs != null -> formatMs(obs.observedAtMs) + " · " + ObservationTime.utcIso(obs.observedAtMs)
        obs?.timestamp != null -> obs.timestamp + " · " + stringResource(R.string.profile_history_approximate_time)
        else -> formatMs(entry.evaluatedAtMs) + " · " + stringResource(R.string.profile_history_evaluation_time)
    }
}

private fun formatMs(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(ms))

private fun fmt(x: Double) = String.format(Locale.ROOT, "%.1f", x)

private fun levelColor(level: DeviationLevel?): Color = when (level) {
    DeviationLevel.HIGH -> Color(0xFFB39DDB)
    DeviationLevel.MODERATE -> Color(0xFF9FA8DA)
    DeviationLevel.LOW -> Color(0xFF90A4AE)
    DeviationLevel.NONE -> Color(0xFF607D8B)
    DeviationLevel.ABSTAIN -> Color(0xFF555555)
    null -> Color(0xFF555555)
}

private fun levelLabel(level: DeviationLevel): Int = when (level) {
    DeviationLevel.NONE -> R.string.local_profile_level_none
    DeviationLevel.LOW -> R.string.local_profile_level_low
    DeviationLevel.MODERATE -> R.string.local_profile_level_moderate
    DeviationLevel.HIGH -> R.string.local_profile_level_high
    DeviationLevel.ABSTAIN -> R.string.local_profile_level_abstain
}

private fun filterLabel(filter: LevelFilter): Int = when (filter) {
    LevelFilter.ALL -> R.string.filter_all
    LevelFilter.DEVIATIONS -> R.string.profile_filter_deviations
    LevelFilter.HIGH -> R.string.local_profile_level_high
    LevelFilter.MODERATE -> R.string.local_profile_level_moderate
    LevelFilter.LOW -> R.string.local_profile_level_low
    LevelFilter.NONE -> R.string.local_profile_level_none
    LevelFilter.ABSTAIN -> R.string.local_profile_level_abstain
}

private fun modeLabel(mode: ProfileMode?): Int = when (mode) {
    ProfileMode.SHADOW -> R.string.profile_mode_shadow
    ProfileMode.EXPERIMENTAL_DISPLAY -> R.string.profile_mode_experimental
    null -> R.string.profile_history_not_available
}

private fun h13Label(h: H13History): Int = when (h) {
    H13History.FAILED -> R.string.profile_h13_failed
    H13History.NOT_EVALUATED -> R.string.profile_h13_not_evaluated
    H13History.NO_FAILURE_RECORDED -> R.string.profile_h13_no_failure
    H13History.UNKNOWN -> R.string.profile_h13_unknown
}

/** Motivo de abstención: código estable (el mismo que el CSV), sin traducir. */
/** Motivo de abstención en palabras; el código guardado se muestra en el detalle. */
internal fun abstainReasonLabel(reason: AbstainReason): Int = when (reason) {
    AbstainReason.INCOMPLETE_CONTEXT -> R.string.profile_abstain_incomplete_context
    AbstainReason.NO_PROFILE -> R.string.profile_abstain_no_profile
    AbstainReason.PROFILE_IMMATURE -> R.string.profile_abstain_immature
    AbstainReason.PROFILE_STALE -> R.string.profile_abstain_stale
    AbstainReason.INCOMPATIBLE_DEVICE -> R.string.profile_abstain_incompatible_device
    AbstainReason.INCOMPATIBLE_RADIO -> R.string.profile_abstain_incompatible_radio
    AbstainReason.INCOMPATIBLE_ALGORITHM -> R.string.profile_abstain_incompatible_algorithm
    AbstainReason.NO_COMPATIBLE_METRIC -> R.string.profile_abstain_no_compatible_metric
}
