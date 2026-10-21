package com.alexisgordr.icdetector.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * 3.0 (L2) — Migración 23 -> 24 contra un esquema 23 real: identidad de la observación en `history`
 * y en `profile_evaluations`. Lo existente se conserva (NULL); una observación solo puede tener una
 * evaluación; las evaluaciones antiguas, sin identidad, no chocan entre sí.
 */
class SchemaV24MigrationTest {
    private lateinit var db: Connection

    @Before fun openSchema23() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys = ON")
        SchemaV20MigrationTest.V19_TABLES.forEach { exec(it) }
        listOf(SchemaV20.STATEMENTS, SchemaV21.STATEMENTS, SchemaV22.STATEMENTS, SchemaV23.STATEMENTS).forEach { it.forEach(::exec) }
        // Datos de un esquema 23: dos filas de la misma lectura (periódica y alarma), evaluadas dos veces.
        repeat(2) { exec("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics, observed_at_ms) VALUES ('2026-10-01 10:00:00', '1', -85, 100, 'OK', 5000)") }
        repeat(2) { i -> exec("INSERT INTO profile_evaluations (history_id, evaluated_at_ms, algorithm_version, context_key, mode, " +
            "deviation_level, metrics_evaluated, metrics_total) VALUES (${i + 1}, 1, 2, 'k', 'SHADOW', 'NONE', 1, 2)") }
        SchemaV24.STATEMENTS.forEach(::exec)
    }

    @After fun close() = db.close()

    private fun evaluation(historyId: Int, observationId: String?) =
        "INSERT INTO profile_evaluations (history_id, evaluated_at_ms, algorithm_version, context_key, mode, deviation_level, " +
            "metrics_evaluated, metrics_total, observation_id) VALUES ($historyId, 1, 2, 'k', 'SHADOW', 'NONE', 1, 2, " +
            (observationId?.let { "'$it'" } ?: "NULL") + ")"

    @Test fun `existing rows and evaluations are kept, not rewritten`() {
        assertEquals("2", single("SELECT COUNT(*) FROM history"))
        assertEquals("las dos evaluaciones antiguas siguen", "2", single("SELECT COUNT(*) FROM profile_evaluations"))
        assertNull(single("SELECT observation_id FROM history LIMIT 1"))
        assertNull(single("SELECT observation_id FROM profile_evaluations LIMIT 1"))
    }

    @Test fun `one evaluation per observation, enforced by the database`() {
        exec("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics, observed_at_ms, observation_id) VALUES ('2026-10-02 10:00:00', '1', -80, 100, 'OK', 9000, 'obs-1')")
        exec("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics, observed_at_ms, observation_id) VALUES ('2026-10-02 10:00:00', '1', -80, 100, 'H3', 9000, 'obs-1')")
        exec(evaluation(3, "obs-1"))
        try { exec(evaluation(4, "obs-1")); fail("una segunda evaluación de la misma observación debe rechazarse") } catch (_: SQLException) { }
        assertEquals("1", single("SELECT COUNT(*) FROM profile_evaluations WHERE observation_id='obs-1'"))
        assertEquals("las dos filas se conservan", "2", single("SELECT COUNT(*) FROM history WHERE observation_id='obs-1'"))
        // La lista muestra cuántas filas comparten la evaluación (1 en las antiguas).
        val (sql, args) = com.alexisgordr.icdetector.core.profile.ProfileEvaluationQuery.pageSql(
            com.alexisgordr.icdetector.core.profile.ProfileEvaluationQuery.LevelFilter.ALL, null)
        val rows = db.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setString(i + 1, a) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong("history_id") to rs.getInt("observation_rows")) } }
        }
        assertEquals(mapOf(1L to 1, 2L to 1, 3L to 2), rows.toMap())
    }

    @Test fun `the migration is additive and CellDbHelper runs it on create and upgrade`() {
        assertTrue(SchemaV24.STATEMENTS.all { it.startsWith("ALTER TABLE") || it.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS") || it.startsWith("CREATE INDEX IF NOT EXISTS") })
        val helper = listOf(File("src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt")).first { it.exists() }.readText()
        assertTrue(helper.contains("const val DATABASE_VERSION = SchemaV26.VERSION"))
        assertTrue(helper.contains("if (oldVersion < 24) applySchemaV24(db)"))
        assertTrue(helper.substringAfter("private fun applySchemaV24(db: SQLiteDatabase) {").substringBefore("SchemaV24.STATEMENTS").contains("applySchemaV23(db)"))
        assertTrue(helper.substringAfter("override fun onCreate(db: SQLiteDatabase) {").substringBefore("override fun onUpgrade").contains("applySchemaV24(db)"))
    }

    private fun exec(sql: String) { db.createStatement().use { it.execute(sql) } }
    private fun single(sql: String): String? = db.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
}
