package com.alexisgordr.icdetector.ui

import androidx.compose.foundation.Canvas
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
fun TelemetryGraph(dbmHistory: List<Int>) {
    val umbralPeligro = -70
    
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        HorizontalDivider(color = Color(0xFF1A1A1A))
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("TELEMETRÍA RSRP [UMBRAL: $umbralPeligro dBm]", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("Límite: -40 dBm", color = Color(0xFF444444), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(100.dp).padding(vertical = 8.dp)) {
            val maxPoints = 50
            val width = size.width
            val height = size.height
            
            val normalizedUmbral = 1f - ((umbralPeligro - (-140f)) / 100f)
            val umbralY = height * normalizedUmbral
            drawLine(
                color = Color.Red.copy(alpha = 0.5f),
                start = Offset(0f, umbralY),
                end = Offset(width, umbralY),
                strokeWidth = 2f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            )

            if (dbmHistory.isEmpty()) return@Canvas
            
            val minDbm = -140f
            val maxDbm = -40f
            val range = maxDbm - minDbm
            val stepX = width / (maxPoints - 1)
            val startX = width - ((dbmHistory.size - 1) * stepX)
            
            for (i in 0 until (dbmHistory.size - 1)) {
                val dbm1 = dbmHistory[i]
                val dbm2 = dbmHistory[i + 1]
                
                val x1 = startX + (i * stepX)
                val y1 = height * (1f - ((dbm1 - minDbm) / range).coerceIn(0f, 1f))
                val x2 = startX + ((i + 1) * stepX)
                val y2 = height * (1f - ((dbm2 - minDbm) / range).coerceIn(0f, 1f))
                
                val color = if (dbm1 >= umbralPeligro || dbm2 >= umbralPeligro) Color.Red else Color(0xFF00FFCC)
                
                drawLine(
                    color = color,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 4f
                )
            }
        }
        Text("ROJO = ZONA DE ALTA POTENCIA (POSIBLE IMSI-CATCHER)", color = Color.Red, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
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
