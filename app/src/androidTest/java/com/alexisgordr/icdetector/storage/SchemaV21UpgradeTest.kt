package com.alexisgordr.icdetector.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 3.0 — Actualización real 20 -> 21 con el SQLite de Android: las filas del esquema 20 siguen ahí con
 * el modo de ubicación vacío (desconocido) y las nuevas guardan el modo con el que se observaron.
 */
@RunWith(AndroidJUnit4::class)
class SchemaV21UpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var helper: CellDbHelper? = null

    @Before fun createSchema20() {
        context.deleteDatabase(SchemaV20UpgradeTest.NAME)
        val file = context.getDatabasePath(SchemaV20UpgradeTest.NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            SchemaV20UpgradeTest.V19.forEach(db::execSQL)
            SchemaV20.STATEMENTS.forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO history (timestamp, net_type, cid, mnc, tac, mcc, dbm, verified, score, " +
                    "failed_heuristics, radio, observed_at_ms, app_version) VALUES ('2026-10-01 10:00:00', " +
                    "'LTE', '100', '07', '1', '214', -90, 'PENDING', 100, 'OK', 'LTE', 1790000000000, '3.0.0-beta4')"
            )
            db.version = 20
        }
    }

    @After fun after() { helper?.close(); context.deleteDatabase(SchemaV20UpgradeTest.NAME) }

    @Test fun upgradeKeepsRowsWithUnknownModeAndNewRowsStoreIt() {
        val db = CellDbHelper(context).also { helper = it }
        assertEquals(SchemaV21.VERSION, db.readableDatabase.version)

        val old = db.getRecords().single()
        assertEquals("3.0.0-beta4", old.appVersion)
        assertNull(old.locationMode)

        db.logConnection(
            observedAtMs = System.currentTimeMillis(), netType = "LTE", cid = "100", mnc = "07", tac = "1",
            mcc = "214", dbm = -90, radio = RadioTech.LTE, appVersion = "3.0.0-beta4", locationMode = "ADAPTIVE"
        )
        assertEquals("ADAPTIVE", db.getRecords().first().locationMode)
    }
}
