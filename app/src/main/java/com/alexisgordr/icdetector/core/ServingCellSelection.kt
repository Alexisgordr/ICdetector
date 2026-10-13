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
 *  2. Si ninguna lo declara (módems que no rellenan el campo), la primera registrada que NO se
 *     declare `SECONDARY_SERVING`. Un módem que solo rellena el estado en la pata NR la marcaría
 *     como secundaria; elegirla sería justo lo que esta regla evita. Si todas se declaran
 *     secundarias, el ciclo se abstiene.
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
        return cells.indexOfFirst {
            it.isRegistered && it.connectionState != CellConnectionState.SECONDARY_SERVING && it.hasUsableSignal()
        }
    }

    private fun CellData.hasUsableSignal(): Boolean = dbm != Int.MAX_VALUE && dbm < 100

    /**
     * Portadoras que acompañan a la servidora: entradas registradas distintas de ella y cualquier
     * celda que el módem declare `SECONDARY_SERVING` (la pata NR de NSA suele venir sin
     * `isRegistered`). Una entrada duplicada de la MISMA portadora que la primaria (mismo radio,
     * ARFCN y PCI) no es una portadora distinta y no se lista.
     *
     * 3.0 — «Misma portadora» exige que ARFCN y PCI sean válidos para su tecnología en los dos lados
     * (RadioChannels), como en `rfDifference`: desconocido no es coincidencia. Antes dos entradas
     * con ARFCN y PCI sin informar se daban por la misma (null == null) y la segunda desaparecía.
     * Lo mismo al quitar repetidas entre las secundarias.
     */
    fun secondaryCarriers(cells: List<CellData>, primaryIndex: Int): List<SecondaryCarrier> {
        val primary = cells.getOrNull(primaryIndex)
        return cells.withIndex()
            .filter { (index, cell) ->
                index != primaryIndex &&
                    (cell.isRegistered || cell.connectionState == CellConnectionState.SECONDARY_SERVING)
            }
            .map { it.value }
            .filterNot { cell -> primary != null && sameKnownCarrier(cell, primary) }
            .map { cell ->
                SecondaryCarrier(
                    radio = cell.radioTech,
                    arfcn = cell.arfcn,
                    pci = cell.pci,
                    bandwidthKhz = cell.bandwidthKhz,
                    connectionState = cell.connectionState
                )
            }
            .let(::distinctKnown)
    }

    private fun sameKnownCarrier(a: CellData, b: CellData): Boolean {
        if (a.radioTech != b.radioTech) return false
        val ca = RadioChannels.channelOrNull(a.radioTech, a.arfcn) ?: return false
        val pa = RadioChannels.physicalIdOrNull(a.radioTech, a.pci) ?: return false
        return ca == RadioChannels.channelOrNull(b.radioTech, b.arfcn) && pa == RadioChannels.physicalIdOrNull(b.radioTech, b.pci)
    }

    /** Quita repetidas solo si su ARFCN y PCI son válidos; las desconocidas se conservan todas. */
    private fun distinctKnown(carriers: List<SecondaryCarrier>): List<SecondaryCarrier> {
        val seen = HashSet<SecondaryCarrier>()
        return carriers.filter { c ->
            val known = RadioChannels.channelOrNull(c.radio, c.arfcn) != null && RadioChannels.physicalIdOrNull(c.radio, c.pci) != null
            !known || seen.add(c)
        }
    }

    /**
     * 3.0 — Las VECINAS que cuentan para el análisis: entradas no registradas que el módem no
     * declara `SECONDARY_SERVING`.
     *
     * Antes el servicio usaba `filter { !it.isRegistered }`, y la pata NR de NSA (o cualquier
     * portadora secundaria publicada sin `isRegistered`) entraba como vecina: rellenaba el recuento
     * de H1, H7 y la densidad del bayesiano, competía en H2 y Stable-Site la aprendía como entorno.
     * Una portadora que sirve a esta conexión no es una celda vecina, aunque sea de otra tecnología
     * o de otro emplazamiento: no se supone que comparta estación con la primaria, solo que su
     * papel es otro.
     *
     * Lo demás no cambia: una entrada no se excluye por ser NR, `NONE` y `UNKNOWN` no registradas
     * siguen siendo vecinas como antes (no se inventa su papel), y el filtro de potencia lo
     * aplica quien llama, igual que antes. Las secundarias siguen en [secondaryCarriers] y en
     * [nonServingEntries] para mostrarlas y registrarlas.
     */
    fun neighbours(cells: List<CellData>): List<CellData> = cells.filter {
        !it.isRegistered && it.connectionState != CellConnectionState.SECONDARY_SERVING
    }

    /**
     * Todas las entradas no registradas, secundarias incluidas: el contexto completo de radio que
     * se MUESTRA o se guarda como evidencia (pantalla, caja negra forense). No es la lista de
     * vecinas: para contar o comparar vecinas, [neighbours].
     */
    fun nonServingEntries(cells: List<CellData>): List<CellData> = cells.filter { !it.isRegistered }

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
