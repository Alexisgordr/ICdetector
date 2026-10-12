package com.alexisgordr.icdetector.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.alexisgordr.icdetector.R
import com.alexisgordr.icdetector.core.AppBuildId
import com.alexisgordr.icdetector.core.profile.DeviationLevel
import com.alexisgordr.icdetector.core.profile.LocalProfileHealth
import com.alexisgordr.icdetector.core.profile.LocalProfileOption
import com.alexisgordr.icdetector.core.profile.LocalProfileSummary
import com.alexisgordr.icdetector.core.profile.ProfileCompatibility
import com.alexisgordr.icdetector.core.profile.ProfileEvaluationRecord
import com.alexisgordr.icdetector.storage.CellDbHelper
import com.alexisgordr.icdetector.storage.LocalProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 3.0 — Ajustes → Perfil local (experimental). Desactivado por defecto. Activarlo solo MUESTRA la
 * última evaluación, marcada como experimental y separada de las decisiones del motor; no añade,
 * silencia ni rebaja alertas (ver [LocalProfileOption]). Se aplica al momento y queda registrado.
 */
@Composable
fun LocalProfileSection() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("miniic_prefs", Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(LocalProfileOption.PREF_KEY, LocalProfileOption.DEFAULT_ENABLED)) }
    var latest by remember { mutableStateOf<ProfileEvaluationRecord?>(null) }
    var counts by remember { mutableStateOf(LocalProfileSummary.Counts(0, 0)) }
    var lastResetMs by remember { mutableStateOf<Long?>(null) }
    var errorCount by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun db() = CellDbHelper.getInstance(context).writableDatabase
    fun appVersion() = AppBuildId.current(runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull())

    val deviceModel = remember { ProfileCompatibility.deviceModel(android.os.Build.MANUFACTURER, android.os.Build.MODEL) }

    // F08 — Mientras la tarjeta está visible y la opción activa, se refresca cada
    // [LocalProfileSummary.REFRESH_MS]; el recuento usa la madurez y compatibilidad actuales.
    LaunchedEffect(enabled, refresh) {
        while (enabled && isActive) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val db = db()
                    Triple(
                        LocalProfileStore.latestEvaluation(db),
                        LocalProfileSummary.counts(LocalProfileStore.allProfiles(db), deviceModel, System.currentTimeMillis()),
                        LocalProfileStore.lastEventMs(db, LocalProfileOption.RESET_EVENT)
                    )
                }.getOrNull()
            }?.let { (l, c, r) -> latest = l; counts = c; lastResetMs = r }
            errorCount = prefs.getInt(LocalProfileHealth.PREF_ERROR_COUNT, 0)
            delay(LocalProfileSummary.REFRESH_MS)
        }
    }

    Text(stringResource(R.string.local_profile_title), color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.local_profile_body), color = Color(0xFF888888), fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, lineHeight = 15.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = enabled,
            onCheckedChange = { requested ->
                enabled = requested
                prefs.edit { putBoolean(LocalProfileOption.PREF_KEY, requested) }
                scope.launch {
                    val logged = withContext(Dispatchers.IO) {
                        runCatching {
                            LocalProfileStore.recordModeEvent(db(), LocalProfileOption.mode(requested), appVersion(), System.currentTimeMillis())
                        }.isSuccess
                    }
                    // F10 — El modo ya cambió; si no se pudo registrar, se dice en vez de callarlo.
                    message = if (logged) null else resources.getString(R.string.local_profile_log_failed)
                }
            },
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF7E57C2))
        )
    }

    if (enabled) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.local_profile_summary, counts.usable, counts.total),
                color = Color(0xFFB39DDB), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            val r = latest
            val line = when {
                r == null -> stringResource(R.string.local_profile_none)
                r.level == DeviationLevel.ABSTAIN -> stringResource(R.string.local_profile_last_abstain, r.abstainReason?.name ?: "-")
                else -> stringResource(
                    R.string.local_profile_last_level,
                    stringResource(levelLabel(r.level)),
                    String.format(Locale.ROOT, "%.2f", r.score ?: 0.0),
                    "${r.metricsEvaluated}/${r.metricsTotal}"
                )
            }
            Text(line, color = Color(0xFFB39DDB), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
            if (r != null) {
                val stamp = remember(r.evaluatedAtMs) {
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM).format(java.util.Date(r.evaluatedAtMs))
                }
                Text(stringResource(R.string.local_profile_last_when, stamp, r.contextKey), color = Color(0xFF888888),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                if (LocalProfileSummary.fromPreviousProfile(r.evaluatedAtMs, lastResetMs)) {
                    Text(stringResource(R.string.local_profile_previous), color = Color(0xFF888888), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
            // F10 — Fallos del perfil (diagnóstico de la función experimental, no una alerta).
            val errors = errorCount
            if (errors > 0) {
                val lastErrorAt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(java.util.Date(prefs.getLong(LocalProfileHealth.PREF_LAST_ERROR_MS, 0L)))
                Text(stringResource(R.string.local_profile_errors, errors, lastErrorAt, prefs.getString(LocalProfileHealth.PREF_LAST_ERROR, null) ?: "-"),
                    color = Color(0xFFFFA000), fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp)
            }
            Text(stringResource(R.string.local_profile_not_engine), color = Color(0xFF666666), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 12.sp)
        }
    }

    Button(
        onClick = { confirmReset = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(stringResource(R.string.local_profile_reset), color = Color.White, fontFamily = FontFamily.Monospace)
    }
    message?.let { Text(it, color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.local_profile_reset_title)) },
            text = { Text(stringResource(R.string.local_profile_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                val db = db()
                                db.beginTransaction()
                                try {
                                    LocalProfileStore.reset(db)
                                    LocalProfileStore.recordEvent(db, LocalProfileOption.RESET_EVENT, appVersion(), System.currentTimeMillis())
                                    db.setTransactionSuccessful()
                                } finally {
                                    db.endTransaction()
                                }
                            }.isSuccess
                        }
                        message = resources.getString(if (ok) R.string.local_profile_reset_done else R.string.local_profile_reset_failed)
                        refresh++
                    }
                }) { Text(stringResource(R.string.local_profile_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

private fun levelLabel(level: DeviationLevel): Int = when (level) {
    DeviationLevel.NONE -> R.string.local_profile_level_none
    DeviationLevel.LOW -> R.string.local_profile_level_low
    DeviationLevel.MODERATE -> R.string.local_profile_level_moderate
    DeviationLevel.HIGH -> R.string.local_profile_level_high
    DeviationLevel.ABSTAIN -> R.string.local_profile_level_abstain
}
