package com.alexisgordr.icdetector.storage

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2.10.6 — Pantallas y exportaciones de Topología y Geometry leen todas las rutas guardadas.
 * Antes se quedaban, en silencio, en 250 (Topología), 400 (pantalla Geometry) y 1.000 (export).
 */
@RunWith(AndroidJUnit4::class)
class TransitionExportLimitTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CellDbHelper

    @Before fun before() { context.deleteDatabase(NAME); db = CellDbHelper(context); db.writableDatabase }
    @After fun after() { db.close(); context.deleteDatabase(NAME) }

    @Test fun everyStoredRouteIsReturnedBeyondTheOldCaps() {
        val routes = 1_200
        val sqlite = db.writableDatabase
        sqlite.beginTransaction()
        try {
            repeat(routes) { i ->
                sqlite.insertOrThrow("cell_transitions", null, ContentValues().apply {
                    put("from_identity", "214-07-1-$i-LTE")
                    put("to_identity", "214-07-1-${i + 1}-LTE")
                    put("observations", 1)
                    put("trusted_observations", 0)
                    put("last_status", "PASSED")
                    put("last_seen_ms", 1_000L + i)
                })
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }

        // La consulta acotada sigue existiendo y devuelve las rutas más recientes.
        assertEquals(250, db.getCellTransitions().size)
        assertEquals(1_000, db.getCellTransitions(limit = 5_000).size)
        assertEquals(1_000L + routes - 1, db.getCellTransitions().first().lastSeenMs)
        assertEquals(400, db.getMobilityGeometrySnapshot(limit = 400).transitions.size)

        // Pantallas y exportaciones: todas.
        assertEquals(routes, db.getAllCellTransitions().size)
        val snapshot = db.getMobilityGeometrySnapshot(limit = null)
        assertEquals(routes, snapshot.transitions.size)
        assertEquals(routes + 1, snapshot.cells.size)
    }

    private companion object { const val NAME = "icdetector_history.db" }
}
