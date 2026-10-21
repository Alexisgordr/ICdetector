package com.alexisgordr.icdetector.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * 3.1.0 (#39) — La base de la app pasa a WAL. Con SQLite real (sqlite-jdbc) se comprueba lo que la
 * copia de seguridad necesita: las filas aún en el `-wal` solo están en la copia si se copia también
 * el `-wal`, y la copia consolidada (checkpoint TRUNCATE + modo DELETE) queda en un solo fichero con
 * todas ellas. Y, sobre el código, que el helper activa WAL, que la exportación hace el checkpoint y
 * copia el `-wal`, y que la restauración mueve `-wal` y `-shm` con la base. Las pruebas
 * instrumentadas (BackupRestoreTest, WalModeTest) lo repiten con el SQLite de Android.
 */
class WalBackupCopyTest {
    private lateinit var dir: File
    private lateinit var live: Connection
    private val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        .resolve("com/alexisgordr/icdetector")

    @Before fun open() {
        dir = Files.createTempDirectory("wal-test").toFile()
        live = connect(File(dir, "live.db"))
        live.createStatement().use {
            assertEquals("wal", it.executeQuery("PRAGMA journal_mode=WAL").use { rs -> rs.next(); rs.getString(1) })
            it.execute("CREATE TABLE history (id INTEGER PRIMARY KEY, cid TEXT)")
            it.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").close()   // la tabla, en el fichero principal
            it.execute("PRAGMA wal_autocheckpoint=0")     // las filas se quedan en el -wal
            repeat(5) { i -> it.execute("INSERT INTO history (cid) VALUES ('c$i')") }
        }
        assertTrue("hay contenido sin volcar en el -wal", File(dir, "live.db-wal").length() > 0)
    }

    @After fun close() { live.close(); dir.deleteRecursively() }

    private fun connect(file: File): Connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")
    private fun count(file: File) = connect(file).use { c -> c.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM history").use { it.next(); it.getInt(1) } } }

    private fun copyLive(to: String, withWal: Boolean): File {
        val copy = File(dir, to)
        File(dir, "live.db").copyTo(copy)
        if (withWal) File(dir, "live.db-wal").copyTo(File(copy.path + "-wal"))
        return copy
    }

    @Test fun `copying only the main file loses the rows still in the WAL`() {
        assertEquals(0, count(copyLive("main-only.db", withWal = false)))
    }

    @Test fun `main file plus WAL, consolidated, is one file with every row`() {
        val copy = copyLive("copy.db", withWal = true)
        connect(copy).use { c -> c.createStatement().use { st ->
            st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { rs -> rs.next(); assertEquals(0, rs.getInt(1)); assertEquals(rs.getInt(2), rs.getInt(3)) }
            assertEquals("delete", st.executeQuery("PRAGMA journal_mode=DELETE").use { rs -> rs.next(); rs.getString(1) })
        } }
        // Sin auxiliares con contenido: el fichero solo ya es la copia completa.
        listOf("-wal", "-shm").forEach { File(copy.path + it).takeIf { f -> f.exists() }?.let { f -> assertEquals(0L, f.length()); f.delete() } }
        assertEquals(5, count(copy))
        assertFalse(File(copy.path + "-wal").exists() && File(copy.path + "-wal").length() > 0)
    }

    @Test fun `the helper enables WAL and backup and restore handle its files`() {
        val helper = main.resolve("storage/CellDbHelper.kt").readText()
        assertTrue(helper.contains("init {\n        setWriteAheadLoggingEnabled(true)\n    }"))
        val exporter = main.resolve("backup/BackupExporter.kt").readText()
        val checkpoint = exporter.indexOf("PRAGMA wal_checkpoint(PASSIVE)")
        assertTrue("checkpoint antes de la transacción de la copia", checkpoint in 0 until exporter.indexOf("db.beginTransaction()"))
        assertTrue(exporter.contains("wal.copyTo(File(copy.path + \"-wal\"), overwrite = true)"))
        assertTrue(exporter.contains("AndroidDatabaseOps.consolidate(copy)"))
        val ops = main.resolve("backup/AndroidDatabaseOps.kt").readText()
        assertTrue(ops.contains("PRAGMA wal_checkpoint(TRUNCATE)") && ops.contains("PRAGMA journal_mode=DELETE"))
        assertTrue(main.resolve("backup/RestoreEngine.kt").readText().contains("val AUX_SUFFIXES = listOf(\"-wal\", \"-shm\", \"-journal\")"))
    }
}
