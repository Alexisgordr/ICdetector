package com.alexisgordr.icdetector.service

import com.alexisgordr.icdetector.models.CellData
import kotlinx.coroutines.flow.MutableStateFlow

/** Maintains the bounded live charts shown by the monitoring screen. */
internal class TelemetryHistoryController(
    private val dbmHistory: MutableStateFlow<List<Int>>,
    private val rsrqHistory: MutableStateFlow<List<Int>>,
    private val geoHistory: MutableStateFlow<List<Float>>
) {
    fun onHandover() {
        dbmHistory.value = emptyList()
    }

    fun record(cell: CellData) {
        val dbm = cell.dbm
        if (dbm == -999 || dbm == Int.MAX_VALUE) return
        dbmHistory.value = appendBounded(dbmHistory.value, dbm)

        cell.rsrq?.takeUnless { it == Int.MAX_VALUE }?.let {
            rsrqHistory.value = appendBounded(rsrqHistory.value, it)
        }

        cell.timingAdvance
            ?.let { cell.timingAdvanceUnit.toMeters(it) }
            ?.let { geoHistory.value = appendBounded(geoHistory.value, it.toFloat()) }
    }

    private fun <T> appendBounded(current: List<T>, value: T): List<T> =
        (current + value).takeLast(MAX_POINTS)

    private companion object {
        const val MAX_POINTS = 50
    }
}
