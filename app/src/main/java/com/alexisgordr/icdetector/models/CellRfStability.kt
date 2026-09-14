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
 *
 * v2.1 — DESGLOSE POR PORTADORA (pciByArfcn / recentPciByArfcn).
 * Los datos de campo (59 días, 173 celdas) demostraron que el PCI SÍ "parpadea" de forma
 * benigna, por el mismo motivo que ya se descartó el ARFCN como señal de identidad: con
 * AGREGACIÓN DE PORTADORAS el módem atribuye a la celda servidora el PCI *y* el ARFCN de una
 * portadora secundaria. La correlación observada es perfecta — p. ej. una celda con PCI 200
 * visto SIEMPRE en ARFCN 6400 y PCI 473 visto SIEMPRE en ARFCN 3600, sin un solo cruce. No son
 * dos identidades alternándose: es una antena vista a través de dos portadoras.
 *
 * Por eso H15 pasa a comparar PCI ÚNICAMENTE dentro de una misma portadora. Un clon real que
 * reconfigura su PCI lo hace en su propia portadora, así que no se pierde detección.
 *
 * Clave del mapa = ARFCN observado; [UNKNOWN_ARFCN] agrupa las filas antiguas sin ARFCN
 * registrado. Vacío = historial sin datos de portadora → H15 usa la lógica global anterior.
 */
data class CellRfStability(
    val totalObservations: Int,
    val distinctPci: List<Pair<Int, Int>>,
    val distinctArfcn: List<Pair<Int, Int>>,
    val recentDistinctPci: List<Pair<Int, Int>> = emptyList(),
    val recentDistinctArfcn: List<Pair<Int, Int>> = emptyList(),
    /** ARFCN -> lista de (PCI, nº de observaciones) en los últimos 30 días. */
    val pciByArfcn: Map<Int, List<Pair<Int, Int>>> = emptyMap(),
    /** ARFCN -> lista de (PCI, nº de observaciones) en la ventana reciente (48 h). */
    val recentPciByArfcn: Map<Int, List<Pair<Int, Int>>> = emptyMap()
) {
    companion object {
        /** Clave para observaciones sin ARFCN registrado (filas anteriores a la migración v6). */
        const val UNKNOWN_ARFCN = -1
    }
}
