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
    @Test fun `the message names both versions and what to do`() {
        val e = DatabaseDowngradeException(27, 26)
        assertTrue(e is IllegalStateException)
        assertFalse("no es una SQLiteException", e.javaClass.name.contains("SQLite"))
        assertEquals(27, e.storedVersion)
        assertEquals(26, e.supportedVersion)
        val message = e.message!!
        assertTrue(message.contains("esquema 27") && message.contains("usa el 26"))
        assertTrue(message.contains("No se ha modificado nada"))
        assertTrue(message.contains("restaura una copia de seguridad"))
    }

    @Test fun `the helper overrides onDowngrade with it`() {
        val helper = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/storage/CellDbHelper.kt").readText()
        assertTrue(helper.contains("override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {\n" +
            "        throw DatabaseDowngradeException(oldVersion, newVersion)\n    }"))
    }
}
