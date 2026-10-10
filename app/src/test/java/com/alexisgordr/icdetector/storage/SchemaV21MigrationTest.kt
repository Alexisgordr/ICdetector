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
 * 3.0 — Migración 20 -> 21 contra un esquema 20 real (el 19 de v2.10.10 más [SchemaV20]): una
 * columna con el modo de ubicación. Las filas existentes quedan con NULL (desconocido).
 */
class SchemaV21MigrationTest {
    private lateinit var db: Connection

    @Before fun openSchema20() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        SchemaV20MigrationTest.V19_TABLES.forEach { exec(it) }
        SchemaV20.STATEMENTS.forEach { exec(it) }
        exec("INSERT INTO history (timestamp, cid, mnc, tac, mcc, radio, score, failed_heuristics, app_version) " +
            "VALUES ('2026-10-01 10:00:00', '1', '07', '1', '214', 'LTE', 100, 'OK', '3.0.0-beta4')")
        SchemaV21.STATEMENTS.forEach { exec(it) }
    }

    @After fun close() = db.close()

    @Test fun `existing rows keep an unknown location mode and nothing else changes`() {
        assertNull(single("SELECT location_mode FROM history"))
        assertEquals("3.0.0-beta4", single("SELECT app_version FROM history"))
        assertEquals("2026-10-01 10:00:00", single("SELECT timestamp FROM history"))
    }

    @Test fun `new rows store the mode they were observed with`() {
        exec("INSERT INTO history (timestamp, cid, location_mode) VALUES ('2026-10-10 10:00:00', '2', 'ADAPTIVE')")
        assertEquals("ADAPTIVE", single("SELECT location_mode FROM history WHERE cid = '2'"))
    }

    @Test fun `the migration only alters history and CellDbHelper runs it`() {
        assertEquals(listOf("ALTER TABLE history ADD COLUMN location_mode TEXT"), SchemaV21.STATEMENTS)
        val helper = listOf(
            File("src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt")
        ).first { it.exists() }.readText()
        assertTrue(helper.contains("private const val DATABASE_VERSION = SchemaV21.VERSION"))
        assertTrue(helper.contains("if (oldVersion < 21) applySchemaV21(db)"))
        val v21 = helper.substringAfter("private fun applySchemaV21(db: SQLiteDatabase) {")
            .substringBefore("SchemaV21.STATEMENTS")
        assertTrue("la tabla se crea antes de alterarla", v21.contains("createHistoryTable(db)"))
        val onCreate = helper.substringAfter("override fun onCreate(db: SQLiteDatabase) {")
            .substringBefore("override fun onUpgrade")
        assertTrue(onCreate.contains("applySchemaV21(db)"))
    }

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun single(sql: String): String? = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) }
    }
}
