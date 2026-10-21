package com.alexisgordr.icdetector.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import android.content.Intent
import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import com.alexisgordr.icdetector.service.MiniICService
import com.alexisgordr.icdetector.R
import com.alexisgordr.icdetector.util.LocaleController
import com.alexisgordr.icdetector.core.BootStart
import com.alexisgordr.icdetector.core.LegacyNetworkAlertMode
import com.alexisgordr.icdetector.core.StableSiteNotices
import com.alexisgordr.icdetector.core.LocationMode

@Composable
fun SettingsPanel(
    service: MiniICService?,
    /** 3.0 — «Ver evaluaciones»: abre Historial con la pestaña Perfil local. */
    onOpenProfileEvaluations: () -> Unit = {},
    /** 3.0 — Flecha de volver: cierra Ajustes sin guardar, como el botón «atrás» del sistema. */
    onBack: () -> Unit = {},
    onSave: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("miniic_prefs", Context.MODE_PRIVATE) }
    var selectedLanguage by remember { mutableStateOf(LocaleController.selectedLanguage(context)) }
    var selectedLocationMode by remember {
        mutableStateOf(LocationMode.fromStored(prefs.getString(LocationMode.PREF_KEY, null)))
    }
    var selectedLegacyAlertMode by remember {
        mutableStateOf(LegacyNetworkAlertMode.fromStored(prefs.getString(LegacyNetworkAlertMode.PREF_KEY, null)))
    }
    // 3.0 — Avisos silenciosos de Stable-Site: activados por defecto; si se apagan, siguen apagados.
    var stableSiteNotices by remember {
        mutableStateOf(prefs.getBoolean(StableSiteNotices.PREF_ENABLED, StableSiteNotices.DEFAULT_ENABLED))
    }

    // 3.1.0 (#42) — Arranque tras reiniciar: desactivado por defecto (BootStart).
    var startOnBoot by remember {
        mutableStateOf(prefs.getBoolean(BootStart.PREF_ENABLED, BootStart.DEFAULT_ENABLED))
    }

    val scrollState = rememberScrollState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
        shape = RoundedCornerShape(4.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                }
                Text(stringResource(R.string.settings_title), color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Text(stringResource(R.string.settings_language), color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "es" to stringResource(R.string.language_spanish),
                    "en" to stringResource(R.string.language_english)
                ).forEach { (tag, label) ->
                    FilterChip(
                        selected = selectedLanguage == tag,
                        onClick = {
                            if (selectedLanguage != tag) {
                                selectedLanguage = tag
                                LocaleController.selectLanguage(context, tag)
                                (context as? Activity)?.recreate()
                            }
                        },
                        label = { Text(label, fontFamily = FontFamily.Monospace) }
                    )
                }
            }
            HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.location_mode_title), color = Color.White,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            LocationMode.entries.forEach { mode ->
                val label = when (mode) {
                    LocationMode.CONTINUOUS -> R.string.location_mode_continuous
                    LocationMode.INTELLIGENT -> R.string.location_mode_intelligent
                    LocationMode.ADAPTIVE -> R.string.location_mode_adaptive
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedLocationMode == mode,
                        onClick = { selectedLocationMode = mode })
                    TextButton(onClick = { selectedLocationMode = mode }) {
                        Text(stringResource(label), color = Color.White, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            val modeDescription = when (selectedLocationMode) {
                LocationMode.CONTINUOUS -> R.string.location_adaptive_off
                LocationMode.INTELLIGENT -> R.string.location_intelligent_description
                LocationMode.ADAPTIVE -> R.string.location_adaptive_on
            }
            Text(stringResource(modeDescription), color = Color(0xFF888888), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 15.sp)

            HorizontalDivider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 4.dp))

            // 3.0 — Avisos de red 2G/3G: uno por episodio; el sonido solo si se elige.
            Text(stringResource(R.string.legacy_alert_title), color = Color.White,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            LegacyNetworkAlertMode.entries.forEach { mode ->
                val label = when (mode) {
                    LegacyNetworkAlertMode.OFF -> R.string.legacy_alert_off
                    LegacyNetworkAlertMode.NOTIFY -> R.string.legacy_alert_notify
                    LegacyNetworkAlertMode.NOTIFY_SOUND -> R.string.legacy_alert_notify_sound
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedLegacyAlertMode == mode,
                        onClick = { selectedLegacyAlertMode = mode })
                    TextButton(onClick = { selectedLegacyAlertMode = mode }) {
                        Text(stringResource(label), color = Color.White, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Text(stringResource(R.string.legacy_alert_description), color = Color(0xFF888888), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 15.sp)

            HorizontalDivider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 4.dp))

            // 3.0 — Avisos silenciosos de Stable-Site (StableSiteNotices). Sin alarmas ni sonido.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.stable_site_notices_title), color = Color.White,
                        fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.stable_site_notices_description), color = Color(0xFF888888),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = stableSiteNotices,
                    onCheckedChange = { stableSiteNotices = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF4CAF50)
                    )
                )
            }

            // 3.1.0 (#42) — Iniciar el monitoreo al reiniciar el teléfono (BootReceiver).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.start_on_boot_title), color = Color.White,
                        fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Text(stringResource(R.string.start_on_boot_description), color = Color(0xFF888888),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = startOnBoot,
                    onCheckedChange = { startOnBoot = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF4CAF50)
                    )
                )
            }

            Button(
                onClick = {
                    val selectedMode = selectedLocationMode
                    val legacyMode = selectedLegacyAlertMode
                    prefs.edit {
                        putString(LocationMode.PREF_KEY, selectedMode.storedValue)
                        putString(LegacyNetworkAlertMode.PREF_KEY, legacyMode.storedValue)
                        putBoolean(StableSiteNotices.PREF_ENABLED, stableSiteNotices)
                        putBoolean(BootStart.PREF_ENABLED, startOnBoot)
                    }
                    service?.locationMode = selectedMode
                    service?.legacyNetworkAlertMode = legacyMode
                    onSave()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(stringResource(R.string.save_settings), color = Color.White, fontFamily = FontFamily.Monospace)
            }

            HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 4.dp))

            // 3.0 — Perfil local: opción experimental, solo visualización.
            LocalProfileSection(onViewEvaluations = onOpenProfileEvaluations)

            HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 4.dp))

            // 3.0 — Copia de seguridad: exportar y restaurar todos los datos.
            BackupSection()

            HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 4.dp))

            Text(stringResource(R.string.technical_notice), color = Color(0xFFCF6679), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.technical_notice_body),
                color = Color(0xFF888888),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 12.sp
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Color(0xFF1A1A1A))
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://buymeacoffee.com/alexisgomez".toUri())
                    // v2.10.8 — Sin navegador instalado, la app se cerraba al pulsar.
                    runCatching { context.startActivity(intent) }
                },
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFDD00)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .border(BorderStroke(0.5.dp, Color(0xFF333333)), RoundedCornerShape(16.dp))
            ) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFFCF6679)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.support_project),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
