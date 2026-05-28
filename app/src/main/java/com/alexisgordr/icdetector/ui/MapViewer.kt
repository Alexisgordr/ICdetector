package com.alexisgordr.icdetector.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun LocationButton(lat: Double?, lon: Double?, cellId: String) {
    // Si no hay coordenadas, no mostrar nada
    if (lat == null || lon == null) return
    
    val context = LocalContext.current
    
    IconButton(
        onClick = {
            // Abrir OpenStreetMap en el navegador (libre, sin API key)
            val uri = Uri.parse("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon&zoom=15")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        },
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            Icons.Default.Map,
            contentDescription = "Ver ubicación en OpenStreetMap",
            tint = Color(0xFF4CAF50),
            modifier = Modifier.size(18.dp)
        )
    }
}
