package com.alexisgordr.icdetector.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * 3.0 — Migración 21 -> 22 contra un esquema 21 real: tablas nuevas del perfil local, sin tocar el
 * historial. Las evaluaciones están ligadas a una sola fila y caen con ella.
 */
class SchemaV22MigrationTest {
    private lateinit var db: Connection

    @Before fun openSchema21() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys = ON")
        SchemaV20MigrationTest.V19_TABLES.forEach { exec(it) }
        SchemaV20.STATEMENTS.forEach { exec(it) }
        SchemaV21.STATEMENTS.forEach { exec(it) }
        exec("INSERT INTO history (timestamp, cid, mnc, tac, mcc, radio, dbm, score, failed_heuristics, app_version, location_mode) " +
            "VALUES ('2026-10-01 10:00:00', '1', '07', '1', '214', 'LTE', -85, 100, 'OK', '3.0.0-beta4', 'CONTINUOUS')")
        SchemaV22.STATEMENTS.forEach { exec(it) }
    }

    @After fun close() = db.close()

    @Test fun `existing history rows and their original results are untouched`() {
        assertEquals("1", single("SELECT COUNT(*) FROM history"))
        assertEquals("-85", single("SELECT dbm FROM history"))
        assertEquals("100", single("SELECT score FROM history"))
        assertEquals("OK", single("SELECT failed_heuristics FROM history"))
        assertEquals("CONTINUOUS", single("SELECT location_mode FROM history"))
    }

    @Test fun `the migration is additive and can run twice`() {
        SchemaV22.STATEMENTS.forEach { exec(it) }
        assertTrue(SchemaV22.STATEMENTS.all { it.startsWith("CREATE TABLE IF NOT EXISTS") || it.startsWith("CREATE INDEX IF NOT EXISTS") })
        listOf("local_profile", "profile_evaluations", "profile_mode_events").forEach {
            assertEquals(it, "0", single("SELECT COUNT(*) FROM $it"))
        }
    }

    @Test fun `one evaluation per history row, removed with its row`() {
        evaluation(1)
        try { evaluation(1); fail("dos evaluaciones para la misma fila") } catch (_: SQLException) {}
        try { evaluation(999); fail("evaluación de una fila que no existe") } catch (_: SQLException) {}
        exec("DELETE FROM history")
        assertEquals("0", single("SELECT COUNT(*) FROM profile_evaluations"))
    }

    @Test fun `CellDbHelper creates and upgrades to 22, and clear and retention reach the new tables`() {
        val helper = listOf(
            File("src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt")
        ).first { it.exists() }.readText()
        assertTrue(helper.contains("const val DATABASE_VERSION = SchemaV22.VERSION"))
        assertTrue(helper.contains("if (oldVersion < 22) applySchemaV22(db)"))
        val onCreate = helper.substringAfter("override fun onCreate(db: SQLiteDatabase) {").substringBefore("override fun onUpgrade")
        assertTrue(onCreate.contains("applySchemaV22(db)"))
        val clear = helper.substringAfter("fun clear() {").substringBefore("fun pruneOldRecords(")
        assertTrue(clear.contains("DELETE FROM \${SchemaV22.TABLE_EVALUATIONS}"))
        assertTrue(clear.contains("LocalProfileStore.reset(db)"))
        val prune = helper.substringAfter("fun pruneOldRecords(").substringBefore("db.setTransactionSuccessful()")
        assertTrue(prune.contains("LocalProfileStore.pruneProfiles(db, hardSiteCutoff)"))
        assertTrue(prune.contains("history_id NOT IN (SELECT \$COLUMN_ID FROM \$TABLE_HISTORY)"))
    }

    private fun evaluation(historyId: Long) = exec(
        "INSERT INTO profile_evaluations (history_id, evaluated_at_ms, algorithm_version, context_key, mode, " +
            "deviation_level, metrics_evaluated, metrics_total) VALUES ($historyId, 1, 1, 'k', 'SHADOW', 'NONE', 1, 2)"
    )

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun single(sql: String): String? = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
    }
}
