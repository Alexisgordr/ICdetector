package com.alexisgordr.icdetector.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.TimeZone

/**
 * 3.0 — Migración 19 -> 20 contra un esquema 19 real (SQLite en memoria, sin Android).
 *
 * Las tablas de partida son una copia congelada del esquema 19 tal y como lo crea v2.10.10:
 * si alguien cambia [SchemaV20], esta prueba sigue partiendo de lo que tienen los usuarios.
 */
class SchemaV20MigrationTest {
    private lateinit var db: Connection
    private val madrid = TimeZone.getTimeZone("Europe/Madrid")

    @Before fun openSchema19() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        V19_TABLES.forEach { sql -> db.createStatement().use { it.execute(sql) } }
        exec("INSERT INTO history (timestamp, cid, mnc, tac, mcc, radio, score, failed_heuristics) " +
            "VALUES ('2026-09-01 10:00:00', '1', '07', '1', '214', 'LTE', 100, 'OK')")
        exec("INSERT INTO incidents (started_at, updated_at, identity, cid, radio, state, highest_phase, " +
            "required_phases, score, anomaly_confidence, reason, heuristic_snapshot) " +
            "VALUES ('2026-09-01 10:00:00', '2026-09-01 10:05:00', 'x', '1', 'LTE', 'RECOVERED', 1, 3, 70, 10, 'r', 's')")
        exec("INSERT INTO forensic_cases (case_code, created_at, updated_at, state, cell_identity, highest_phase) " +
            "VALUES ('ICD-2026-09-01-0001', '2026-09-01 10:00:00', '2026-09-01 10:05:00', 'CLOSED', 'x', 1)")
        SchemaV20.STATEMENTS.forEach { exec(it) }
    }

    @After fun close() = db.close()

    @Test fun `old rows keep every new column empty, meaning unknown`() {
        assertNull(single("SELECT observed_at_ms FROM history"))
        assertNull(single("SELECT started_at_ms FROM incidents"))
        assertNull(single("SELECT updated_at_ms FROM incidents"))
        assertNull(single("SELECT created_at_ms FROM forensic_cases"))
        assertNull(single("SELECT updated_at_ms FROM forensic_cases"))
        assertNull(single("SELECT not_evaluated_heuristics FROM history"))
        assertNull(single("SELECT gps_accuracy_m FROM history"))
        assertNull(single("SELECT app_version FROM history"))
        // Y nada de lo que ya había cambia.
        assertEquals("2026-09-01 10:00:00", single("SELECT timestamp FROM history"))
    }

    @Test fun `a 30-day window keeps legacy rows by their text and new rows by their instant`() {
        val now = 1_791_540_000_000L                        // 2026-10-09T10:00:00Z
        insertNew("2026-10-09 12:00:00", now)
        insertNew("2026-08-01 12:00:00", now - 69L * 86_400_000) // nueva pero fuera de ventana
        val cutoff = now - 40L * 86_400_000                   // incluye la fila antigua del 1-9
        val texts = query(
            "SELECT timestamp FROM history WHERE ${ObservationTime.sinceClause("observed_at_ms", "timestamp")} ORDER BY id",
            ObservationTime.cutoffArgs(cutoff, madrid)
        )
        assertEquals(listOf("2026-09-01 10:00:00", "2026-10-09 12:00:00"), texts)
    }

    @Test fun `rows in the repeated autumn hour are ordered by their instant, not by insertion`() {
        // Se insertan al revés para que el id no pueda ordenar por casualidad.
        insertNew("2026-10-25 02:30:00", 1_792_891_800_000L, cid = "second")
        insertNew("2026-10-25 02:30:00", 1_792_888_200_000L, cid = "first")
        val order = query(
            "SELECT cid FROM history ORDER BY " +
                ObservationTime.orderAscending("observed_at_ms", "timestamp", "id"),
            emptyArray()
        )
        assertEquals(listOf("1", "first", "second"), order)
    }

    @Test fun `retention removes old rows of both kinds and keeps recent ones`() {
        val now = 1_791_540_000_000L
        insertNew("2026-10-09 12:00:00", now)
        val cutoff = now - 7L * 86_400_000                    // borra el 1-9 (antigua) y nada más
        val args = ObservationTime.cutoffArgs(cutoff, madrid)
        val sql = "DELETE FROM history WHERE ${ObservationTime.beforeClause("observed_at_ms", "timestamp")}"
        db.prepareStatement(sql).use { st -> args.forEachIndexed { i, a -> st.setString(i + 1, a) }; st.executeUpdate() }
        assertEquals(listOf("2026-10-09 12:00:00"), query("SELECT timestamp FROM history", emptyArray()))
    }

    @Test fun `the latest row is the last observed one, not the last written one (#25)`() {
        // La fila 3 se escribe después pero se observó antes (otra cola, un reintento...).
        insertNew("2026-10-09 12:00:02", 1_791_540_002_000L, cid = "1")
        insertNew("2026-10-09 12:00:01", 1_791_540_001_000L, cid = "1")
        val latest = query(
            "SELECT timestamp FROM history WHERE cid='1' ORDER BY " +
                ObservationTime.orderDescending("observed_at_ms", "id") + " LIMIT 1",
            emptyArray()
        )
        assertEquals(listOf("2026-10-09 12:00:02"), latest)
        // Y la fila anterior a 3.0, sin instante, queda la última.
        val all = query(
            "SELECT timestamp FROM history ORDER BY " + ObservationTime.orderDescending("observed_at_ms", "id"),
            emptyArray()
        )
        assertEquals("2026-09-01 10:00:00", all.last())
    }

    @Test fun `CellDbHelper never uses the row id as the observation order`() {
        val helper = listOf(
            File("src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt")
        ).first { it.exists() }.readText()
        assertTrue(!helper.contains("MAX(\$COLUMN_ID)"))
        assertTrue(!helper.contains("ORDER BY \$COLUMN_ID DESC"))
        assertTrue(helper.contains("NEWEST_FIRST = \"\$COLUMN_OBSERVED_AT_MS DESC, \$COLUMN_ID DESC\""))
    }

    @Test fun `table names match the ones used by CellDbHelper`() {
        val helper = listOf(
            File("src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt")
        ).first { it.exists() }.readText()
        assertTrue(helper.contains("const val TABLE_HISTORY = \"${SchemaV20.TABLE_HISTORY}\""))
        assertTrue(helper.contains("const val TABLE_INCIDENTS = \"${SchemaV20.TABLE_INCIDENTS}\""))
        assertTrue(helper.contains("const val TABLE_FORENSIC_CASES = \"${SchemaV20.TABLE_FORENSIC_CASES}\""))
        assertTrue(helper.contains("if (oldVersion < 20) applySchemaV20(db)"))
    }

    /**
     * Bug found during testing: una base de datos sin `incidents` o sin `forensic_cases`
     * (instalación parcial; así son también las bases de datos de los tests instrumentados de
     * migraciones antiguas) hacía fallar el ALTER con "no such table" y la app no abría. La
     * migración crea antes, vacías, las tablas que toca.
     */
    @Test fun `the migration creates every table it alters before altering it`() {
        val altered = SchemaV20.STATEMENTS.mapNotNull {
            Regex("^ALTER TABLE (\\w+) ").find(it)?.groupValues?.get(1)
        }.toSet()
        assertEquals(
            setOf(SchemaV20.TABLE_HISTORY, SchemaV20.TABLE_INCIDENTS, SchemaV20.TABLE_FORENSIC_CASES),
            altered
        )
        val helper = listOf(
            File("src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt")
        ).first { it.exists() }.readText()
        val body = helper.substringAfter("private fun applySchemaV20(db: SQLiteDatabase) {")
            .substringBefore("SchemaV20.STATEMENTS")
        listOf("createHistoryTable(db)", "createIncidentTable(db)", "createForensicTables(db)")
            .forEach { assertTrue(it, body.contains(it)) }
        listOf(
            "CREATE TABLE IF NOT EXISTS \$TABLE_HISTORY (",
            "CREATE TABLE IF NOT EXISTS \$TABLE_INCIDENTS (",
            "CREATE TABLE IF NOT EXISTS \$TABLE_FORENSIC_CASES ("
        ).forEach { assertTrue(it, helper.contains(it)) }
    }

    @Test fun `without the tables the bare statements fail, which is the bug being fixed`() {
        val partial = DriverManager.getConnection("jdbc:sqlite::memory:")
        partial.use { c ->
            c.createStatement().use { it.execute("CREATE TABLE history (id INTEGER PRIMARY KEY, cid TEXT)") }
            val error = runCatching {
                SchemaV20.STATEMENTS.forEach { sql -> c.createStatement().use { it.execute(sql) } }
            }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("no such table"))
        }
    }

    private fun insertNew(text: String, ms: Long, cid: String = "1") = db.prepareStatement(
        "INSERT INTO history (timestamp, observed_at_ms, cid, mnc, tac, mcc, radio, score, failed_heuristics) " +
            "VALUES (?, ?, ?, '07', '1', '214', 'LTE', 100, 'OK')"
    ).use { it.setString(1, text); it.setLong(2, ms); it.setString(3, cid); it.executeUpdate() }

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun single(sql: String): String? = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) }
    }

    private fun query(sql: String, args: Array<String>): List<String> = db.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setString(i + 1, a) }
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    companion object {
        /** Esquema 19 congelado (v2.10.10): historial, incidentes y casos forenses. */
        val V19_TABLES = listOf(
            "CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT, net_type TEXT, " +
                "cid TEXT, mnc TEXT, tac TEXT, mcc TEXT, dbm INTEGER, verified TEXT, score INTEGER DEFAULT 100, " +
                "failed_heuristics TEXT, lat REAL, lon REAL, pci INTEGER, arfcn INTEGER, rsrq INTEGER, sinr INTEGER, " +
                "threat_prob REAL DEFAULT 0, api_lat REAL, api_lon REAL, ta INTEGER, ta_unit TEXT, radio TEXT, " +
                "conn_status TEXT, bandwidth_khz INTEGER, bands TEXT, additional_plmns TEXT, csg_indicator INTEGER, " +
                "csg_identity INTEGER, csg_name TEXT, secondary_carriers TEXT, service_state TEXT, " +
                "network_operator TEXT, sim_operator TEXT, network_roaming INTEGER)",
            "CREATE INDEX idx_timestamp ON history (timestamp)",
            "CREATE TABLE incidents (id INTEGER PRIMARY KEY AUTOINCREMENT, started_at TEXT NOT NULL, " +
                "updated_at TEXT NOT NULL, ended_at TEXT, identity TEXT NOT NULL, cid TEXT NOT NULL, " +
                "radio TEXT NOT NULL, state TEXT NOT NULL, highest_phase INTEGER NOT NULL, " +
                "required_phases INTEGER NOT NULL, score INTEGER NOT NULL, anomaly_confidence REAL NOT NULL, " +
                "reason TEXT NOT NULL, heuristic_snapshot TEXT NOT NULL)",
            "CREATE TABLE forensic_cases (id INTEGER PRIMARY KEY AUTOINCREMENT, case_code TEXT NOT NULL, " +
                "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, closed_at TEXT, state TEXT NOT NULL, " +
                "cell_identity TEXT NOT NULL, highest_phase INTEGER NOT NULL, confirmed INTEGER NOT NULL DEFAULT 0)",
        )
    }
}
