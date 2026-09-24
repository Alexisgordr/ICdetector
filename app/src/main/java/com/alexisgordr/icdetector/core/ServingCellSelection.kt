package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellConnectionState
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.SecondaryCarrier

/**
 * v2.10.4 — Qué entrada de `getAllCellInfo()` es LA celda servidora.
 *
 * Con agregación de portadoras (y en 5G NSA) el módem puede publicar varias entradas con
 * `isRegistered = true`. Hasta v2.10.3 el servicio tomaba la PRIMERA registrada para el
 * historial, Stable-Site y H15, pero analizaba TODAS las registradas y se quedaba con la de más
 * señal. Si la más fuerte era una portadora secundaria, se juzgaba con el historial de otra celda
 * y se guardaba como si fuera la servidora: el origen de buena parte de la confusión que obligó a
 * H15 a comparar PCI solo dentro de cada ARFCN.
 *
 * Regla nueva, una sola y en un solo sitio:
 *  1. Si alguna entrada registrada declara `PRIMARY_SERVING`, esa es la servidora.
 *  2. Si ninguna lo declara (módems que no rellenan el campo), la primera registrada, que es el
 *     comportamiento anterior para el caso de una sola entrada.
 *
 * Nunca se elige por potencia: la portadora con más señal no es necesariamente la que lleva el
 * control de la conexión.
 */
object ServingCellSelection {

    fun hasDeclaredPrimary(cells: List<CellData>): Boolean = cells.any {
        it.isRegistered && it.connectionState == CellConnectionState.PRIMARY_SERVING
    }

    /**
     * Publicación segura cuando existe una primaria declarada pero su muestra no es utilizable.
     * Ninguna secundaria puede quedar expuesta como si fuera la celda activa.
     */
    fun abstentionPublication(): List<CellData> = emptyList()

    /**
     * Índice de la celda servidora utilizable en [cells], o -1 si el ciclo debe abstenerse.
     *
     * Una PRIMARY_SERVING declarada es autoritativa incluso cuando su potencia no se puede leer:
     * en ese caso no se permite que una secundaria ocupe su lugar. El fallback histórico solo se
     * aplica cuando el módem no declaró ninguna primaria.
     */
    fun primaryIndex(cells: List<CellData>): Int {
        val declaredPrimary = cells.indexOfFirst {
            it.isRegistered && it.connectionState == CellConnectionState.PRIMARY_SERVING
        }
        if (declaredPrimary >= 0) {
            return declaredPrimary.takeIf { cells[it].hasUsableSignal() } ?: -1
        }
        return cells.indexOfFirst { it.isRegistered && it.hasUsableSignal() }
    }

    private fun CellData.hasUsableSignal(): Boolean = dbm != Int.MAX_VALUE && dbm < 100

    /**
     * Portadoras que acompañan a la servidora: entradas registradas distintas de ella y cualquier
     * celda que el módem declare `SECONDARY_SERVING` (la pata NR de NSA suele venir sin
     * `isRegistered`). Una entrada duplicada de la MISMA portadora que la primaria (mismo radio,
     * ARFCN y PCI) no es una portadora distinta y no se lista.
     */
    fun secondaryCarriers(cells: List<CellData>, primaryIndex: Int): List<SecondaryCarrier> {
        val primary = cells.getOrNull(primaryIndex)
        return cells.withIndex()
            .filter { (index, cell) ->
                index != primaryIndex &&
                    (cell.isRegistered || cell.connectionState == CellConnectionState.SECONDARY_SERVING)
            }
            .map { it.value }
            .filterNot { cell ->
                primary != null && cell.radioTech == primary.radioTech &&
                    cell.arfcn == primary.arfcn && cell.pci == primary.pci
            }
            .map { cell ->
                SecondaryCarrier(
                    radio = cell.radioTech,
                    arfcn = cell.arfcn,
                    pci = cell.pci,
                    bandwidthKhz = cell.bandwidthKhz,
                    connectionState = cell.connectionState
                )
            }
            .distinct()
    }

    /**
     * Devuelve una copia de [cells] con la servidora en la posición 0 y sus portadoras
     * secundarias adjuntas. El resto conserva su orden. Así todo el código que busca "la
     * primera registrada" encuentra la servidora correcta sin cambiar cada llamada.
     */
    fun withPrimaryFirst(cells: List<CellData>): List<CellData> {
        val index = primaryIndex(cells)
        if (index < 0) return cells
        val primary = cells[index].copy(secondaryCarriers = secondaryCarriers(cells, index))
        return listOf(primary) + cells.filterIndexed { i, _ -> i != index }
    }
}
