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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.alexisgordr.icdetector.R
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
            text = stringResource(R.string.author_signature),
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
            HeuristicStatus.PASSED -> stringResource(R.string.status_passed) to Color(0xFF4CAF50)
            HeuristicStatus.FAILED -> stringResource(R.string.status_failed) to Color(0xFFCF6679)
            HeuristicStatus.NOT_EVALUATED -> "N/A" to Color(0xFFFFA000)
        }
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun HeuristicDiagnosticItem(item: HeuristicDiagnostic) {
    val names = stringArrayResource(R.array.heuristic_names)
    val name = names.getOrNull(item.id - 1) ?: stringResource(R.string.heuristic_rule_format, item.id)
    val english = LocalConfiguration.current.locales[0].language != "es"
    val explanation = if (english) localizeTerminalLine(item.explanation) else item.explanation
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        HeuristicItem("H${item.id} · $name", item.status)
        Text(
            explanation,
            color = Color(0xFF777777),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 12.dp, top = 1.dp)
        )
    }
}

@Composable
fun TransitionCoherenceCard(result: TransitionCoherenceResult) {
    val (label, color) = when (result.status) {
        HeuristicStatus.PASSED -> stringResource(R.string.status_coherent) to Color(0xFF4CAF50)
        HeuristicStatus.FAILED -> stringResource(R.string.status_incoherent) to Color(0xFFCF6679)
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
                    stringResource(R.string.mobility_sanity),
                    color = Color(0xFFCCCCCC), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
                Text(label, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(5.dp))
            Text(when (result.status) {
                HeuristicStatus.PASSED -> stringResource(R.string.transition_explanation_passed)
                HeuristicStatus.FAILED -> stringResource(R.string.transition_explanation_failed)
                HeuristicStatus.NOT_EVALUATED -> stringResource(R.string.transition_explanation_na)
            }, color = Color(0xFF888888), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun VerificationBadge(status: VerificationStatus, compact: Boolean = false) {
    val (text, color) = when (status) {
        VerificationStatus.VERIFIED -> stringResource(R.string.badge_registered) to Color(0xFF4CAF50)
        // "NO ENCONTRADA" en rojo era otra herencia de cuando no estar en una base pública se
        // trataba como un indicio. No lo es: las bases están incompletas. Gris, como corresponde a
        // un dato de contexto que no acusa a nadie.
        VerificationStatus.NOT_FOUND -> stringResource(R.string.badge_not_found) to Color(0xFF888888)
        VerificationStatus.REJECTED -> stringResource(R.string.badge_rejected) to Color(0xFFFFA000)
        VerificationStatus.PENDING -> stringResource(R.string.badge_pending) to Color(0xFF888888)
        VerificationStatus.ERROR -> stringResource(R.string.badge_error) to Color(0xFFFFA000)
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
