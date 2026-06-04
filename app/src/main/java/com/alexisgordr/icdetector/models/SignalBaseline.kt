package com.alexisgordr.icdetector.models

/**
 * Línea base de potencia (dBm) aprendida del propio historial del usuario
 * para una celda concreta, en torno a una ubicación dada (el mismo sitio).
 * Detecta potencia anómalamente FUERTE (posible transmisor cercano suplantando
 * una celda que aquí suele verse más débil). Offline y sin datos etiquetados.
 */
data class SignalBaseline(
    val sampleCount: Int,
    val meanDbm: Double,
    val stdDevDbm: Double,
    val minDbm: Int,
    val maxDbm: Int,
    val p95Dbm: Int = 0,   // percentil 95 del dBm histórico (0 = no calculado / pocas muestras)
    val p99Dbm: Int = 0    // percentil 99 del dBm histórico (cola alta legítima de la celda)
)
