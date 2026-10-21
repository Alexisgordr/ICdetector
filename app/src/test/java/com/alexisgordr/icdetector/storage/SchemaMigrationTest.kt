package com.alexisgordr.icdetector.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException

/**
 * 3.1.0 (#47) — Las migraciones 7-19 solo toleran «duplicate column», como las de la 20 en adelante.
 * Con SQLite real (sqlite-jdbc): una columna repetida se acepta; una migración que falla por otro
 * motivo se propaga en vez de dejar el esquema a medias.
 */
class SchemaMigrationTest {
    private fun withDb(block: (java.sql.Statement) -> Unit) =
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c -> c.createStatement().use(block) }

    @Test fun `a column that already exists is tolerated`() = withDb { st ->
        st.execute("CREATE TABLE history (id INTEGER PRIMARY KEY, rsrq INTEGER)")
        SchemaMigration.tolerateDuplicateColumn { st.execute("ALTER TABLE history ADD COLUMN rsrq INTEGER") }
        SchemaMigration.tolerateDuplicateColumn { st.execute("ALTER TABLE history ADD COLUMN sinr INTEGER") }
        val columns = st.executeQuery("PRAGMA table_info(history)").use { rs ->
            generateSequence { if (rs.next()) rs.getString("name") else null }.toList() }
        assertEquals(listOf("id", "rsrq", "sinr"), columns)
    }

    @Test fun `a migration that fails for another reason is propagated`() = withDb { st ->
        // Sin la tabla (instalación rota): antes el catch vacío seguía y el esquema quedaba a medias.
        try {
            SchemaMigration.tolerateDuplicateColumn { st.execute("ALTER TABLE history ADD COLUMN rsrq INTEGER") }
            fail("debía propagarse")
        } catch (e: SQLException) {
            assertTrue(e.message, e.message!!.contains("no such table", ignoreCase = true))
            assertFalse(SchemaMigration.isDuplicateColumn(e))
        }
        try {
            SchemaMigration.tolerateDuplicateColumn { throw IllegalStateException("disk I/O error") }
            fail("debía propagarse")
        } catch (e: IllegalStateException) {
            assertEquals("disk I/O error", e.message)
        }
    }

    @Test fun `migrations 7 to 19 no longer swallow every exception`() {
        val helper = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/storage/CellDbHelper.kt").readText()
        val upgrade = helper.substringAfter("override fun onUpgrade(").substringBefore("if (oldVersion < 20) applySchemaV20(db)")
        val legacy = upgrade.substringAfter("if (oldVersion < 7) {")
        assertFalse(legacy.contains("catch (_: Exception) {}"))
        assertEquals(13, Regex("""SchemaMigration\.tolerateDuplicateColumn \{ db\.execSQL\(""").findAll(legacy).count())
    }
}
