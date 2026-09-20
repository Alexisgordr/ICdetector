package com.alexisgordr.icdetector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.alexisgordr.icdetector.R
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import com.alexisgordr.icdetector.core.BandPlan
import java.util.Locale

@Composable
fun SignalVisualizer(active: CellData, neighbors: List<CellData>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.signal_comparison), color = Color(0xFF444444), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        
        SignalBarSegmented(
            label = stringResource(R.string.active_cell_format, active.networkType),
            dbm = active.dbm, 
            isMain = true,
            isSuspicious = active.isSuspicious
        )
        
        neighbors.asSequence().sortedByDescending { it.dbm }.take(3).forEach { neighbor ->
            SignalBarSegmented(
                label = stringResource(R.string.neighbor_cell_format, neighbor.cellId),
                dbm = neighbor.dbm, 
                isMain = false,
                isSuspicious = neighbor.isSuspicious
            )
        }
    }
}


@Composable
fun GeoGraph(active: CellData, geoHistory: List<Float>) {
    // v2.1 — La UI usa EXACTAMENTE la misma fuente de verdad que H6.
    //
    // Aquí quedaba el último resto de un error ya eliminado del motor: `if (is5g) 150 else 78`,
    // decidiendo el factor por la cadena de tipo de red. El resultado era una incoherencia fea —
    // ThreatAnalyzer podía concluir "no puedo afirmar esta distancia" y abstenerse, mientras esta
    // pantalla le enseñaba al usuario un "3.000 m" muy convincente calculado con un multiplicador
    // inventado. Enseñar una distancia que el propio motor considera indemostrable es peor que no
    // enseñar ninguna: en una herramienta forense, la pantalla no puede afirmar más que el motor.
    //
    // Ahora ambos preguntan a TimingAdvanceUnit.toMeters(), así que no pueden discrepar.
    val isTaAvailable = active.timingAdvance != null && active.timingAdvance != Int.MAX_VALUE
    val taValue = active.timingAdvance ?: -1
    val taUnit = active.timingAdvanceUnit
    val distanceMeters: Int? = active.timingAdvance?.let { taUnit.toMeters(it) }
    // Resolución de la unidad: lo que vale "1" en esta tecnología. Solo para el texto "< X m".
    val unitStep: Int? = taUnit.toMeters(1)

    val distanceText = when {
        !isTaAvailable || taValue < 0 -> stringResource(R.string.ta_unavailable)
        distanceMeters == null -> stringResource(R.string.ta_no_conversion)
        distanceMeters == 0 && unitStep != null -> "< $unitStep m"
        distanceMeters >= 1000 -> String.format(Locale.ROOT, "%.2f km", distanceMeters / 1000f)
        else -> "$distanceMeters m"
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        HorizontalDivider(color = Color(0xFF1A1A1A))
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column {
                Text(stringResource(R.string.geometric_telemetry), color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (isTaAvailable && taValue >= 0) stringResource(R.string.ta_value_format, taValue, taUnit.name)
                    else stringResource(R.string.ta_blocked),
                    color = Color(0xFFFFA000), fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.ta_distance), color = Color(0xFF444444), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text(distanceText, color = if (distanceMeters != null) Color.White else Color(0xFF888888), fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)

                // v2.1 — Segunda estimación, por una vía completamente distinta: la coordenada
                // que las bases públicas atribuyen a esta antena frente a tu fix GPS. Se muestra
                // aparte y con su procedencia a la vista porque su precisión NO es comparable con
                // la del TA: una coordenada colaborativa puede estar a cientos de metros del
                // emplazamiento real. Presentarlas como si fueran lo mismo sería engañar.
                val towerDistance = active.distanceToTowerMeters
                if (towerDistance != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.public_database_distance), color = Color(0xFF444444), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        if (towerDistance >= 1000) String.format(Locale.ROOT, "%.2f km", towerDistance / 1000f)
                        else "$towerDistance m",
                        color = Color(0xFF4CAF50), fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(100.dp).padding(vertical = 8.dp)) {
            if (geoHistory.isEmpty() || !isTaAvailable || taValue < 0 || distanceMeters == null) {
                 val motivo = if (isTaAvailable && distanceMeters == null) "TA SIN UNIDAD CONVERTIBLE (${taUnit.name})"
                              else "SENSOR DE TA BLOQUEADO POR HARDWARE"
                 drawContext.canvas.nativeCanvas.drawText(motivo, 20f, 50f, android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY; textSize = 26f })
                 return@Canvas
            }
            
            val maxPoints = 50
            val width = size.width
            val height = size.height
            val maxTa = (geoHistory.maxOrNull() ?: 10f).coerceAtLeast(10f) * 1.5f 
            val stepX = width / (maxPoints - 1)
            val startX = width - ((geoHistory.size - 1) * stepX)
            
            val path = Path()
            geoHistory.forEachIndexed { index, ta ->
                val normalizedY = 1f - (ta / maxTa).coerceIn(0f, 1f)
                val y = (height * normalizedY).coerceAtMost(height - 2f)
                val x = startX + (index * stepX)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = Color(0xFFFFA000), style = Stroke(width = 3f))
        }
        
        if (!isTaAvailable || taValue < 0) {
             Text(stringResource(R.string.ta_not_reported), color = Color(0xFFCF6679), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        } else if (taUnit == TimingAdvanceUnit.STUB_ZERO) {
             Text(stringResource(R.string.ta_stub_zero), color = Color(0xFFCF6679), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        } else if (distanceMeters == null) {
             Text(stringResource(R.string.ta_no_conversion_format, taUnit.name), color = Color(0xFFCF6679), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        } else if (taValue == 0) {
            Text(stringResource(R.string.ta_zero_format, unitStep ?: 0), color = Color(0xFFCF6679), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        } else {
             Text(stringResource(R.string.ta_graph_legend), color = Color(0xFFFFA000), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MiniRsrpGraph(dbmHistory: List<Int>, currentDbm: Int) {
    val umbral = -70

    val labelColor = when {
        currentDbm >= -70 -> Color.Red
        currentDbm >= -85 -> Color(0xFFFFA000)
        else              -> Color(0xFF4CAF50)
    }

    val labelText = when {
        currentDbm >= -70 -> "RSRP ⚠"
        currentDbm >= -85 -> "RSRP ~"
        else              -> "RSRP OK"
    }

    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = labelText,
            color = labelColor,
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Canvas(modifier = Modifier.width(75.dp).height(30.dp)) {
            val w = size.width; val h = size.height
            // Línea umbral punteada
            val uy = h * (1f - ((umbral - (-140f)) / 100f))
            drawLine(
                Color.Red.copy(alpha = 0.4f),
                Offset(0f, uy), Offset(w, uy),
                strokeWidth = 1f,
                pathEffect = androidx.compose.ui.graphics.PathEffect
                    .dashPathEffect(floatArrayOf(5f, 5f))
            )
            // Línea RSRP
            if (dbmHistory.size >= 2) {
                val step = w / 49f
                val start = w - (dbmHistory.size - 1) * step
                for (i in 0 until dbmHistory.size - 1) {
                    val v1 = dbmHistory[i]; val v2 = dbmHistory[i + 1]
                    val x1 = start + i * step; val x2 = start + (i + 1) * step
                    val y1 = h * (1f - ((v1 - (-140f)) / 100f).coerceIn(0f, 1f))
                    val y2 = h * (1f - ((v2 - (-140f)) / 100f).coerceIn(0f, 1f))
                    val color = when {
                        v1 >= -70 || v2 >= -70 -> Color.Red
                        v1 >= -85 || v2 >= -85 -> Color(0xFFFFA000)
                        else                   -> Color(0xFF4CAF50)
                    }
                    drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2f)
                }
            }
        }
    }
}

@Composable
fun MiniRsrqGraph(rsrqHistory: List<Int>, currentRsrq: Int?) {
    val umbral = -15

    val labelColor = when {
        currentRsrq == null  -> Color(0xFF666666)
        currentRsrq <= -15   -> Color.Red
        currentRsrq <= -10   -> Color(0xFFFFA000)
        else                 -> Color(0xFF4CAF50)
    }

    val labelText = when {
        currentRsrq == null  -> "RSRQ N/A"
        currentRsrq <= -15   -> "RSRQ ⚠"
        currentRsrq <= -10   -> "RSRQ ~"
        else                 -> "RSRQ OK"
    }

    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = labelText,
            color = labelColor,
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Canvas(modifier = Modifier.width(75.dp).height(30.dp)) {
            val w = size.width; val h = size.height
            // Línea umbral punteada
            val uy = h * (1f - ((umbral - (-20f)) / 17f).coerceIn(0f, 1f))
            drawLine(
                Color(0xFFFFA000).copy(alpha = 0.4f),
                Offset(0f, uy), Offset(w, uy),
                strokeWidth = 1f,
                pathEffect = androidx.compose.ui.graphics.PathEffect
                    .dashPathEffect(floatArrayOf(5f, 5f))
            )
            // Línea RSRQ
            if (rsrqHistory.size >= 2) {
                val step = w / 49f
                val start = w - (rsrqHistory.size - 1) * step
                for (i in 0 until rsrqHistory.size - 1) {
                    val v1 = rsrqHistory[i]; val v2 = rsrqHistory[i + 1]
                    val x1 = start + i * step; val x2 = start + (i + 1) * step
                    val y1 = h * (1f - ((v1 - (-20f)) / 17f).coerceIn(0f, 1f))
                    val y2 = h * (1f - ((v2 - (-20f)) / 17f).coerceIn(0f, 1f))
                    val color = when {
                        v1 <= -15 || v2 <= -15 -> Color.Red
                        v1 <= -10 || v2 <= -10 -> Color(0xFFFFA000)
                        else                   -> Color(0xFF4CAF50)
                    }
                    drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2f)
                }
            } else if (currentRsrq == null) {
                drawContext.canvas.nativeCanvas.drawText(
                    "N/A",
                    w / 2f - 20f, h / 2f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.DKGRAY
                        textSize = 24f
                    }
                )
            }
        }
    }
}

@Composable
fun MiniSinrGraph(sinr: Int?) {
    // El SINR suele ir de -20 (pésimo) a +30 (excelente)
    val actualSinr = sinr ?: 0
    // Normalizamos el valor para la gráfica (de 0 a 1)
    // Asumimos un rango visual de -10 a +25 para que se mueva bien
    val normalized = ((actualSinr + 10).toFloat() / 35f).coerceIn(0f, 1f)

    // Colores: Si es menor o igual a 0 (alerta MITM), rojo. Si no, verde o amarillo.
    val barColor = when {
        actualSinr <= 0 -> Color(0xFFCF6679) // Rojo alerta (Ruido > Señal)
        actualSinr in 1..10 -> Color(0xFFFFEB3B) // Amarillo (Interferencia media)
        else -> Color(0xFF4CAF50) // Verde (Señal limpia)
    }

    Column(
        modifier = Modifier.width(60.dp),
        horizontalAlignment = Alignment.End
    ) {
        Text(
            text = "SINR",
            color = Color.Gray,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.height(24.dp)
        ) {
            Text(
                text = "${sinr ?: "--"} dB",
                color = barColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            // Pequeña barra visual
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(Color.DarkGray)
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight(normalized)
                        .background(barColor)
                        .align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/**
 * Indicador de banda física. Línea compacta "BANDA  [B20 · 800MHz]".
 *
 * Mapeo correcto por tipo de red:
 *  - 4G LTE y 5G NSA  -> la celda registrada es LTE (en NSA, el ancla), su arfcn es un
 *    EARFCN válido, así que se mapea con BandPlan.
 *  - 5G SA            -> el arfcn es un NR-ARFCN (otra tabla); NO se mapea con la tabla
 *    LTE (daría una banda falsa). Se muestra "5G".
 *  - Resto / desconocido -> "—".
 *
 * Rojo (heurística 14 disparada) o gris neutro.
 */
@Composable
fun BandIndicator(arfcn: Int?, networkType: String, suspicious: Boolean) {
    val isSa = networkType.contains("(SA)")
    val mappable = !isSa && (
        networkType.contains("LTE") ||
        networkType.contains("4G") ||
        networkType.contains("NSA")
    )
    val band = if (mappable) arfcn?.let { BandPlan.earfcnToBandLte(it) } else null
    val freq = band?.let { BandPlan.approxFreqMhz(it) }

    val label = when {
        band != null && freq != null -> "B$band · ${freq}MHz"
        isSa -> "5G NR"
        else -> "—"
    }
    val color = if (suspicious) Color(0xFFCF6679) else Color(0xFF888888)

    Column {
        Text(
            text = stringResource(R.string.radio_band),
            color = Color(0xFF555555),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = label,
                color = color,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
