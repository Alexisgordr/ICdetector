package com.alexisgordr.icdetector.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 3.0 (L2) — Actualización real 23 -> 24 con el SQLite de Android: historial y evaluaciones intactos. */
@RunWith(AndroidJUnit4::class)
class SchemaV24UpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "schema24_upgrade_test.db"
    private var helper: CellDbHelper? = null

    @Before fun createSchema23() {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            SchemaV20UpgradeTest.V19.forEach(db::execSQL)
            listOf(SchemaV20.STATEMENTS, SchemaV21.STATEMENTS, SchemaV22.STATEMENTS, SchemaV23.STATEMENTS).forEach { it.forEach(db::execSQL) }
            repeat(2) { db.execSQL("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics, observed_at_ms) VALUES ('2026-10-01 10:00:00', '1', -85, 100, 'OK', 5000)") }
            repeat(2) { i -> db.execSQL("INSERT INTO profile_evaluations (history_id, evaluated_at_ms, algorithm_version, context_key, mode, " +
                "deviation_level, metrics_evaluated, metrics_total) VALUES (${i + 1}, 1, 2, 'k', 'SHADOW', 'NONE', 1, 2)") }
            db.version = 23
        }
    }

    @After fun after() { helper?.close(); context.deleteDatabase(name) }

    @Test fun upgradeKeepsHistoryAndEvaluations() {
        val h = CellDbHelper(context, context.getDatabasePath(name).absolutePath).also { helper = it }
        val db = h.writableDatabase
        assertEquals(CellDbHelper.DATABASE_VERSION, db.version)
        fun count(sql: String) = db.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(2, count("SELECT COUNT(*) FROM history WHERE observation_id IS NULL"))
        assertEquals("evaluaciones antiguas sin reescribir", 2, count("SELECT COUNT(*) FROM profile_evaluations WHERE observation_id IS NULL"))
        assertEquals(1, count("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_profile_eval_observation'"))
    }
}
