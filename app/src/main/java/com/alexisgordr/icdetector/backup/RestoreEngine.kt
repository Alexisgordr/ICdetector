package com.alexisgordr.icdetector.backup

import com.alexisgordr.icdetector.backup.RestoreJournal.Phase
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 3.0 — Sustitución segura de la base de datos por una copia de seguridad. Sin Android, para probar
 * en la JVM cada corte posible; las operaciones de SQLite van detrás de [DatabaseOps].
 *
 * Se ejecuta al arrancar el proceso ([recover]), antes de que nada abra la base: así no queda
 * ninguna conexión abierta sobre los ficheros que se mueven. Secuencia de una instalación:
 *  1. Comprobar la copia preparada (SHA-256 del estado anotado e identificador de su cabecera).
 *  2. Checkpoint completo y comprobado de la base actual, y cerrarla. Si no se completa, se cancela
 *     sin tocar nada: borrar o mover el WAL sin checkpoint podría perder datos.
 *  3. Mover (nunca borrar) la base actual y sus auxiliares (`-wal`, `-shm`, `-journal`) a
 *     `restore/previous/`.
 *  4. Renombrar la copia preparada a su sitio (mismo sistema de ficheros: renombrado atómico).
 *  5. Reabrir y verificar: integridad, esquema e identificador de restauración.
 *  6. Marcar DONE, limpiar `previous/` y el directorio de trabajo, y borrar el diario el último.
 * Cada fase se escribe en el diario antes de su paso. Ante un corte, [recover] vuelve a la base
 * anterior salvo que la nueva ya esté instalada y verificada.
 *
 * La base nueva se reconoce por su cabecera (`application_id`, bytes 68–71), no por abrir y ver que
 * "es válida": tras un corte, en el sitio puede estar todavía la antigua.
 */
class RestoreEngine(
    private val databaseDir: File,
    private val databaseName: String,
    private val ops: DatabaseOps,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Punto de corte para los tests: se llama antes (y después) de cada paso y puede lanzar. */
    private val step: (String) -> Unit = {},
    /**
     * Tras marcar DONE y antes de limpiar: aplica los ajustes de la copia ([STAGED_SETTINGS], si
     * existe). Debe ser repetible: un corte en la limpieza lo vuelve a llamar en el siguiente arranque.
     */
    private val onRestored: (settings: File) -> Unit = {}
) {
    interface DatabaseOps {
        /** Checkpoint TRUNCATE completo de [database] y cierre. False si no se completó. */
        fun checkpointAndClose(database: File): Boolean
        /** Abrir [database] ya instalada y comprobar integridad, esquema y [restoreId]. */
        fun verifyInstalled(database: File, restoreId: Int): Boolean
    }

    enum class RestoreOutcome {
        /** No había ninguna restauración pendiente. */
        NOTHING,
        RESTORED,
        /** Algo falló o se cortó; se ha vuelto a la base anterior, intacta. */
        ROLLED_BACK,
        /** Había una copia preparada sin confirmar: se ha descartado. */
        DISCARDED,
        /**
         * No se puede decidir con seguridad (diario ilegible, ficheros en conflicto) o la vuelta atrás
         * no pudo terminar: no se toca nada más, el diario se conserva y la monitorización sigue
         * bloqueada hasta que se resuelva.
         */
        PENDING
    }

    data class Result(val outcome: RestoreOutcome, val reason: String? = null)

    val workDir = File(databaseDir, WORK_DIR)
    val stagedFile = File(workDir, STAGED_NAME)
    val previousDir = File(workDir, PREVIOUS_DIR)
    private val journal = RestoreJournal(File(workDir, JOURNAL_NAME))
    private val databaseFile = File(databaseDir, databaseName)
    private val databaseFiles get() = listOf(databaseName) + AUX_SUFFIXES.map { databaseName + it }

    fun hasPendingWork(): Boolean = journal.exists()

    fun phase(): Phase? = journal.read()?.phase

    /**
     * La copia ya está preparada en [stagedFile] (validada, migrada, con [restoreId] en su cabecera,
     * en modo DELETE y cerrada). Anota su estado: fase VALIDATED.
     */
    fun registerStaged(restoreId: Int) {
        require(restoreId != 0) { "restoreId 0 es el valor por defecto de SQLite" }
        require(stagedFile.exists()) { "No hay copia preparada" }
        require(readApplicationId(stagedFile) == restoreId) { "La cabecera no lleva el identificador" }
        require(readApplicationId(databaseFile) != restoreId) { "El identificador coincide con la base actual" }
        AUX_SUFFIXES.forEach { require(!File(workDir, STAGED_NAME + it).exists()) { "La copia preparada tiene auxiliares" } }
        journal.write(RestoreJournal.State(Phase.VALIDATED, restoreId, BackupArchive.sha256(stagedFile), clock()))
    }

    /** El usuario ha confirmado (y su copia de los datos actuales está guardada). */
    fun requestInstall() {
        val state = journal.read() ?: throw IOException("No hay restauración preparada")
        check(state.phase == Phase.VALIDATED) { "Fase inesperada: ${state.phase}" }
        journal.write(state.copy(phase = Phase.INSTALL_REQUESTED))
    }

    /** Descarta una copia preparada que no se ha empezado a instalar. */
    fun discardStaged() {
        val phase = journal.read()?.phase
        if (phase != null && phase != Phase.VALIDATED && phase != Phase.INSTALL_REQUESTED) return
        discardStagedFiles()
    }

    /** Al arrancar el proceso, antes de abrir la base. */
    fun recover(): Result {
        val state = journal.read() ?: return sweepWithoutJournal()
        return try {
            when (state.phase) {
                Phase.VALIDATED -> { discardStagedFiles(); Result(RestoreOutcome.DISCARDED) }
                Phase.INSTALL_REQUESTED -> install(state)
                Phase.NEW_INSTALLED -> finishInstalled(state)
                Phase.DONE -> completeAfterDone()
                Phase.UNREADABLE -> Result(RestoreOutcome.PENDING, "UNREADABLE_JOURNAL")
                Phase.CHECKPOINTED, Phase.MOVING_OLD, Phase.OLD_SAVED,
                Phase.INSTALLING_NEW, Phase.ROLLING_BACK -> rollback(state, state.reason ?: "INTERRUPTED")
            }
        } catch (e: IOException) {
            afterError(state, "IO_ERROR")
        } catch (e: RuntimeException) {
            afterError(state, "ERROR")
        }
    }

    /**
     * Un error a mitad de camino. Si el diario ya dice DONE, la base nueva está instalada y
     * verificada: nunca se deshace, solo queda la limpieza pendiente.
     */
    private fun afterError(state: RestoreJournal.State, reason: String): Result {
        val phase = try { journal.read()?.phase } catch (_: Exception) { null }
        if (phase == Phase.DONE) return Result(RestoreOutcome.RESTORED, CLEANUP_PENDING)
        if (phase == Phase.UNREADABLE) return Result(RestoreOutcome.PENDING, "UNREADABLE_JOURNAL")
        return safeRollback(state, reason)
    }

    private fun install(state: RestoreJournal.State): Result {
        step("verify-staged")
        if (!stagedFile.exists() ||
            BackupArchive.sha256(stagedFile) != state.stagedSha256 ||
            readApplicationId(stagedFile) != state.restoreId
        ) {
            discardStagedFiles()
            return Result(RestoreOutcome.ROLLED_BACK, "STAGED_CHANGED")
        }
        step("checkpoint")
        if (databaseFile.exists() && !ops.checkpointAndClose(databaseFile)) {
            discardStagedFiles()
            return Result(RestoreOutcome.ROLLED_BACK, "CHECKPOINT_INCOMPLETE")
        }
        journal.write(state.copy(phase = Phase.CHECKPOINTED))
        journal.write(state.copy(phase = Phase.MOVING_OLD))
        previousDir.mkdirs()
        for (name in databaseFiles) {
            val file = File(databaseDir, name)
            if (!file.exists()) continue
            step("move:$name")
            move(file, File(previousDir, name))
            step("moved:$name")
        }
        RestoreJournal.syncDirectory(databaseDir)
        journal.write(state.copy(phase = Phase.OLD_SAVED))
        journal.write(state.copy(phase = Phase.INSTALLING_NEW))
        step("install")
        move(stagedFile, databaseFile)
        RestoreJournal.syncDirectory(databaseDir)
        step("installed")
        journal.write(state.copy(phase = Phase.NEW_INSTALLED))
        return finishInstalled(state)
    }

    private fun finishInstalled(state: RestoreJournal.State): Result {
        step("verify")
        val ok = readApplicationId(databaseFile) == state.restoreId &&
            ops.verifyInstalled(databaseFile, state.restoreId)
        if (!ok) return rollback(state, "VERIFY_FAILED")
        journal.write(state.copy(phase = Phase.DONE))
        step("done")
        return completeAfterDone()
    }

    /**
     * Después de DONE: aplicar los ajustes y limpiar. Si algo falla, la base nueva se queda (está
     * verificada) y el diario sigue en DONE para reintentar la limpieza en el siguiente arranque.
     */
    private fun completeAfterDone(): Result = try {
        applySettings()
        cleanupAfterDone()
        Result(RestoreOutcome.RESTORED)
    } catch (_: IOException) {
        Result(RestoreOutcome.RESTORED, CLEANUP_PENDING)
    } catch (_: RuntimeException) {
        Result(RestoreOutcome.RESTORED, CLEANUP_PENDING)
    }

    private fun applySettings() {
        val settings = File(workDir, STAGED_SETTINGS)
        if (settings.exists()) onRestored(settings)
    }

    /**
     * Repetible: si se corta o falla, el siguiente arranque ve DONE y vuelve a empezar. El diario solo
     * se borra cuando todo lo demás se ha borrado de verdad.
     */
    private fun cleanupAfterDone() {
        step("cleanup")
        previousDir.listFiles()?.forEach { if (!it.delete() && it.exists()) throw IOException("No se pudo borrar ${it.name}") }
        if (previousDir.exists() && !previousDir.delete()) throw IOException("No se pudo borrar previous/")
        deleteStagedFiles()
        step("cleanup-journal")
        journal.delete()
        workDir.delete()
    }

    private fun rollback(state: RestoreJournal.State, reason: String): Result {
        // Nunca deshacer una instalación verificada, ni actuar sin poder reconocer la base nueva.
        when (journal.read()?.phase) {
            Phase.DONE -> return Result(RestoreOutcome.RESTORED, CLEANUP_PENDING)
            Phase.UNREADABLE -> return Result(RestoreOutcome.PENDING, "UNREADABLE_JOURNAL")
            else -> Unit
        }
        if (state.restoreId == 0) return Result(RestoreOutcome.PENDING, "UNKNOWN_RESTORE_ID")
        journal.write(state.copy(phase = Phase.ROLLING_BACK, reason = reason))
        step("rollback")
        // 1. Quitar la base nueva si está en el sitio: primero sus auxiliares, después el principal,
        //    así un corte nunca deja auxiliares nuevos junto a la base antigua. Solo se reconoce
        //    como nueva por su identificador; con un identificador desconocido no se borra nada.
        if (readApplicationId(databaseFile) == state.restoreId) {
            AUX_SUFFIXES.forEach { File(databaseDir, databaseName + it).delete() }
            step("rollback-removed-new-aux")
            databaseFile.delete()
            step("rollback-removed-new")
        }
        // 2. Devolver cada fichero anterior que siga en previous/ (los que no llegaron a moverse ya
        //    están en su sitio). Si en el sitio hay un principal que no es el nuevo y previous/ también
        //    guarda uno, no se sabe cuál es el bueno: no se mezcla nada y queda pendiente.
        if (databaseFile.exists() && File(previousDir, databaseName).exists()) {
            return Result(RestoreOutcome.PENDING, "CONFLICTING_FILES")
        }
        previousDir.listFiles()?.sortedBy { if (it.name == databaseName) 0 else 1 }?.forEach { saved ->
            val target = File(databaseDir, saved.name)
            if (!target.exists()) {
                step("restore:${saved.name}")
                move(saved, target)
            }
        }
        RestoreJournal.syncDirectory(databaseDir)
        step("rollback-cleanup")
        if (previousDir.listFiles().isNullOrEmpty()) previousDir.delete()
        deleteStagedFiles()
        journal.delete()
        if (workDir.listFiles().isNullOrEmpty()) workDir.delete()
        return Result(RestoreOutcome.ROLLED_BACK, reason)
    }

    private fun safeRollback(state: RestoreJournal.State, reason: String): Result = try {
        rollback(state, reason)
    } catch (_: Exception) {
        Result(RestoreOutcome.PENDING, reason)
    }

    /** Sin diario no debería quedar nada; si quedaran ficheros anteriores sin base en su sitio, se devuelven. */
    private fun sweepWithoutJournal(): Result {
        if (!workDir.exists()) return Result(RestoreOutcome.NOTHING)
        if (!databaseFile.exists()) {
            previousDir.listFiles()?.forEach { saved ->
                val target = File(databaseDir, saved.name)
                if (!target.exists()) move(saved, target)
            }
        }
        deleteStagedFiles()
        if (previousDir.listFiles().isNullOrEmpty()) previousDir.delete()
        if (workDir.listFiles().isNullOrEmpty()) workDir.delete()
        return Result(RestoreOutcome.NOTHING)
    }

    private fun discardStagedFiles() {
        deleteStagedFiles()
        journal.delete()
        if (previousDir.listFiles().isNullOrEmpty()) previousDir.delete()
        if (workDir.listFiles().isNullOrEmpty()) workDir.delete()
    }

    private fun deleteStagedFiles() {
        for (name in listOf(STAGED_NAME, EXTRACTED_NAME)) {
            File(workDir, name).delete()
            AUX_SUFFIXES.forEach { File(workDir, name + it).delete() }
        }
        File(workDir, STAGED_SETTINGS).delete()
    }

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) throw IOException("No se pudo mover ${from.name}")
    }

    companion object {
        /** Resultado RESTORED con la limpieza pendiente para el siguiente arranque. */
        const val CLEANUP_PENDING = "CLEANUP_PENDING"
        const val WORK_DIR = "restore"
        const val STAGED_NAME = "staged.db"
        const val PREVIOUS_DIR = "previous"
        const val JOURNAL_NAME = "journal.json"
        /** Ajustes de la copia, aplicados solo si la restauración termina bien. */
        const val STAGED_SETTINGS = "staged-settings.json"
        /** Base tal como sale del ZIP, antes de validarla y migrarla (ver RestoreStager). */
        const val EXTRACTED_NAME = "extracted.db"
        val AUX_SUFFIXES = listOf("-wal", "-shm", "-journal")

        /** `application_id` de la cabecera SQLite (bytes 68–71, big-endian), sin abrir la base. */
        fun readApplicationId(file: File): Int? {
            if (!file.isFile || file.length() < 100) return null
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val magic = ByteArray(16)
                    raf.readFully(magic)
                    if (String(magic, Charsets.US_ASCII) != "SQLite format 3\u0000") return null
                    raf.seek(68)
                    raf.readInt()
                }
            } catch (_: IOException) {
                null
            }
        }
    }
}
