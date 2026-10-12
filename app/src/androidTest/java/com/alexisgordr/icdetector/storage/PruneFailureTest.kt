package com.alexisgordr.icdetector.storage

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 3.0 — Si la poda falla, se revierte entera y el error llega al llamador (no "0 filas"). */
@RunWith(AndroidJUnit4::class)
class PruneFailureTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var helper: CellDbHelper

    @Before fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        helper = CellDbHelper(context)
        val db = helper.writableDatabase
        // Dos filas muy antiguas y una reciente.
        listOf("2020-01-01 10:00:00", "2020-01-02 10:00:00", null).forEach { ts ->
            db.insertOrThrow(CellDbHelper.TABLE_HISTORY, null, ContentValues().apply {
                put(CellDbHelper.COLUMN_TIMESTAMP, ts ?: java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date()))
                put(CellDbHelper.COLUMN_NET_TYPE, "4G LTE"); put(CellDbHelper.COLUMN_CID, "1"); put(CellDbHelper.COLUMN_MNC, "07")
                put(CellDbHelper.COLUMN_TAC, "1"); put(CellDbHelper.COLUMN_MCC, "214"); put(CellDbHelper.COLUMN_DBM, -90)
            })
        }
    }

    @After fun tearDown() {
        helper.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test fun successDeletesOnlyOldRows() {
        assertEquals(2, helper.pruneOldRecords())
        assertEquals(1, count())
    }

    @Test fun aFailureRollsBackEverythingAndReachesTheCaller() {
        // La poda borra el historial y después los incidentes; hacemos fallar el segundo paso.
        helper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_prune BEFORE DELETE ON incidents BEGIN SELECT RAISE(ABORT, 'forced'); END"
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO incidents (started_at, updated_at, identity, cid, radio, state, highest_phase, required_phases, " +
                "score, anomaly_confidence, reason, heuristic_snapshot) VALUES ('2020-01-01 10:00:00', '2020-01-01 10:00:00', " +
                "'214-07-1-1-LTE', '1', 'LTE', 'RECOVERED', 1, 3, 70, 0.0, 'x', '')"
        )
        try {
            helper.pruneOldRecords()
            fail("el error debe llegar al llamador, no devolverse como 0 filas")
        } catch (_: Exception) { }
        assertEquals("el historial borrado en el primer paso se revierte", 3, count())
    }

    private fun count() = helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM history", null).use { it.moveToFirst(); it.getInt(0) }

    private companion object { const val DATABASE_NAME = "icdetector_history.db" }
}
