package com.alexisgordr.icdetector.ui

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexisgordr.icdetector.R
import com.alexisgordr.icdetector.models.CellConnectionState
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.SecondaryCarrier
import com.alexisgordr.icdetector.models.ServiceRegistrationState
import com.alexisgordr.icdetector.models.ServiceStateSnapshot
import com.alexisgordr.icdetector.models.identityKey
import com.alexisgordr.icdetector.service.MiniICService
import com.alexisgordr.icdetector.storage.CellDbHelper
import com.alexisgordr.icdetector.utils.ExportUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/*
 * v2.10.4 — Pestaña RADIO y tarjeta de contexto de radio.
 *
 * Muestra lo que Android expone sin root y que la app ahora recoge: qué entrada es la servidora
 * según el módem (PRIMARY/SECONDARY_SERVING), las portadoras secundarias, ancho de banda, bandas,
 * PLMN adicionales, grupo cerrado (CSG) y el estado de servicio con su historial de cambios.
 *
 * Todo es SOLO RECOLECCIÓN: nada de esta pantalla cambia el score ni dispara alertas.
 */

private val LabelColor = Color(0xFF666666)
private val ValueColor = Color(0xFFE0E0E0)
private val CardColor = Color(0xFF111111)
private val BorderColor = Color(0xFF222222)
private val GoodColor = Color(0xFF4CAF50)
private val WatchColor = Color(0xFFFFA000)
private val AlertColor = Color(0xFFCF6679)

@Composable
internal fun connectionStateLabel(state: CellConnectionState): String = stringResource(
    when (state) {
        CellConnectionState.PRIMARY_SERVING -> R.string.conn_primary
        CellConnectionState.SECONDARY_SERVING -> R.string.conn_secondary
        CellConnectionState.NONE -> R.string.conn_none
        CellConnectionState.UNKNOWN -> R.string.conn_unknown
    }
)

@Composable
internal fun serviceStateLabel(state: ServiceRegistrationState): String = stringResource(
    when (state) {
        ServiceRegistrationState.IN_SERVICE -> R.string.service_in
        ServiceRegistrationState.OUT_OF_SERVICE -> R.string.service_out
        ServiceRegistrationState.EMERGENCY_ONLY -> R.string.service_emergency
        ServiceRegistrationState.POWER_OFF -> R.string.service_power_off
        ServiceRegistrationState.UNKNOWN -> R.string.service_unknown
    }
)

internal fun serviceStateColor(state: ServiceRegistrationState): Color = when (state) {
    // Una rama por valor: tools/check_when_exhaustive.py (en CI) no interpreta ramas con coma.
    ServiceRegistrationState.IN_SERVICE -> GoodColor
    ServiceRegistrationState.EMERGENCY_ONLY -> AlertColor
    ServiceRegistrationState.OUT_OF_SERVICE -> AlertColor
    ServiceRegistrationState.POWER_OFF -> WatchColor
    ServiceRegistrationState.UNKNOWN -> WatchColor
}

private fun connectionStateColor(state: CellConnectionState): Color = when (state) {
    CellConnectionState.PRIMARY_SERVING -> GoodColor
    CellConnectionState.SECONDARY_SERVING -> Color(0xFF64B5F6)
    CellConnectionState.NONE -> Color(0xFF888888)
    CellConnectionState.UNKNOWN -> Color(0xFF555555)
}

private fun megahertz(khz: Int): String =
    if (khz % 1000 == 0) "${khz / 1000} MHz" else String.format(java.util.Locale.ROOT, "%.1f MHz", khz / 1000.0)

/** Tarjeta compacta para la pantalla principal. */
@Composable
fun RadioContextCard(active: CellData, serviceState: ServiceStateSnapshot?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        border = BorderStroke(0.5.dp, BorderColor),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.radio_card_title),
                color = LabelColor, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold
            )
            val carrier = buildString {
                append(active.radioTech.name)
                append(" ")
                append(active.arfcn?.toString() ?: "?")
                append("/")
                append(active.pci?.toString() ?: "?")
                active.bandwidthKhz?.let { append(" · ").append(megahertz(it)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.radio_card_serving, carrier, connectionStateLabel(active.connectionState)),
                    color = ValueColor, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
            }
            Text(
                stringResource(R.string.radio_card_secondary, active.secondaryCarriers.size),
                color = if (active.secondaryCarriers.isEmpty()) Color(0xFF888888) else Color(0xFF64B5F6),
                fontFamily = FontFamily.Monospace, fontSize = 10.sp
            )
            val state = serviceState?.state
            Text(
                stringResource(R.string.radio_card_service, state?.let { serviceStateLabel(it) } ?: stringResource(R.string.radio_not_available)),
                color = state?.let(::serviceStateColor) ?: Color(0xFF888888),
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold
            )
            if (active.csg?.indicator == true) {
                Text(
                    stringResource(R.string.radio_csg_yes, active.csg?.identity?.toString() ?: "?"),
                    color = WatchColor, fontFamily = FontFamily.Monospace, fontSize = 9.sp
                )
            }
        }
    }
}

/** Pestaña RADIO dentro del historial. */
@Composable
@SuppressLint("LocalContextGetResourceValueCall")
fun RadioPanel(dbHelper: CellDbHelper, service: MiniICService?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cellFlow = remember(service) { service?.cellFlow ?: MutableStateFlow<List<CellData>>(emptyList()) }
    val serviceFlow = remember(service) { service?.serviceState ?: MutableStateFlow<ServiceStateSnapshot?>(null) }
    val revisionFlow = remember(service) { service?.serviceEventsRevision ?: MutableStateFlow(0L) }
    val cells by cellFlow.collectAsStateWithLifecycle()
    val serviceState by serviceFlow.collectAsStateWithLifecycle()
    val serviceEventsRevision by revisionFlow.collectAsStateWithLifecycle()

    var events by remember { mutableStateOf<List<ServiceStateSnapshot>>(emptyList()) }
    var eventCount by remember { mutableIntStateOf(0) }
    // Solo una inserción confirmada incrementa la revisión; al observarla, la fila ya está en BD.
    LaunchedEffect(serviceEventsRevision) {
        val (loaded, count) = withContext(Dispatchers.IO) {
            dbHelper.getServiceStateEvents(limit = 50) to dbHelper.countServiceStateEvents()
        }
        events = loaded
        eventCount = count
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    ExportUtils.exportServiceStateCsv(context, it, dbHelper.getServiceStateEvents())
                }
                result.onSuccess { n ->
                    Toast.makeText(context, context.getString(R.string.radio_export_done, n), Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    Toast.makeText(context, context.getString(R.string.radio_export_failed, error.message ?: "?"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val serving = cells.firstOrNull { it.isRegistered }

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                stringResource(R.string.radio_collection_note),
                color = Color(0xFF888888), fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 12.sp
            )
        }

        if (service == null) {
            item { SectionText(stringResource(R.string.radio_waiting)) }
        }

        // ── Celda servidora ───────────────────────────────────────────────────────────────
        item {
            RadioSection(stringResource(R.string.radio_serving_cell)) {
                if (serving == null) {
                    SectionText(stringResource(R.string.radio_not_available))
                } else {
                    RadioLine(stringResource(R.string.radio_identity), serving.identityKey)
                    RadioLine(
                        stringResource(R.string.radio_carrier),
                        "${serving.radioTech.name} ${serving.arfcn ?: "?"}/${serving.pci ?: "?"}"
                    )
                    RadioLine(
                        stringResource(R.string.radio_connection_state),
                        connectionStateLabel(serving.connectionState),
                        connectionStateColor(serving.connectionState)
                    )
                    RadioLine(
                        stringResource(R.string.radio_bandwidth),
                        serving.bandwidthKhz?.let(::megahertz) ?: stringResource(R.string.radio_not_available)
                    )
                    RadioLine(
                        stringResource(R.string.radio_bands),
                        serving.bands.takeIf { it.isNotEmpty() }?.joinToString(", ") { "B$it" }
                            ?: stringResource(R.string.radio_not_available)
                    )
                    RadioLine(
                        stringResource(R.string.radio_additional_plmns),
                        serving.additionalPlmns.takeIf { it.isNotEmpty() }?.joinToString(", ")
                            ?: stringResource(R.string.radio_not_available)
                    )
                    val csg = serving.csg
                    RadioLine(
                        stringResource(R.string.radio_csg),
                        when {
                            csg == null -> stringResource(R.string.radio_not_available)
                            csg.indicator -> stringResource(R.string.radio_csg_yes, csg.identity?.toString() ?: "?")
                            else -> stringResource(R.string.radio_no)
                        },
                        if (csg?.indicator == true) WatchColor else ValueColor
                    )
                    csg?.homeNodebName?.let { RadioLine(stringResource(R.string.radio_csg_name), it) }
                }
            }
        }

        // ── Portadoras secundarias ────────────────────────────────────────────────────────
        item {
            RadioSection(stringResource(R.string.radio_secondary_carriers)) {
                val carriers = serving?.secondaryCarriers.orEmpty()
                if (carriers.isEmpty()) {
                    SectionText(stringResource(R.string.radio_no_secondary))
                } else {
                    carriers.forEach { SecondaryCarrierLine(it) }
                }
            }
        }

        // ── Estado de servicio ────────────────────────────────────────────────────────────
        item {
            RadioSection(stringResource(R.string.radio_service_state)) {
                val current = serviceState
                if (current == null) {
                    SectionText(stringResource(R.string.radio_not_available))
                } else {
                    ServiceStateLines(current)
                }
            }
        }

        // ── Celdas visibles ───────────────────────────────────────────────────────────────
        item { SectionHeader(stringResource(R.string.radio_visible_cells, cells.size)) }
        items(cells) { cell -> VisibleCellLine(cell) }

        // ── Historial de cambios de servicio ──────────────────────────────────────────────
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(stringResource(R.string.radio_service_events, eventCount))
                TextButton(
                    onClick = { exportLauncher.launch("icdetector_service_state_${System.currentTimeMillis()}.csv") },
                    enabled = eventCount > 0
                ) {
                    Text(stringResource(R.string.radio_export_events), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
        }
        if (events.isEmpty()) {
            item { SectionText(stringResource(R.string.radio_no_events)) }
        } else {
            items(events) { event -> ServiceEventLine(event) }
        }
    }
}

@Composable
private fun RadioSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        border = BorderStroke(0.5.dp, BorderColor),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SectionHeader(title)
            content()
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, color = LabelColor, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun SectionText(text: String) {
    Text(text, color = Color(0xFF888888), fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 12.sp)
}

@Composable
private fun RadioLine(label: String, value: String, valueColor: Color = ValueColor) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = LabelColor, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            value, color = valueColor, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SecondaryCarrierLine(carrier: SecondaryCarrier) {
    val bandwidth = carrier.bandwidthKhz?.let { " · ${megahertz(it)}" } ?: ""
    RadioLine(
        "${carrier.radio.name} ${carrier.arfcn ?: "?"}/${carrier.pci ?: "?"}$bandwidth",
        connectionStateLabel(carrier.connectionState),
        connectionStateColor(carrier.connectionState)
    )
}

@Composable
private fun VisibleCellLine(cell: CellData) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${cell.radioTech.name} ${cell.arfcn ?: "?"}/${cell.pci ?: "?"} · ${cell.dbm} dBm",
                    color = ValueColor, fontFamily = FontFamily.Monospace, fontSize = 9.sp
                )
                val idText = if (cell.cellId == "N/A") stringResource(R.string.radio_no_identity) else "CID ${cell.cellId}"
                val registeredText = if (cell.isRegistered) " · " + stringResource(R.string.radio_registered) else ""
                Text(
                    idText + registeredText,
                    color = LabelColor, fontFamily = FontFamily.Monospace, fontSize = 8.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                connectionStateLabel(cell.connectionState),
                color = connectionStateColor(cell.connectionState),
                fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ServiceStateLines(current: ServiceStateSnapshot) {
    val yes = stringResource(R.string.radio_yes)
    val no = stringResource(R.string.radio_no)
    val unavailable = stringResource(R.string.radio_not_available)
    RadioLine(stringResource(R.string.radio_service_state_label), serviceStateLabel(current.state), serviceStateColor(current.state))
    RadioLine(
        stringResource(R.string.radio_network_operator),
        current.operatorNumeric?.let { numeric ->
            current.operatorAlphaLong?.let { "$numeric ($it)" } ?: numeric
        } ?: unavailable
    )
    RadioLine(stringResource(R.string.radio_sim_operator), current.simOperator ?: unavailable)
    RadioLine(stringResource(R.string.radio_data_domain), domainText(current.dataNetworkType, current.dataRegistered, unavailable))
    RadioLine(stringResource(R.string.radio_voice_domain), domainText(current.voiceNetworkType, current.voiceRegistered, unavailable))
    RadioLine(stringResource(R.string.radio_roaming), if (current.roaming) yes else no)
    RadioLine(stringResource(R.string.radio_manual_selection), if (current.manualSelection) yes else no)
    if (current.cellBandwidthsKhz.isNotEmpty()) {
        RadioLine(stringResource(R.string.radio_active_bandwidths), current.cellBandwidthsKhz.joinToString(" + ") { megahertz(it) })
    }
    if (current.operatorDiffersFromSim) {
        Text(
            stringResource(R.string.radio_operator_mismatch),
            color = WatchColor, fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 12.sp
        )
    }
}

@Composable
private fun domainText(network: String?, registered: Boolean?, unavailable: String): String {
    val state = when (registered) {
        true -> stringResource(R.string.radio_registered)
        false -> stringResource(R.string.radio_not_registered)
        null -> null
    }
    return listOfNotNull(network, state).joinToString(" · ").ifEmpty { unavailable }
}

@Composable
private fun ServiceEventLine(event: ServiceStateSnapshot) {
    val time = remember(event.timestampMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(event.timestampMs))
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(time, color = LabelColor, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        Text(
            listOfNotNull(serviceStateLabel(event.state), event.dataNetworkType, event.operatorNumeric).joinToString(" · "),
            color = serviceStateColor(event.state), fontFamily = FontFamily.Monospace, fontSize = 9.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}
