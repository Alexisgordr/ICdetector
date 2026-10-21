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
    /**
     * Percentil 99 del dBm histórico (cola alta legítima de la celda); null = no calculado.
     * 3.1.0 (#40) — Era `Int` con 0 como «no calculado», y 0 también es un valor que el historial
     * puede tener: un P99 real de 0 dBm desactivaba el refuerzo de H13 como si no existiera.
     */
    val p99Dbm: Int? = null
)
