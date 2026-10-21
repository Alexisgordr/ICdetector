package com.alexisgordr.icdetector.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.backup.BackupExporter
import com.alexisgordr.icdetector.backup.RestoreEngine
import com.alexisgordr.icdetector.backup.AndroidDatabaseOps
import com.alexisgordr.icdetector.backup.RestoreStager
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/**
 * 3.1.0 (#39) — Con el SQLite de Android: el helper abre la base en WAL sin pedirlo; una copia de
 * seguridad hecha con filas aún en el `-wal` las incluye y sale en un solo fichero en modo DELETE; y
 * la restauración sobre una base en WAL (con `-wal` y `-shm` presentes) deja la de la copia, sin
 * que el `-wal` antiguo se mezcle con ella.
 *
 * 3.1.0 (remate) — Con un lector que mantiene abierta una foto (transacción de lectura), el
 * checkpoint PASIVO del exportador no puede volcar ni vaciar el `-wal` más allá de esa foto: las
 * filas escritas después solo están en el `-wal`, y la copia las tiene porque copia el `-wal`.
 */
@RunWith(AndroidJUnit4::class)
class WalModeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.filesDir, "wal-test/databases")
    private val source = File(dir, "source.db")
    private val target = File(dir, "target.db")
    private val helpers = mutableListOf<CellDbHelper>()

    @Before fun setUp() { dir.deleteRecursively(); dir.mkdirs() }
    @After fun tearDown() { helpers.forEach { it.close() }; File(context.filesDir, "wal-test").deleteRecursively() }

    private fun helper(file: File) = CellDbHelper(context, file.absolutePath).also { helpers += it }

    private fun addRows(h: CellDbHelper, cid: String, count: Int) = repeat(count) {
        h.logConnection(observedAtMs = 1_790_000_000_000L + it, netType = "LTE", cid = cid, mnc = "07", tac = "1",
            mcc = "214", dbm = -90, radio = RadioTech.LTE)
    }

    private fun journalMode(h: CellDbHelper) =
        h.readableDatabase.rawQuery("PRAGMA journal_mode", null).use { it.moveToFirst(); it.getString(0) }

    @Test fun theHelperOpensTheDatabaseInWal() {
        val h = helper(source)
        assertEquals("wal", journalMode(h).lowercase())
        assertTrue(h.readableDatabase.isWriteAheadLoggingEnabled)
    }

    @Test fun backupOfAWalDatabaseHasEveryRowAndRestoreReplacesAWalTarget() {
        val src = helper(source)
        src.writableDatabase.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
        addRows(src, "111", 5)
        assertTrue("filas aún en el -wal", File(source.path + "-wal").length() > 0)
        val out = ByteArrayOutputStream()
        val exported = BackupExporter.export(context, src, source, "3.1.0+test", out, Instant.EPOCH)
        assertTrue(exported.toString(), exported is BackupExporter.Result.Ok)

        // Destino en WAL con su -wal y -shm presentes.
        val tgt = helper(target)
        tgt.writableDatabase.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
        addRows(tgt, "999", 2)
        tgt.close()

        val stager = RestoreStager(context, dir, target.name)
        assertTrue(stager.stage(ByteArrayInputStream(out.toByteArray())) is RestoreStager.Result.Staged)
        stager.engine.requestInstall()
        assertEquals(RestoreEngine.RestoreOutcome.RESTORED, RestoreEngine(dir, target.name, AndroidDatabaseOps).recover().outcome)

        val restored = helper(target)
        assertEquals(5, restored.getRecords().size)
        assertTrue(restored.getRecords().all { it.cid == "111" })
        assertEquals("vuelve a WAL al abrirla", "wal", journalMode(restored).lowercase())
    }

    @Test fun backupCopiesTheWalThatAnOpenReaderKeepsFromTheCheckpoint() {
        // beginTransactionReadOnly (BEGIN DEFERRED) existe desde Android 15; la CI usa API 35.
        assumeTrue("necesita API 35", Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) pinnedReaderBackup()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun pinnedReaderBackup() {
        val src = helper(source)
        src.writableDatabase.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
        addRows(src, "111", 3)

        // Lector con su propia conexión y una foto abierta: la primera lectura fija la foto.
        val reader = SQLiteDatabase.openDatabase(source.path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
        fun readerCount() = reader.rawQuery("SELECT COUNT(*) FROM history", null).use { it.moveToFirst(); it.getInt(0) }
        reader.beginTransactionReadOnly()
        try {
            assertEquals(3, readerCount())
            addRows(src, "222", 4)                          // después de abrir la foto
            assertEquals("el lector sigue en su foto", 3, readerCount())

            val out = ByteArrayOutputStream()
            val exported = BackupExporter.export(context, src, source, "3.1.0+test", out, Instant.EPOCH)
            assertTrue(exported.toString(), exported is BackupExporter.Result.Ok)

            // El checkpoint PASIVO no pudo vaciar el -wal: el fichero principal solo no tiene las filas nuevas.
            assertTrue("el -wal sigue con contenido", File(source.path + "-wal").length() > 0)
            val mainOnly = File(dir, "main-only.db")
            source.copyTo(mainOnly)
            SQLiteDatabase.openDatabase(mainOnly.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM history WHERE cid = '222'", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            }

            // La copia, restaurada, tiene todas: las 4 filas escritas tras abrir la foto vienen del -wal.
            helper(target).close()
            val stager = RestoreStager(context, dir, target.name)
            assertTrue(stager.stage(ByteArrayInputStream(out.toByteArray())) is RestoreStager.Result.Staged)
            stager.engine.requestInstall()
            assertEquals(RestoreEngine.RestoreOutcome.RESTORED, RestoreEngine(dir, target.name, AndroidDatabaseOps).recover().outcome)
            val restored = helper(target)
            assertEquals(7, restored.getRecords().size)
            assertEquals(4, restored.getRecords().count { it.cid == "222" })
        } finally {
            reader.endTransaction()
            reader.close()
        }
    }
}
