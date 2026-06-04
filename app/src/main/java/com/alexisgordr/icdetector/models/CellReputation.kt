package com.alexisgordr.icdetector.models

/**
 * Reputación de una celda derivada del PROPIO historial del usuario (offline, sin datos
 * externos ni cambios de esquema: se calcula leyendo la columna 'score' ya almacenada).
 *
 * Idea: una celda vista muchas veces, a lo largo de varios días, y siempre con puntuación
 * limpia, es casi con seguridad legítima — ha "demostrado" su buen comportamiento con el
 * tiempo. Esa confianza se usa ÚNICAMENTE para AMORTIGUAR el ruido de las heurísticas
 * débiles (instantáneas) sobre celdas ya probadas, reduciendo falsos positivos. Nunca se
 * usa para aumentar la sospecha (eso crearía bucles y falsos positivos), ni afecta a las
 * heurísticas físicas (MCC, cifrado, H11, H13, H15).
 *
 * @property observations  nº de observaciones consideradas (ventana reciente)
 * @property distinctDays   nº de días distintos en que se ha visto (volumen temporal)
 * @property cleanRatio     fracción de observaciones con puntuación limpia [0..1]
 * @property trustScore     confianza resultante [0..100]; <0 o bajo = desconocida/sin datos
 */
data class CellReputation(
    val observations: Int,
    val distinctDays: Int,
    val cleanRatio: Double,
    val trustScore: Int
)
