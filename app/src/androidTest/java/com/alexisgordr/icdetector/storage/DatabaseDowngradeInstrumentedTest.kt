package com.alexisgordr.icdetector.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 3.1.0 (#47) — Una base de un esquema más nuevo no se abre ni se modifica: mensaje claro. */
@RunWith(AndroidJUnit4::class)
class DatabaseDowngradeInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.filesDir, "downgrade-test/newer.db")

    @After fun tearDown() { File(context.filesDir, "downgrade-test").deleteRecursively() }

    @Test fun openingANewerSchemaFailsWithAClearMessageAndChangesNothing() {
        file.parentFile!!.mkdirs()
        val newer = CellDbHelper.DATABASE_VERSION + 1
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE future_table (id INTEGER PRIMARY KEY)")
            db.version = newer
        }
        val helper = CellDbHelper(context, file.absolutePath)
        try {
            helper.writableDatabase
            fail("debía fallar")
        } catch (e: DatabaseDowngradeException) {
            assertEquals(newer, e.storedVersion)
            assertEquals(CellDbHelper.DATABASE_VERSION, e.supportedVersion)
        } finally {
            helper.close()
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(newer, db.version)
            db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'future_table'", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }
}
