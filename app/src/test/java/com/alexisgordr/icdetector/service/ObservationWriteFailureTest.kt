package com.alexisgordr.icdetector.service

import com.alexisgordr.icdetector.core.CollectionHealth
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — Si abrir la base de datos lanza (disco lleno, base de datos ilegible), la fila cuenta como
 * fallida para la salud de recolección, como un insert rechazado; el error se registra.
 */
class ObservationWriteFailureTest {
    private val cell = CellData(
        isRegistered = true, networkType = "4G LTE", cellId = "100", mnc = "07", tac = "1", dbm = -80,
        mcc = "214", radioTech = RadioTech.LTE
    )

    private class DiskFull : RuntimeException("database or disk is full")

    @Test fun `an exception while opening the database is a failed write and is logged`() {
        val results = mutableListOf<Long>()
        val errors = mutableListOf<Throwable>()
        val persistence = ObservationPersistenceController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            insert = { throw DiskFull() },
            location = { null },
            onWrite = { results += it },
            onPeriodicMissingLocation = {},
            writeDispatcher = Dispatchers.Unconfined,
            onInsertError = { errors += it }
        )
        val context = persistence.capture()
        persistence.recordHandover(cell, context)
        persistence.recordConfirmedAlarm(cell, context)
        assertEquals(listOf(-1L, -1L), results)
        assertEquals(2, errors.size)
        assertTrue(errors.all { it is DiskFull })
    }

    @Test fun `collection health raises the write-failure warning`() {
        // La misma salud que usa CollectionHealthController: tres fallos seguidos activan el aviso.
        val health = CollectionHealth()
        var openFails = true
        val persistence = ObservationPersistenceController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            insert = { if (openFails) throw DiskFull() else 7L },
            location = { null },
            onWrite = { health.noteWrite(it, nowMs = 1_000L) },
            onPeriodicMissingLocation = {},
            writeDispatcher = Dispatchers.Unconfined
        )
        repeat(3) { persistence.recordHandover(cell, persistence.capture()) }
        assertTrue("ESCRITURA FALLIDA", health.isFailing)
        openFails = false
        persistence.recordHandover(cell, persistence.capture())
        assertFalse("se recupera con la primera escritura buena", health.isFailing)
    }

    @Test fun `the service logs the error instead of losing it`() {
        val src = listOf("src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt",
            "app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt").map(::File).first { it.exists() }.readText()
        assertTrue(src.contains("onWrite = ::noteWriteResult"))
        assertTrue(src.contains("onInsertError = { e -> Log.e(\"MiniIC\", \"Escritura del historial fallida: \${e.javaClass.simpleName}\", e) }"))
    }
}
