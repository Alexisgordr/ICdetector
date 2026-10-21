package com.alexisgordr.icdetector.storage

import android.content.Context
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
}
