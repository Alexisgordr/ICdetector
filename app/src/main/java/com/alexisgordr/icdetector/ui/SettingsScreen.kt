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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import com.alexisgordr.icdetector.service.MiniICService
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun SettingsPanel(service: MiniICService?, onSave: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("miniic_prefs", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("opencellid_key", "") ?: "") }
    var wigleName by remember { mutableStateOf(prefs.getString("wigle_api_name", "") ?: "") }
    var wigleToken by remember { mutableStateOf(prefs.getString("wigle_api_token", "") ?: "") }
    var proxyEnabled by remember { mutableStateOf(prefs.getBoolean("proxy_enabled", false)) }
    var latencyDetectionEnabled by remember {
        mutableStateOf(prefs.getBoolean("latency_detection_enabled", false))
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
            Text("CONFIGURACIÓN", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("APIS", color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("OpenCellID Token", color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                placeholder = { Text("pk.xxxxxxxxxxxxxxxx", color = Color(0xFF444444), fontSize = 12.sp) },
                visualTransformation = PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF4CAF50),
                    unfocusedBorderColor = Color(0xFF333333),
                    cursorColor = Color.White
                )
            )
            HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 4.dp))
            Text("WiGLE API Name", color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            OutlinedTextField(
                value = wigleName,
                onValueChange = { wigleName = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                placeholder = { Text("AIDxxxxxxxxxxxxxxxx", color = Color(0xFF444444), fontSize = 12.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF4CAF50),
                    unfocusedBorderColor = Color(0xFF333333),
                    cursorColor = Color.White
                )
            )
            Text("WiGLE API Token", color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            OutlinedTextField(
                value = wigleToken,
                onValueChange = { wigleToken = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                placeholder = { Text("xxxxxxxxxxxxxxxx", color = Color(0xFF444444), fontSize = 12.sp) },
                visualTransformation = PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF4CAF50),
                    unfocusedBorderColor = Color(0xFF333333),
                    cursorColor = Color.White
                )
            )
            HorizontalDivider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 4.dp))
            // Proxy Tor — se oculta si latencia experimental está activa
            if (!latencyDetectionEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Proxy Tor (Orbot)",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "Enruta API por Tor (SOCKS5 9050)",
                            color = Color(0xFF666666),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = proxyEnabled,
                        onCheckedChange = { proxyEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF4CAF50)
                        )
                    )
                }
            }
            // Leer estado WiFi de forma reactiva
            val isWifiActive by produceState(initialValue = false) {
                while (true) {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                        as android.net.ConnectivityManager
                    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                    value = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ?: false
                    kotlinx.coroutines.delay(2000L.milliseconds) // comprueba cada 2 segundos
                }
            }

            HorizontalDivider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Detección de Latencia (Experimental)",
                        color = if (isWifiActive) Color(0xFF444444) else Color.White,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        if (isWifiActive)
                            "No disponible con WiFi activo."
                        else
                            "3 peticiones HEAD cada 30s. Incompatible con Proxy Tor.",
                        color = Color(0xFF666666),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 12.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = latencyDetectionEnabled,
                    enabled = !isWifiActive,
                    onCheckedChange = {
                        latencyDetectionEnabled = it
                        if (it) proxyEnabled = false
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFFFA000),
                        disabledCheckedTrackColor = Color(0xFF444444),
                        disabledUncheckedTrackColor = Color(0xFF333333)
                    )
                )
            }

            Button(
                onClick = {
                    prefs.edit {
                        putString("opencellid_key", token)
                        putString("wigle_api_name", wigleName)
                        putString("wigle_api_token", wigleToken)
                        putBoolean("proxy_enabled", proxyEnabled)
                        putBoolean("latency_detection_enabled", latencyDetectionEnabled)
                    }
                    service?.openCellIdKey = token
                    service?.wigleApiName = wigleName
                    service?.wigleApiToken = wigleToken
                    service?.isProxyEnabled = proxyEnabled
                    service?.isLatencyDetectionEnabled = latencyDetectionEnabled
                    onSave()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text("GUARDAR AJUSTES", color = Color.White, fontFamily = FontFamily.Monospace)
            }

            HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 4.dp))

            Text("AVISO TÉCNICO", color = Color(0xFFCF6679), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(
                "La detección directa de cifrado nulo e IMSI Disclosure no está implementada en v2.0 para instalaciones Android estándar. Requiere APIs/permisos privilegiados que una app no-root no recibe; por eso esta regla se muestra como N/A cuando el sistema no expone el dato.",
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
                    context.startActivity(intent)
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
                    "APOYAR PROYECTO",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
