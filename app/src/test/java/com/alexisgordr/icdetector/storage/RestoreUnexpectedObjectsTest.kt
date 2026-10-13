package com.alexisgordr.icdetector.storage

import com.alexisgordr.icdetector.backup.BackupPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * 3.0 — Restauración: ninguna versión de la app crea triggers ni vistas, así que una copia que los
 * tenga se rechaza. Se comprueba con SQLite real la consulta de solo lectura que usa el stager.
 */
class RestoreUnexpectedObjectsTest {
    private lateinit var db: Connection
    private val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }

    @Before fun open() { db = DriverManager.getConnection("jdbc:sqlite::memory:") }
    @After fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }
    private fun unexpected(): List<String> = db.createStatement().use { st ->
        st.executeQuery(BackupPolicy.UNEXPECTED_OBJECTS_SQL).use { rs ->
            buildList { while (rs.next()) add(rs.getString(1) + ":" + rs.getString(2)) }
        }
    }

    @Test fun `every supported schema, 20 to 24, has no triggers or views`() {
        SchemaV20MigrationTest.V19_TABLES.forEach { exec(it) }
        SchemaV20.STATEMENTS.forEach { exec(it) }
        assertEquals("esquema 20", emptyList<String>(), unexpected())
        SchemaV21.STATEMENTS.forEach { exec(it) }
        assertEquals("esquema 21", emptyList<String>(), unexpected())
        SchemaV22.STATEMENTS.forEach { exec(it) }
        assertEquals("esquema 22", emptyList<String>(), unexpected())
        SchemaV23.STATEMENTS.forEach { exec(it) }
        assertEquals("esquema 23", emptyList<String>(), unexpected())
        SchemaV24.STATEMENTS.forEach { exec(it) }
        assertEquals("esquema 24", emptyList<String>(), unexpected())
    }

    @Test fun `sqlite internal objects are not reported`() {
        exec("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT UNIQUE)")   // sqlite_sequence, autoindex
        exec("CREATE TABLE android_metadata (locale TEXT)")
        exec("INSERT INTO t (code) VALUES ('a')")
        exec("CREATE INDEX idx_t ON t(code)")
        exec("ANALYZE")                                                                     // sqlite_stat1
        val names = db.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        assertTrue(names.containsAll(listOf("sqlite_sequence", "sqlite_stat1")))
        assertTrue(names.any { it.startsWith("sqlite_autoindex_t") })
        assertEquals(emptyList<String>(), unexpected())
    }

    @Test fun `triggers and views are found by a read-only query`() {
        exec("CREATE TABLE history (id INTEGER PRIMARY KEY, cid TEXT)")
        exec("CREATE TRIGGER planted AFTER INSERT ON history BEGIN DELETE FROM history; END")
        exec("CREATE VIEW planted_view AS SELECT * FROM history")
        assertEquals(listOf("trigger:planted", "view:planted_view"), unexpected().sorted())
        assertTrue(BackupPolicy.UNEXPECTED_OBJECTS_SQL.trimStart().startsWith("SELECT"))
    }

    @Test fun `the app never creates triggers or views in any schema code`() {
        main.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            listOf("CREATE TRIGGER", "CREATE TEMP TRIGGER", "CREATE VIEW", "CREATE TEMP VIEW").forEach {
                assertFalse("${file.name} crea $it", text.contains(it, ignoreCase = true) && file.name != "BackupPolicy.kt")
            }
        }
    }

    @Test fun `the stager checks before opening with the helper and again before installing`() {
        val stager = main.resolve("com/alexisgordr/icdetector/backup/RestoreStager.kt").readText()
        val stage = stager.substringAfter("fun stage(").substringBefore("fun discard(")
        val looks = stager.substringAfter("private fun looksLikeOurDatabase(")
        assertTrue("antes de abrirla con el helper", looks.contains("!AndroidDatabaseOps.hasUnexpectedObjects(db)"))
        assertTrue(looks.contains("SQLiteDatabase.OPEN_READONLY"))
        assertTrue(stage.indexOf("looksLikeOurDatabase(") < stage.indexOf("CellDbHelper(context, database.absolutePath)"))
        val after = stage.substringAfter("CellDbHelper(context, database.absolutePath)")
        assertTrue("después de migrar", after.contains("AndroidDatabaseOps.hasUnexpectedObjects(db)"))
        assertTrue(after.indexOf("hasUnexpectedObjects(db)") < after.indexOf("PRAGMA application_id"))
        assertTrue(after.indexOf("hasUnexpectedObjects(db)") < after.indexOf("engine.registerStaged"))
        val ops = main.resolve("com/alexisgordr/icdetector/backup/AndroidDatabaseOps.kt").readText()
        assertTrue(ops.contains("db.rawQuery(BackupPolicy.UNEXPECTED_OBJECTS_SQL, null)"))
    }

    @Test fun `docs explain the rejection and that checksums do not authenticate a backup`() {
        fun doc(name: String) = listOf(File(name), File("../$name")).first { it.exists() }.readText()
        val manual = doc("MANUAL.md")
        assertTrue(manual.contains("**triggers or views** is refused as damaged"))
        assertTrue(manual.contains("They do **not**\nauthenticate the backup: anyone who can edit the file can also recompute its checksum"))
        assertTrue(doc("README.md").contains("Its checksum and\nintegrity check detect damage, not tampering"))
        assertTrue(doc("CHANGELOG.md").contains("**Restore refuses databases with triggers or views.**"))
    }
}
