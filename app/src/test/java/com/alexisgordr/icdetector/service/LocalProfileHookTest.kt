package com.alexisgordr.icdetector.service

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 3.0 — Modo sombra: el perfil local se llama después de guardar cada fila. Las filas guardadas, su
 * orden y lo que recibe el servicio ([ObservationPersistenceController] `onWrite`) son idénticos
 * con el perfil, sin él o con un perfil que falla.
 */
class LocalProfileHookTest {
    private var now = 1_791_540_000_000L
    private val cell = CellData(
        isRegistered = true, networkType = "4G LTE", cellId = "100", mnc = "07", tac = "1", dbm = -80,
        mcc = "214", radioTech = RadioTech.LTE
    )

    private class Run(val rows: MutableList<ObservationRow> = mutableListOf(), val writes: MutableList<Long> = mutableListOf(),
                      val events: MutableList<String> = mutableListOf())

    private fun run(afterWrite: ((ObservationRow, Long) -> Unit)?, insertResult: (Int) -> Long = { it.toLong() }): Run {
        val r = Run()
        val base = { row: ObservationRow -> r.rows += row; insertResult(r.rows.size) }
        val c = if (afterWrite == null) ObservationPersistenceController(
            scope = CoroutineScope(Dispatchers.Unconfined), insert = base, location = { null },
            onWrite = { r.writes += it; r.events += "write:$it" }, onPeriodicMissingLocation = {},
            writeDispatcher = Dispatchers.Unconfined, clock = { now }
        ) else ObservationPersistenceController(
            scope = CoroutineScope(Dispatchers.Unconfined), insert = base, location = { null },
            onWrite = { r.writes += it; r.events += "write:$it" }, onPeriodicMissingLocation = {},
            writeDispatcher = Dispatchers.Unconfined, clock = { now },
            afterWrite = { row, id -> r.events += "profile:$id"; afterWrite(row, id) }
        )
        c.recordPeriodicIfDue(cell, screenOn = true, context = c.capture())
        now += 60_000L
        c.recordHandover(cell.copy(cellId = "200"), c.capture())
        now += 60_000L
        c.recordConfirmedAlarm(cell.copy(cellId = "300", suspiciousReason = "H3"), c.capture())
        return r
    }

    @Test fun `rows and write results are identical with, without or with a failing profile`() {
        val without = run(null)
        val with = run { _, _ -> }
        val failing = run { _, _ -> throw IllegalStateException("perfil roto") }
        listOf(with, failing).forEach {
            assertEquals(without.rows.map { r -> r.cell to r.reason }, it.rows.map { r -> r.cell to r.reason })
            assertEquals(without.writes, it.writes)
        }
        assertEquals(listOf(1L, 2L, 3L), without.writes)
    }

    @Test fun `the profile runs after the row is saved and only for saved rows`() {
        assertEquals(listOf("write:1", "profile:1", "write:2", "profile:2", "write:3", "profile:3"), run { _, _ -> }.events)
        assertEquals(listOf("write:-1", "write:-1", "write:-1"), run({ _, _ -> }, insertResult = { -1L }).events)
    }
}
