package com.alexisgordr.icdetector.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.1.0 (#47) — Abrir una base de una versión más nueva falla con un mensaje claro, no con la
 * SQLiteException genérica. La apertura real con el SQLite de Android está en androidTest
 * (DatabaseDowngradeInstrumentedTest).
 */
class DatabaseDowngradeTest {
    @Test fun `it carries both versions and a log-only message`() {
        val e = DatabaseDowngradeException(27, 26)
        assertEquals("hereda de IllegalStateException, no de SQLiteException", IllegalStateException::class.java, e.javaClass.superclass)
        assertFalse("no es una SQLiteException", e.javaClass.name.contains("SQLite"))
        assertEquals(27, e.storedVersion)
        assertEquals(26, e.supportedVersion)
        // El texto para la persona va en el diálogo localizado (DatabaseGate); este es para el log.
        assertEquals("Database schema 27 is newer than the supported 26; nothing was changed.", e.message)
        DatabaseDowngradeException.requireSupported(26, 26)
        try {
            DatabaseDowngradeException.requireSupported(27, 26)
            org.junit.Assert.fail()
        } catch (expected: DatabaseDowngradeException) {
            assertEquals(27, expected.storedVersion)
        }
    }

    @Test fun `the helper overrides onDowngrade with it`() {
        val helper = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/storage/CellDbHelper.kt").readText()
        assertTrue(helper.contains("override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {\n" +
            "        throw DatabaseDowngradeException(oldVersion, newVersion)\n    }"))
        assertTrue(helper.contains("DatabaseGate.open { writableDatabase }"))
    }
}
