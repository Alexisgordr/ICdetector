package com.alexisgordr.icdetector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.alexisgordr.icdetector.models.CellData
import java.util.Locale

@Composable
fun SignalVisualizer(active: CellData, neighbors: List<CellData>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("COMPARATIVA DE SEÑAL", color = Color(0xFF444444), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        
        SignalBarSegmented(
            label = "ACTIVA (${active.networkType})", 
            dbm = active.dbm, 
            isMain = true,
            isSuspicious = active.isSuspicious
        )
        
        neighbors.asSequence().sortedByDescending { it.dbm }.take(3).forEach { neighbor ->
            SignalBarSegmented(
                label = "VECINA (${neighbor.cellId})", 
                dbm = neighbor.dbm, 
                isMain = false,
                isSuspicious = neighbor.isSuspicious
            )
        }
    }
}


@Composable
fun GeoGraph(active: CellData, geoHistory: List<Float>) {
    val isTaAvailable = active.timingAdvance != null && active.timingAdvance != Int.MAX_VALUE
    val taValue = active.timingAdvance ?: -1 
    
    val is5g = active.networkType.contains("5G")
    val multiplier = if (is5g) 150 else 78
    val distanceMeters = taValue * multiplier
    
    val distanceText = when {
        !isTaAvailable || taValue < 0 -> "NO DISPONIBLE"
        taValue == 0 -> "< $multiplier m"
        distanceMeters >= 1000 -> String.format(Locale.ROOT, "%.2f km", distanceMeters / 1000f)
        else -> "$distanceMeters m"
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        HorizontalDivider(color = Color(0xFF1A1A1A))
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column {
                Text("TELEMETRÍA GEOMÉTRICA (TA)", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(if (isTaAvailable && taValue >= 0) "Timing Advance: $taValue" else "Timing Advance: BLOQUEADO", color = Color(0xFFFFA000), fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("DISTANCIA FÍSICA APROX.", color = Color(0xFF444444), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text(distanceText, color = if (isTaAvailable && taValue >= 0) Color.White else Color(0xFF888888), fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(100.dp).padding(vertical = 8.dp)) {
            if (geoHistory.isEmpty() || !isTaAvailable || taValue < 0) {
                 drawContext.canvas.nativeCanvas.drawText("SENSOR DE TA BLOQUEADO POR HARDWARE", 20f, 50f, android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY; textSize = 30f })
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
             Text("⚠️ NOTA: El chipset Tensor de Google restringe el acceso al TA en ciertas celdas para ahorrar energía. Esto no es un fallo de tu app, es una limitación de seguridad del hardware.", color = Color(0xFFCF6679), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        } else if (taValue == 0) {
            Text("⚠️ TA=0: Distancia < $multiplier m o limitación del modem", color = Color(0xFFCF6679), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        } else {
             Text("NARANJA = VARIACIÓN DE DISTANCIA (TA)", color = Color(0xFFFFA000), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
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
