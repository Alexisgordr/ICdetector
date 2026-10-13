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
 * 3.0 — Migración 22 -> 23 contra un esquema 22 real: tres columnas nuevas en `local_profile`. Las
 * referencias existentes se conservan con NULL (generación `legacy`); el historial no cambia.
 */
class SchemaV23MigrationTest {
    private lateinit var db: Connection

    @Before fun openSchema22() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys = ON")
        SchemaV20MigrationTest.V19_TABLES.forEach { exec(it) }
        SchemaV20.STATEMENTS.forEach { exec(it) }
        SchemaV21.STATEMENTS.forEach { exec(it) }
        SchemaV22.STATEMENTS.forEach { exec(it) }
        exec("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics) VALUES ('2026-10-01 10:00:00', '1', -85, 100, 'OK')")
        exec("INSERT INTO local_profile (context_key, metric, radio, device_model, algorithm_version, revision, mean, " +
            "maturity, updated_at_ms) VALUES ('214-07-1-1-LTE', 'SIGNAL_DBM', 'LTE', 'google/pixel 8', 1, 80, -80.0, 'MATURE', 1)")
        SchemaV23.STATEMENTS.forEach { exec(it) }
    }

    @After fun close() = db.close()

    @Test fun `existing references and history are kept, new columns unknown`() {
        assertEquals("80", single("SELECT revision FROM local_profile"))
        assertEquals("-80.0", single("SELECT mean FROM local_profile"))
        assertNull(single("SELECT generation FROM local_profile"))
        assertNull(single("SELECT day_start_variance FROM local_profile"))
        assertNull(single("SELECT last_seen_ms FROM local_profile"))
        assertEquals("100", single("SELECT score FROM history"))
    }

    @Test fun `the migration only adds columns to local_profile and CellDbHelper runs it`() {
        assertTrue(SchemaV23.STATEMENTS.all { it.startsWith("ALTER TABLE local_profile ADD COLUMN") })
        val helper = listOf(
            File("src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt")
        ).first { it.exists() }.readText()
        // 3.0 (L2) — La versión actual es la 24; la 23 se sigue aplicando al actualizar desde la 22.
        assertTrue(helper.contains("const val DATABASE_VERSION = SchemaV25.VERSION"))
        assertTrue(helper.contains("if (oldVersion < 23) applySchemaV23(db)"))
        val v23 = helper.substringAfter("private fun applySchemaV23(db: SQLiteDatabase) {").substringBefore("SchemaV23.STATEMENTS")
        assertTrue("la tabla existe antes de alterarla", v23.contains("applySchemaV22(db)"))
        val onCreate = helper.substringAfter("override fun onCreate(db: SQLiteDatabase) {").substringBefore("override fun onUpgrade")
        assertTrue(onCreate.contains("applySchemaV23(db)"))
    }

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun single(sql: String): String? = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
    }
}
