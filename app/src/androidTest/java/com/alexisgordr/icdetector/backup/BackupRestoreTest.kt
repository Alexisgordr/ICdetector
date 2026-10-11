package com.alexisgordr.icdetector.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.storage.CellDbHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/**
 * 3.0 — Copia de seguridad con el SQLite real de Android: exportar con datos aún en el WAL,
 * preparar, instalar al "arrancar" y comprobar que la base restaurada es la de la copia. Usa un
 * directorio propio, nunca la base de la app.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.filesDir, "backup-test/databases")
    private val source = File(dir, "source.db")
    private val target = File(dir, "target.db")
    private val helpers = mutableListOf<CellDbHelper>()

    @Before fun setUp() { dir.deleteRecursively(); dir.mkdirs() }
    @After fun tearDown() { helpers.forEach { it.close() }; File(context.filesDir, "backup-test").deleteRecursively() }

    private fun helper(file: File) = CellDbHelper(context, file.absolutePath).also { helpers += it }

    private fun addRows(helper: CellDbHelper, cid: String, count: Int) = repeat(count) {
        helper.logConnection(
            observedAtMs = 1_790_000_000_000L + it, netType = "LTE", cid = cid, mnc = "07", tac = "1",
            mcc = "214", dbm = -90, radio = RadioTech.LTE, appVersion = "3.0.0-beta4+test", locationMode = "CONTINUOUS"
        )
    }

    private fun export(helper: CellDbHelper, file: File): ByteArray {
        val out = ByteArrayOutputStream()
        val result = BackupExporter.export(context, helper, file, "3.0.0-beta4+test", out, Instant.EPOCH)
        assertTrue(result.toString(), result is BackupExporter.Result.Ok)
        return out.toByteArray()
    }

    @Test fun exportIncludesRowsStillInTheWalAndRestoreReplacesTheTarget() {
        val src = helper(source)
        src.writableDatabase.enableWriteAheadLogging()
        addRows(src, "111", 5)
        val backup = export(src, source)

        // Base de destino con otros datos, abierta y cerrada como la de la app.
        val tgt = helper(target)
        addRows(tgt, "999", 2)
        tgt.close()

        val stager = RestoreStager(context, dir, target.name)
        val staged = stager.stage(ByteArrayInputStream(backup))
        assertTrue(staged.toString(), staged is RestoreStager.Result.Staged)
        // Preparar no toca la base de destino.
        assertEquals(2, helper(target).getRecords().size.also { helpers.last().close() })

        stager.engine.requestInstall()
        val result = RestoreEngine(dir, target.name, AndroidDatabaseOps).recover()
        assertEquals(RestoreEngine.RestoreOutcome.RESTORED, result.outcome)

        val restored = helper(target).getRecords()
        assertEquals(5, restored.size)
        assertTrue(restored.all { it.cid == "111" })
        assertFalse(File(dir, RestoreEngine.WORK_DIR).exists())
    }

    @Test fun campaignBackupsAreRejectedWithoutTouchingTheTarget() {
        val src = helper(source)
        addRows(src, "111", 1)
        val backup = export(src, source)
        // Mismo contenido, pero el índice dice v2.10.10.
        val tampered = rewriteManifest(backup) { it.copy(appVersion = "2.10.10") }
        val result = RestoreStager(context, dir, target.name).stage(ByteArrayInputStream(tampered))
        assertEquals(RestoreStager.Result.Rejected(BackupPolicy.Rejection.CAMPAIGN_VERSION), result)
        assertFalse(target.exists())
        assertFalse(File(dir, RestoreEngine.WORK_DIR).exists())
    }

    @Test fun damagedDatabasesAreRejected() {
        val src = helper(source)
        addRows(src, "111", 1)
        val backup = export(src, source)
        val tampered = rewriteManifest(backup, damageDatabase = true) { it }
        val result = RestoreStager(context, dir, target.name).stage(ByteArrayInputStream(tampered))
        assertEquals(RestoreStager.Result.Rejected(BackupPolicy.Rejection.CHECKSUM_MISMATCH), result)
    }

    @Test fun checkpointLeavesEverythingInTheMainFile() {
        val src = helper(source)
        src.writableDatabase.enableWriteAheadLogging()
        addRows(src, "111", 3)
        src.close()
        assertTrue(AndroidDatabaseOps.checkpointAndClose(source))
        File(source.path + "-wal").delete()
        File(source.path + "-shm").delete()
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM history", null).use { c -> c.moveToFirst(); assertEquals(3, c.getInt(0)) }
        }
    }

    private fun rewriteManifest(
        backup: ByteArray,
        damageDatabase: Boolean = false,
        change: (BackupManifest) -> BackupManifest
    ): ByteArray {
        val work = File(dir, "rewrite").apply { mkdirs() }
        val contents = BackupArchive.read(ByteArrayInputStream(backup), work, "db")
        val db = contents.database!!
        if (damageDatabase) db.appendBytes(byteArrayOf(1, 2, 3))
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, change(contents.manifest!!), db, contents.settingsJson ?: "{}")
        work.deleteRecursively()
        return out.toByteArray()
    }
}
