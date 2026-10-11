package com.alexisgordr.icdetector.backup

import com.alexisgordr.icdetector.backup.RestoreEngine.RestoreOutcome
import com.alexisgordr.icdetector.backup.RestoreJournal.Phase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * 3.0 — Restauración: cada corte posible termina en una base completa, la anterior intacta o la
 * nueva verificada, nunca una mezcla. Los cortes se simulan con un [Cut] lanzado en un paso; la
 * recuperación la hace un motor nuevo, como en el siguiente arranque del proceso.
 */
class RestoreEngineTest {

    private class Cut(val at: String) : Error("cut at $at")

    private val dir: File = Files.createTempDirectory("restore").toFile()
    private val name = "icdetector_history.db"
    private val db = File(dir, name)

    /** Fichero con cabecera SQLite real (los 16 bytes mágicos) y [appId] en los bytes 68–71. */
    private fun sqliteLike(appId: Int, fill: Byte): ByteArray {
        val bytes = ByteArray(4096) { fill }
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        ByteBuffer.wrap(bytes, 68, 4).putInt(appId)
        return bytes
    }

    private val oldMain = sqliteLike(0, 1)
    private val oldWal = byteArrayOf(9, 9, 9)
    private val oldShm = byteArrayOf(8, 8)
    private val newId = 0x1CD0BAC1
    private val newMain = sqliteLike(newId, 2)

    private class FakeOps(var checkpointOk: Boolean = true, var verifyOk: Boolean = true) : RestoreEngine.DatabaseOps {
        val checkpointed = mutableListOf<File>()
        override fun checkpointAndClose(database: File) = checkpointOk.also { checkpointed += database }
        override fun verifyInstalled(database: File, restoreId: Int): Boolean {
            if (!verifyOk) return false
            // Abrir la base instalada crea auxiliares nuevos, como SQLite.
            File(database.path + "-wal").writeBytes(byteArrayOf(7))
            return RestoreEngine.readApplicationId(database) == restoreId
        }
    }

    private fun engine(ops: FakeOps = FakeOps(), cutAt: String? = null) =
        RestoreEngine(dir, name, ops, clock = { 1L }, step = { if (it == cutAt) throw Cut(it) })

    private fun givenOldDatabase() {
        db.writeBytes(oldMain)
        File(dir, "$name-wal").writeBytes(oldWal)
        File(dir, "$name-shm").writeBytes(oldShm)
    }

    private fun stageAndConfirm(e: RestoreEngine) {
        e.workDir.mkdirs()
        e.stagedFile.writeBytes(newMain)
        e.registerStaged(newId)
        e.requestInstall()
    }

    private fun assertOldIntact() {
        assertArrayEquals(oldMain, db.readBytes())
        assertArrayEquals(oldWal, File(dir, "$name-wal").readBytes())
        assertArrayEquals(oldShm, File(dir, "$name-shm").readBytes())
        assertFalse(File(dir, "$name-journal").exists())
        assertFalse("sin trabajo pendiente", File(dir, RestoreEngine.WORK_DIR).exists())
    }

    private fun assertNewInstalled() {
        assertArrayEquals(newMain, db.readBytes())
        assertEquals(newId, RestoreEngine.readApplicationId(db))
        assertFalse("sin restos de la base anterior", File(dir, RestoreEngine.WORK_DIR).exists())
    }

    @Test fun `a confirmed restore replaces the database and leaves nothing behind`() {
        givenOldDatabase()
        val ops = FakeOps()
        val e = engine(ops)
        stageAndConfirm(e)
        assertEquals(RestoreOutcome.RESTORED, e.recover().outcome)
        assertNewInstalled()
        assertEquals(listOf(db), ops.checkpointed)
        // Los auxiliares antiguos no se mezclan con la base nueva.
        assertFalse(File(dir, "$name-shm").exists())
    }

    @Test fun `restoring on a fresh install, with no database yet, works`() {
        val e = engine()
        stageAndConfirm(e)
        assertEquals(RestoreOutcome.RESTORED, e.recover().outcome)
        assertNewInstalled()
    }

    @Test fun `an incomplete checkpoint cancels before touching any file`() {
        givenOldDatabase()
        val e = engine(FakeOps(checkpointOk = false))
        stageAndConfirm(e)
        val result = e.recover()
        assertEquals(RestoreOutcome.ROLLED_BACK, result.outcome)
        assertEquals("CHECKPOINT_INCOMPLETE", result.reason)
        assertOldIntact()
    }

    @Test fun `a staged copy changed after validation is not installed`() {
        givenOldDatabase()
        val e = engine()
        stageAndConfirm(e)
        e.stagedFile.appendBytes(byteArrayOf(0))
        assertEquals("STAGED_CHANGED", e.recover().reason)
        assertOldIntact()
    }

    @Test fun `a staged copy never confirmed is discarded at the next start`() {
        givenOldDatabase()
        val e = engine()
        e.workDir.mkdirs(); e.stagedFile.writeBytes(newMain); e.registerStaged(newId)
        assertEquals(RestoreOutcome.DISCARDED, engine().recover().outcome)
        assertOldIntact()
    }

    /** Todos los cortes antes de que la base nueva esté verificada vuelven a la anterior. */
    @Test fun `every cut before verification brings the old database back intact`() {
        val cuts = listOf(
            "verify-staged", "checkpoint",
            "move:$name", "moved:$name",              // corte tras mover el principal, antes de anotarlo
            "move:$name-wal", "moved:$name-wal",
            "move:$name-shm", "moved:$name-shm",
            "install", "installed", "verify"
        )
        cuts.forEach { cut ->
            dir.listFiles()?.forEach { it.deleteRecursively() }
            givenOldDatabase()
            val first = engine(cutAt = cut)
            stageAndConfirm(first)
            try { first.recover(); throw AssertionError("no se cortó en $cut") } catch (c: Cut) { assertEquals(cut, c.at) }

            val result = engine().recover()
            if (cut == "verify") {
                // La base nueva ya estaba en su sitio: se verifica y se completa.
                assertEquals(cut, RestoreOutcome.RESTORED, result.outcome)
                assertNewInstalled()
            } else if (cut == "verify-staged" || cut == "checkpoint") {
                // Nada se había movido: se reintenta la instalación completa.
                assertEquals(cut, RestoreOutcome.RESTORED, result.outcome)
                assertNewInstalled()
            } else {
                assertEquals(cut, RestoreOutcome.ROLLED_BACK, result.outcome)
                assertOldIntact()
            }
        }
    }

    @Test fun `a failed verification after installing goes back to the old database`() {
        givenOldDatabase()
        val e = engine(FakeOps(verifyOk = false))
        stageAndConfirm(e)
        assertEquals("VERIFY_FAILED", e.recover().reason)
        assertOldIntact()
    }

    @Test fun `a cut in the middle of a rollback is finished at the next start`() {
        listOf("rollback", "rollback-removed-new-aux", "rollback-removed-new",
            "restore:$name", "restore:$name-shm", "rollback-cleanup").forEach { cut ->
            dir.listFiles()?.forEach { it.deleteRecursively() }
            givenOldDatabase()
            // Instalación que falla al verificar y se corta durante la vuelta atrás.
            val first = engine(FakeOps(verifyOk = false), cutAt = cut)
            stageAndConfirm(first)
            try { first.recover() } catch (_: Cut) {}
            val result = engine(FakeOps(verifyOk = false)).recover()
            assertEquals(cut, RestoreOutcome.ROLLED_BACK, result.outcome)
            assertOldIntact()
        }
    }

    @Test fun `cleanup after DONE can be cut and repeated safely, the journal goes last`() {
        listOf("done", "cleanup", "cleanup-journal").forEach { cut ->
            dir.listFiles()?.forEach { it.deleteRecursively() }
            givenOldDatabase()
            val first = engine(cutAt = cut)
            stageAndConfirm(first)
            try { first.recover() } catch (_: Cut) {}
            assertEquals(cut, Phase.DONE, engine().phase())
            assertEquals(cut, RestoreOutcome.RESTORED, engine().recover().outcome)
            assertNewInstalled()
            assertEquals(RestoreOutcome.NOTHING, engine().recover().outcome)
        }
    }

    @Test fun `the new database is recognised by its header, not by being valid`() {
        givenOldDatabase()
        assertEquals(0, RestoreEngine.readApplicationId(db))
        assertEquals(newId, RestoreEngine.readApplicationId(File(dir, "x").apply { writeBytes(newMain) }))
        assertEquals(null, RestoreEngine.readApplicationId(File(dir, "y").apply { writeBytes(ByteArray(200)) }))
        // Una copia con el mismo identificador que la base actual no se acepta.
        val e = engine()
        e.workDir.mkdirs(); e.stagedFile.writeBytes(sqliteLike(0, 3))
        try { e.registerStaged(0); throw AssertionError() } catch (_: IllegalArgumentException) {}
    }

    @Test fun `an unreadable journal touches nothing and stays pending`() {
        givenOldDatabase()
        val journal = File(dir, "${RestoreEngine.WORK_DIR}/${RestoreEngine.JOURNAL_NAME}").apply { parentFile.mkdirs(); writeText("{broken") }
        repeat(2) {
            val result = engine().recover()
            assertEquals(RestoreOutcome.PENDING, result.outcome)
            assertEquals("UNREADABLE_JOURNAL", result.reason)
        }
        assertArrayEquals(oldMain, db.readBytes())
        assertArrayEquals(oldWal, File(dir, "$name-wal").readBytes())
        assertTrue("el diario se conserva", journal.exists())
        assertTrue("sigue pendiente", engine().hasPendingWork())
    }

    /** Caso reproducido: diario ilegible con la base nueva ya instalada y la antigua en previous/. */
    @Test fun `an unreadable journal after installing never mixes files of both databases`() {
        givenOldDatabase()
        val first = engine(cutAt = "installed")
        stageAndConfirm(first)
        try { first.recover() } catch (_: Cut) {}
        val journal = File(dir, "${RestoreEngine.WORK_DIR}/${RestoreEngine.JOURNAL_NAME}")
        journal.writeText("{broken")
        val before = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes().toList() }

        val result = engine().recover()

        assertEquals(RestoreOutcome.PENDING, result.outcome)
        val after = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes().toList() }
        assertEquals("ningún fichero se mueve ni se borra", before, after)
        assertArrayEquals(newMain, db.readBytes())
        assertFalse("el WAL antiguo no vuelve junto a la base nueva", File(dir, "$name-wal").exists())
    }

    /** Caso reproducido: un error en la limpieza final después de DONE. */
    @Test fun `an error after DONE keeps the new database and retries the cleanup`() {
        listOf("cleanup", "cleanup-journal").forEach { at ->
            dir.listFiles()?.forEach { it.deleteRecursively() }
            givenOldDatabase()
            val failing = RestoreEngine(dir, name, FakeOps(), step = { if (it == at) throw java.io.IOException("disk") })
            stageAndConfirm(failing)
            val result = failing.recover()
            assertEquals(at, RestoreOutcome.RESTORED, result.outcome)
            assertEquals(at, RestoreEngine.CLEANUP_PENDING, result.reason)
            assertArrayEquals(at, newMain, db.readBytes())
            assertEquals(at, Phase.DONE, engine().phase())
            // Siguiente arranque: termina la limpieza sin tocar la base nueva.
            assertEquals(at, RestoreOutcome.RESTORED, engine().recover().outcome)
            assertNewInstalled()
        }
    }

    @Test fun `a runtime error right after DONE never rolls back`() {
        givenOldDatabase()
        val failing = RestoreEngine(dir, name, FakeOps(), step = { if (it == "done") throw IllegalStateException("boom") })
        stageAndConfirm(failing)
        val result = failing.recover()
        assertEquals(RestoreOutcome.RESTORED, result.outcome)
        assertArrayEquals(newMain, db.readBytes())
        assertEquals(RestoreOutcome.RESTORED, engine().recover().outcome)
        assertNewInstalled()
    }

    @Test fun `two main files that cannot be told apart stay pending`() {
        givenOldDatabase()
        val first = engine(cutAt = "moved:$name")
        stageAndConfirm(first)
        try { first.recover() } catch (_: Cut) {}
        // Alguien deja un principal que no es el nuevo en el sitio mientras el antiguo está en previous/.
        db.writeBytes(sqliteLike(0, 5))
        val result = engine().recover()
        assertEquals(RestoreOutcome.PENDING, result.outcome)
        assertEquals("CONFLICTING_FILES", result.reason)
        assertTrue(File(dir, "${RestoreEngine.WORK_DIR}/${RestoreEngine.PREVIOUS_DIR}/$name").exists())
    }

    @Test fun `backup settings are applied only when the restore succeeds, again after a cut`() {
        givenOldDatabase()
        val applied = mutableListOf<String>()
        val first = RestoreEngine(dir, name, FakeOps(), clock = { 1L },
            step = { if (it == "cleanup") throw Cut(it) }, onRestored = { applied += it.readText() })
        first.workDir.mkdirs()
        File(first.workDir, RestoreEngine.STAGED_SETTINGS).writeText("{\"location_mode\":\"INTELLIGENT\"}")
        stageAndConfirm(first)
        try { first.recover() } catch (_: Cut) {}
        RestoreEngine(dir, name, FakeOps(), onRestored = { applied += it.readText() }).recover()
        assertEquals(2, applied.size)
        assertNewInstalled()

        // Si la restauración vuelve atrás, los ajustes de la copia no se aplican.
        dir.listFiles()?.forEach { it.deleteRecursively() }
        givenOldDatabase()
        val failing = RestoreEngine(dir, name, FakeOps(verifyOk = false), onRestored = { applied += "rollback" })
        failing.workDir.mkdirs()
        File(failing.workDir, RestoreEngine.STAGED_SETTINGS).writeText("{}")
        stageAndConfirm(failing)
        assertEquals(RestoreOutcome.ROLLED_BACK, failing.recover().outcome)
        assertFalse(applied.contains("rollback"))
        assertOldIntact()
    }

    @Test fun `the journal phase is written before each step`() {
        givenOldDatabase()
        val seen = mutableListOf<Pair<String, Phase?>>()
        lateinit var e: RestoreEngine
        e = RestoreEngine(dir, name, FakeOps(), clock = { 1L }, step = { seen += it to e.phase() })
        stageAndConfirm(e)
        assertTrue(e.recover().outcome == RestoreOutcome.RESTORED)
        val phaseAt = seen.toMap()
        assertEquals(Phase.MOVING_OLD, phaseAt["move:$name"])
        assertEquals(Phase.INSTALLING_NEW, phaseAt["install"])
        assertEquals(Phase.NEW_INSTALLED, phaseAt["verify"])
        assertEquals(Phase.DONE, phaseAt["cleanup"])
    }
}
