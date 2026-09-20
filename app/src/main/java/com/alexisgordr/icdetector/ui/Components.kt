package com.alexisgordr.icdetector.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexisgordr.icdetector.models.VerificationStatus
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.HeuristicDiagnostic
import com.alexisgordr.icdetector.models.TransitionCoherenceResult

@Composable
fun AuthorSignature() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "HECHO POR ALEXIS G. // OPEN SOURCE",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Color(0xFF333333),
            letterSpacing = 2.sp
        )
    }
}

@Composable
fun SignalBarSegmented(label: String, dbm: Int, isMain: Boolean, isSuspicious: Boolean) {
    val totalBlocks = 10
    val activeBlocks = (((dbm + 140).coerceIn(0, 100).toFloat() / 100f) * totalBlocks).toInt()
    
    val blockColor = when {
        isSuspicious -> Color.Red
        isMain -> Color(0xFF4CAF50)
        else -> Color(0xFF888888)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFF666666), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text("$dbm dBm", color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            for (i in 0 until totalBlocks) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(
                            if (i < activeBlocks) blockColor else Color(0xFF1A1A1A),
                            RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}

@Composable
fun HeuristicItem(label: String, status: HeuristicStatus) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), color = Color(0xFFCCCCCC), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(8.dp))
        val (text, color) = when (status) {
            HeuristicStatus.PASSED -> "PASSED" to Color(0xFF4CAF50)
            HeuristicStatus.FAILED -> "FAILED" to Color(0xFFCF6679)
            HeuristicStatus.NOT_EVALUATED -> "N/A" to Color(0xFFFFA000)
        }
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun HeuristicDiagnosticItem(item: HeuristicDiagnostic) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        HeuristicItem("${item.id}. ${item.name}", item.status)
        if (item.status == HeuristicStatus.NOT_EVALUATED) {
            Text(
                item.explanation,
                color = Color(0xFF777777),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 12.dp, top = 1.dp)
            )
        }
    }
}

@Composable
fun TransitionCoherenceCard(result: TransitionCoherenceResult) {
    val (label, color) = when (result.status) {
        HeuristicStatus.PASSED -> "COHERENTE" to Color(0xFF4CAF50)
        HeuristicStatus.FAILED -> "INCOHERENTE" to Color(0xFFCF6679)
        HeuristicStatus.NOT_EVALUATED -> "N/A" to Color(0xFFFFA000)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(3.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "CORDURA DE MOVILIDAD · H16",
                    color = Color(0xFFCCCCCC), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
                Text(label, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(5.dp))
            Text(result.explanation, color = Color(0xFF888888), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun VerificationBadge(status: VerificationStatus, compact: Boolean = false) {
    val (text, color) = when (status) {
        VerificationStatus.VERIFIED -> "REGISTRADA" to Color(0xFF4CAF50)
        // "NO ENCONTRADA" en rojo era otra herencia de cuando no estar en una base pública se
        // trataba como un indicio. No lo es: las bases están incompletas. Gris, como corresponde a
        // un dato de contexto que no acusa a nadie.
        VerificationStatus.NOT_FOUND -> "SIN REGISTRO" to Color(0xFF888888)
        VerificationStatus.REJECTED -> "RESPUESTA DESCARTADA" to Color(0xFFFFA000)
        VerificationStatus.PENDING -> "PENDIENTE" to Color(0xFF888888)
        VerificationStatus.ERROR -> "ERROR API" to Color(0xFFFFA000)
    }
    
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(2.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = text,
            color = color,
            fontSize = if (compact) 8.sp else 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(
                horizontal = if (compact) 4.dp else 6.dp,
                vertical = 2.dp
            )
        )
    }
}
