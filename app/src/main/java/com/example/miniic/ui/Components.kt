package com.example.miniic.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.example.miniic.models.CellData
import com.example.miniic.models.VerificationStatus

@Composable
fun DataBox(modifier: Modifier, label: String, value: String, highlight: Boolean = false) {
    Column(modifier = modifier) {
        Text(label, color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            text = value, 
            color = if (highlight) Color(0xFFE0E0E0) else Color.White, 
            fontFamily = FontFamily.Monospace, 
            fontSize = 14.sp, 
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun AuthorSignature() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val context = LocalContext.current
        TextButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, "https://buymeacoffee.com/alexisgomez".toUri())
                context.startActivity(intent)
            },
            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFDD00)),
            modifier = Modifier
                .height(32.dp)
                .border(BorderStroke(0.5.dp, Color(0xFF333333)), RoundedCornerShape(16.dp))
                .padding(horizontal = 8.dp)
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
fun HeuristicItem(label: String, passed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFFCCCCCC), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        if (passed) {
            Icon(Icons.Default.CheckCircle, contentDescription = "Pass", tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp))
        } else {
            Icon(Icons.Default.Cancel, contentDescription = "Fail", tint = Color(0xFFCF6679), modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
fun VerificationBadge(status: VerificationStatus) {
    val (text, color) = when (status) {
        VerificationStatus.VERIFIED -> "REGISTRADA" to Color(0xFF4CAF50)
        VerificationStatus.NOT_FOUND -> "NO ENCONTRADA" to Color(0xFFF44336)
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
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
