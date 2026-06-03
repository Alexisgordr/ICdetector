package com.alexisgordr.icdetector.models

/**
 * Estabilidad de identidad RF de una Cell ID (heurística H15 — lifecycle).
 *
 * Una antena legítima mantiene su PCI y su ARFCN FIJOS durante toda su vida. Si la misma
 * Cell ID (mismo CID+MNC+TAC+MCC) ha sido observada con varios PCI o ARFCN distintos a lo
 * largo del tiempo, sugiere que alguien está reutilizando ese identificador con distinta
 * configuración de radio: un clon reconfigurándose. A diferencia de H11, esto se detecta
 * aunque el usuario esté parado (no depende de la distancia, solo del historial de la celda).
 *
 * Las listas "distinct*" cubren los últimos 30 días (solo valores no nulos). Las listas
 * "recent*" cubren solo la ventana reciente (últimas 48 h). La distinción es clave para no
 * confundir una RECONFIGURACIÓN permanente del operador (el valor viejo desaparece y solo el
 * nuevo aparece reciente → benigno) con un CLON parpadeando (dos valores intercalados que
 * siguen apareciendo recientes → sospechoso). Cada par es (valor, nº de observaciones).
 */
data class CellRfStability(
    val totalObservations: Int,
    val distinctPci: List<Pair<Int, Int>>,
    val distinctArfcn: List<Pair<Int, Int>>,
    val recentDistinctPci: List<Pair<Int, Int>> = emptyList(),
    val recentDistinctArfcn: List<Pair<Int, Int>> = emptyList()
)
