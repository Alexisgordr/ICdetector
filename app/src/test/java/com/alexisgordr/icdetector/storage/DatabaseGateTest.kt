package com.alexisgordr.icdetector.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

/**
 * 3.1.0 (#47) — Una base de una versión más nueva ya no tumba la app: [DatabaseGate] convierte
 * [DatabaseDowngradeException] en un estado observable, no relanza y no vuelve a intentarlo.
 */
class DatabaseGateTest {
    @Before @After fun reset() = DatabaseGate.resetForTest()

    @Test fun `a simulated downgrade publishes the state and is not rethrown`() {
        assertEquals(DatabaseGate.State.Unchecked, DatabaseGate.state.value)
        val opened = DatabaseGate.open { throw DatabaseDowngradeException(27, 26) }
        assertFalse(opened)
        assertTrue(DatabaseGate.blocked)
        assertEquals(DatabaseGate.State.NewerSchema(27, 26), DatabaseGate.state.value)
        var calls = 0
        assertFalse("bloqueada: no se vuelve a intentar", DatabaseGate.open { calls++ })
        assertEquals(0, calls)
    }

    @Test fun `a normal open is Open and other failures are not hidden`() {
        assertTrue(DatabaseGate.open { })
        assertEquals(DatabaseGate.State.Open, DatabaseGate.state.value)
        DatabaseGate.resetForTest()
        try {
            DatabaseGate.open { throw IllegalStateException("disk I/O error") }
            fail("otro fallo no se convierte en «versión más nueva»")
        } catch (e: IllegalStateException) {
            assertEquals("disk I/O error", e.message)
        }
        assertFalse(DatabaseGate.blocked)
    }

    @Test fun `a real database at version 99 does not crash and publishes the state`() {
        val dir = Files.createTempDirectory("gate-test").toFile()
        try {
            val file = File(dir, "newer.db")
            DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
                c.createStatement().use { it.execute("CREATE TABLE future_table (id INTEGER PRIMARY KEY)"); it.execute("PRAGMA user_version = 99") }
            }
            val before = file.readBytes()
            // La comprobación que hace SQLiteOpenHelper antes de onDowngrade, sobre la versión real del fichero.
            val opened = DatabaseGate.open {
                val stored = DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
                    c.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
                }
                DatabaseDowngradeException.requireSupported(stored, SchemaV26.VERSION)
            }
            assertFalse(opened)
            assertEquals(DatabaseGate.State.NewerSchema(99, SchemaV26.VERSION), DatabaseGate.state.value)
            assertTrue("no se ha modificado nada", before.contentEquals(file.readBytes()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `the dialog strings exist in both languages with the three values`() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }
        listOf("values", "values-es").forEach { dir ->
            val strings = res.resolve("$dir/strings.xml").readText()
            assertTrue("$dir: título", strings.contains("name=\"database_newer_title\""))
            val body = Regex("""<string name="database_newer_body">(.*?)</string>""").find(strings)!!.groupValues[1]
            listOf("%1\$d", "%2\$d", "%3\$s").forEach { assertTrue("$dir: $it", body.contains(it)) }
        }
        assertTrue(res.resolve("values-es/strings.xml").readText().contains("(BD v%1\$d, esta app admite hasta v%2\$d)"))
    }
}
