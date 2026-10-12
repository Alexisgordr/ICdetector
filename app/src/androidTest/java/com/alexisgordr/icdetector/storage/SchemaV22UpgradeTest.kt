package com.alexisgordr.icdetector.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.core.profile.DeviationLevel
import com.alexisgordr.icdetector.core.profile.MetricProfile
import com.alexisgordr.icdetector.core.profile.ProfileEvaluationRecord
import com.alexisgordr.icdetector.core.profile.ProfileMaturity
import com.alexisgordr.icdetector.core.profile.ProfileMetric
import com.alexisgordr.icdetector.core.profile.ProfileMode
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 3.0 — Actualización real 21 -> 22 y tablas del perfil con el SQLite de Android. */
@RunWith(AndroidJUnit4::class)
class SchemaV22UpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "schema22_upgrade_test.db"
    private var helper: CellDbHelper? = null

    @Before fun createSchema21() {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            SchemaV20UpgradeTest.V19.forEach(db::execSQL)
            SchemaV20.STATEMENTS.forEach(db::execSQL)
            SchemaV21.STATEMENTS.forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO history (timestamp, net_type, cid, mnc, tac, mcc, dbm, verified, score, " +
                    "failed_heuristics, radio, observed_at_ms, app_version) VALUES ('2026-10-01 10:00:00', " +
                    "'LTE', '100', '07', '1', '214', -90, 'PENDING', 100, 'OK', 'LTE', 1790000000000, '3.0.0-beta4')"
            )
            db.version = 21
        }
    }

    @After fun after() { helper?.close(); context.deleteDatabase(name) }

    @Test fun upgradeKeepsHistoryAndTheProfileTablesWork() {
        val h = CellDbHelper(context, context.getDatabasePath(name).absolutePath).also { helper = it }
        val db = h.writableDatabase
        assertEquals(CellDbHelper.DATABASE_VERSION, db.version)
        val row = h.getRecords().single()
        assertEquals(100, row.score)

        val p = MetricProfile("214-07-1-100-LTE", ProfileMetric.SIGNAL_DBM, RadioTech.LTE, "google/pixel 8", 1, 3,
            -90.0, 4.0, 3, 0, 3, 0, 1, "2026-10-01", 3, -90.0, 1L, 2L, 2L, ProfileMaturity.LEARNING, null, 2L)
        LocalProfileStore.upsert(db, p)
        assertEquals(p, LocalProfileStore.profiles(db, p.contextKey)[ProfileMetric.SIGNAL_DBM])

        val id = db.rawQuery("SELECT id FROM history", null).use { it.moveToFirst(); it.getLong(0) }
        LocalProfileStore.insertEvaluation(db, ProfileEvaluationRecord(id, 3L, 1, "SIGNAL_DBM:3", p.contextKey,
            ProfileMode.SHADOW, DeviationLevel.ABSTAIN, null, 0, 2, null, null, learned = true, holdout = false))
        db.rawQuery("SELECT COUNT(*) FROM ${SchemaV22.TABLE_EVALUATIONS}", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
    }

    /**
     * "Borrar historial" se lleva también el perfil y sus evaluaciones. Sobre una base completa creada
     * por la app: la base mínima de esquema 21 de arriba no tiene todas las tablas que vacía clear().
     */
    @Test fun clearHistoryAlsoClearsTheProfile() {
        val fullName = "schema22_clear_test.db"
        context.deleteDatabase(fullName)
        val h = CellDbHelper(context, context.getDatabasePath(fullName).absolutePath)
        try {
            val db = h.writableDatabase
            db.execSQL("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics) VALUES ('2026-10-01 10:00:00', '100', -90, 100, 'OK')")
            val id = db.rawQuery("SELECT id FROM history", null).use { it.moveToFirst(); it.getLong(0) }
            val p = MetricProfile("214-07-1-100-LTE", ProfileMetric.SIGNAL_DBM, RadioTech.LTE, "google/pixel 8", 1, 3,
                -90.0, 4.0, 3, 0, 3, 0, 1, "2026-10-01", 3, -90.0, 1L, 2L, 2L, ProfileMaturity.LEARNING, null, 2L)
            LocalProfileStore.upsert(db, p)
            LocalProfileStore.insertEvaluation(db, ProfileEvaluationRecord(id, 3L, 1, "SIGNAL_DBM:3", p.contextKey,
                ProfileMode.SHADOW, DeviationLevel.ABSTAIN, null, 0, 2, null, null, learned = true, holdout = false))
            LocalProfileStore.recordModeEvent(db, ProfileMode.SHADOW, "test", 1L)
            h.clear()
            assertEquals(0, LocalProfileStore.profiles(db, p.contextKey).size)
            db.rawQuery("SELECT COUNT(*) FROM ${SchemaV22.TABLE_EVALUATIONS}", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            // El registro de modos se conserva a propósito (documenta cuándo estuvo activa la opción).
            db.rawQuery("SELECT COUNT(*) FROM ${SchemaV22.TABLE_MODE_EVENTS}", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        } finally {
            h.close()
            context.deleteDatabase(fullName)
        }
    }
}
