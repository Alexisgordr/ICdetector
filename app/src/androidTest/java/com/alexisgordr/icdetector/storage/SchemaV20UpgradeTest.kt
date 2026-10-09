package com.alexisgordr.icdetector.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 3.0 — Actualización real 19 -> 20 con el SQLite de Android: se crea una base de datos con el
 * esquema 19 de v2.10.10, se abre con [CellDbHelper] y se comprueba que las filas antiguas
 * siguen ahí con las columnas nuevas vacías (desconocido) y que las nuevas se escriben completas.
 */
@RunWith(AndroidJUnit4::class)
class SchemaV20UpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var helper: CellDbHelper? = null

    @Before fun createSchema19() {
        context.deleteDatabase(NAME)
        val file = context.getDatabasePath(NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            V19.forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO history (timestamp, net_type, cid, mnc, tac, mcc, dbm, verified, score, " +
                    "failed_heuristics, radio) VALUES ('2026-09-01 10:00:00', 'LTE', '100', '07', '1', " +
                    "'214', -90, 'PENDING', 100, 'OK', 'LTE')"
            )
            db.version = 19
        }
    }

    @After fun after() { helper?.close(); context.deleteDatabase(NAME) }

    @Test fun upgradeKeepsOldRowsAsUnknownAndWritesNewRowsCompletely() {
        val db = CellDbHelper(context).also { helper = it }
        assertEquals(SchemaV20.VERSION, db.readableDatabase.version)

        val old = db.getRecords().single()
        assertEquals("2026-09-01 10:00:00", old.timestamp)
        assertNull(old.observedAtMs)
        assertNull(old.notEvaluatedHeuristics)
        assertNull(old.gpsAccuracyM)
        assertNull(old.appVersion)

        val now = System.currentTimeMillis()
        db.logConnection(
            observedAtMs = now, netType = "LTE", cid = "100", mnc = "07", tac = "1", mcc = "214",
            dbm = -90, radio = RadioTech.LTE, lat = 40.4, lon = -3.7,
            notEvaluatedHeuristics = "H1;H9", gpsAccuracyM = 8f, appVersion = "3.0.0-beta1"
        )
        val latest = db.getRecords().first()
        assertEquals(now, latest.observedAtMs)
        assertEquals("H1;H9", latest.notEvaluatedHeuristics)
        assertEquals(8f, latest.gpsAccuracyM!!, 0.01f)
        assertEquals("3.0.0-beta1", latest.appVersion)
    }

    /**
     * Bug found during testing: una base de datos antigua sin `incidents` ni `forensic_cases`
     * no abría ("no such table: incidents"). Ahora las recibe vacías y conserva sus filas.
     */
    @Test fun upgradeFromPartialSchemaCreatesMissingTablesAndKeepsRows() {
        helper?.close(); context.deleteDatabase(NAME)
        val file = context.getDatabasePath(NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY, cid TEXT)")
            db.execSQL("INSERT INTO history VALUES (1, 'kept')")
            db.version = 18
        }
        val db = CellDbHelper(context).also { helper = it }.writableDatabase
        assertEquals(SchemaV20.VERSION, db.version)
        db.rawQuery("SELECT cid, observed_at_ms FROM history", null).use { c ->
            c.moveToFirst()
            assertEquals("kept", c.getString(0))
            assertTrue(c.isNull(1))
        }
        fun columns(table: String) = db.rawQuery("PRAGMA table_info($table)", null)
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
        assertTrue(columns("incidents").containsAll(listOf("started_at_ms", "updated_at_ms")))
        assertTrue(columns("forensic_cases").containsAll(listOf("created_at_ms", "updated_at_ms")))
        db.rawQuery("SELECT COUNT(*) FROM incidents", null).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }

    private companion object {
        const val NAME = "icdetector_history.db"

        /** Esquema 19 (v2.10.10) de las tablas que toca la migración, más las que se leen al abrir. */
        val V19 = listOf(
            "CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT, net_type TEXT, " +
                "cid TEXT, mnc TEXT, tac TEXT, mcc TEXT, dbm INTEGER, verified TEXT, score INTEGER DEFAULT 100, " +
                "failed_heuristics TEXT, lat REAL, lon REAL, pci INTEGER, arfcn INTEGER, rsrq INTEGER, sinr INTEGER, " +
                "threat_prob REAL DEFAULT 0, api_lat REAL, api_lon REAL, ta INTEGER, ta_unit TEXT, radio TEXT, " +
                "conn_status TEXT, bandwidth_khz INTEGER, bands TEXT, additional_plmns TEXT, csg_indicator INTEGER, " +
                "csg_identity INTEGER, csg_name TEXT, secondary_carriers TEXT, service_state TEXT, " +
                "network_operator TEXT, sim_operator TEXT, network_roaming INTEGER)",
            "CREATE TABLE incidents (id INTEGER PRIMARY KEY AUTOINCREMENT, started_at TEXT NOT NULL, " +
                "updated_at TEXT NOT NULL, ended_at TEXT, identity TEXT NOT NULL, cid TEXT NOT NULL, " +
                "radio TEXT NOT NULL, state TEXT NOT NULL, highest_phase INTEGER NOT NULL, " +
                "required_phases INTEGER NOT NULL, score INTEGER NOT NULL, anomaly_confidence REAL NOT NULL, " +
                "reason TEXT NOT NULL, heuristic_snapshot TEXT NOT NULL)",
            "CREATE TABLE forensic_cases (id INTEGER PRIMARY KEY AUTOINCREMENT, case_code TEXT NOT NULL, " +
                "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, closed_at TEXT, state TEXT NOT NULL, " +
                "cell_identity TEXT NOT NULL, highest_phase INTEGER NOT NULL, confirmed INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE forensic_samples (id INTEGER PRIMARY KEY AUTOINCREMENT, case_id INTEGER NOT NULL, " +
                "wall_time_ms INTEGER NOT NULL, elapsed_time_ms INTEGER NOT NULL, event TEXT NOT NULL, " +
                "payload_json TEXT NOT NULL, FOREIGN KEY(case_id) REFERENCES forensic_cases(id) ON DELETE CASCADE)",
        )
    }
}
