package com.alexisgordr.icdetector.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LiveTerminalPanel(logs: List<String>) {
    val scrollState = rememberScrollState()

    LaunchedEffect(logs.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF050505)),
        border = BorderStroke(1.dp, Color(0xFF222222)),
        shape = RoundedCornerShape(4.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                "RAW TERMINAL OUTPUT", 
                color = Color(0xFF444444), 
                fontFamily = FontFamily.Monospace, 
                fontSize = 8.sp, 
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                logs.forEach { logLine ->
                    Text(
                        text = logLine,
                        color = if (logLine.contains("PELIGRO") || logLine.contains("⚠️") || logLine.contains("🚨") || logLine.contains("ALERTA")) Color(0xFFCF6679) 
                                else if (logLine.contains("✅") || logLine.contains("VERIFICADA") || logLine.contains("SYS:")) Color(0xFF4CAF50)
                                else Color(0xFF00FF00),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        lineHeight = 12.sp
                    )
                }
            }
        }
    }
}
