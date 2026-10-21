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

/**
 * 3.1.0 — Migración 25 -> 26 contra un esquema 25 real: tres columnas nuevas en `history` para el
 * churn de celdas (H17, en sombra). Solo añade columnas; las filas anteriores quedan con NULL.
 */
class SchemaV26MigrationTest {
    private lateinit var db: Connection

    @Before fun openSchema25() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys = ON")
        SchemaV20MigrationTest.V19_TABLES.forEach { exec(it) }
        listOf(SchemaV20.STATEMENTS, SchemaV21.STATEMENTS, SchemaV22.STATEMENTS, SchemaV23.STATEMENTS, SchemaV24.STATEMENTS)
            .forEach { it.forEach(::exec) }
        exec(SchemaV25MigrationTest.V24_TRANSITIONS)
        SchemaV25.COLUMNS.forEach(::exec)
        repeat(3) { i ->
            exec("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics, observed_at_ms, observation_id) " +
                "VALUES ('2026-10-01 10:00:0$i', '1', -85, 100, 'OK', ${5000 + i}, 'obs-$i')")
        }
        SchemaV26.STATEMENTS.forEach(::exec)
    }

    @After fun close() = db.close()

    @Test fun `existing rows are kept with NULL churn`() {
        assertEquals("3", single("SELECT COUNT(*) FROM history"))
        assertEquals("3", single("SELECT COUNT(*) FROM history WHERE churn_cells_15m IS NULL AND churn_changes_15m IS NULL AND churn_motion IS NULL"))
        assertEquals("nada se reescribe", "OK", single("SELECT failed_heuristics FROM history WHERE observation_id='obs-2'"))
    }

    @Test fun `new rows store the three values`() {
        exec("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics, observed_at_ms, churn_cells_15m, churn_changes_15m, churn_motion) " +
            "VALUES ('2026-10-21 10:00:00', '2', -80, 100, 'OK', 9000, 4, 6, 'MOVING')")
        assertEquals("4|6|MOVING", single("SELECT churn_cells_15m || '|' || churn_changes_15m || '|' || churn_motion FROM history WHERE cid='2'"))
        assertNull(single("SELECT churn_motion FROM history WHERE cid='1' LIMIT 1"))
    }

    @Test fun `the migration only adds columns and CellDbHelper runs it on create and upgrade`() {
        assertTrue(SchemaV26.STATEMENTS.all { it.startsWith("ALTER TABLE history ADD COLUMN") })
        val helper = listOf(File("src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt")).first { it.exists() }.readText()
        assertTrue(helper.contains("const val DATABASE_VERSION = SchemaV26.VERSION"))
        assertTrue(helper.contains("if (oldVersion < 26) applySchemaV26(db)"))
        assertTrue(helper.substringAfter("private fun applySchemaV26(db: SQLiteDatabase) {").substringBefore("SchemaV26.STATEMENTS").contains("applySchemaV25(db)"))
        assertTrue(helper.substringAfter("override fun onCreate(db: SQLiteDatabase) {").substringBefore("override fun onUpgrade").contains("applySchemaV26(db)"))
        // Se escribe y se lee: ni la fila ni el export pierden el dato.
        assertTrue(helper.contains("put(COLUMN_CHURN_CELLS, churn.distinctCells15m)"))
        assertTrue(helper.contains("churnMotion = optString(cursor, COLUMN_CHURN_MOTION)"))
    }

    private fun exec(sql: String) { db.createStatement().use { it.execute(sql) } }
    private fun single(sql: String): String? = db.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
}
