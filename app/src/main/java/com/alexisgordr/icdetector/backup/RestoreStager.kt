package com.alexisgordr.icdetector.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.alexisgordr.icdetector.storage.CellDbHelper
import java.io.File
import java.io.InputStream
import kotlin.random.Random

/**
 * 3.0 — Prepara una copia de seguridad para restaurarla, sin tocar la base de la app.
 *
 * Todo ocurre en el directorio de trabajo de [RestoreEngine]:
 *  1. Extraer la copia y comprobar su índice ([BackupPolicy]) y el SHA-256 de la base.
 *  2. Abrirla en solo lectura: `integrity_check`, tabla `history` y que su `user_version` coincida
 *     con el del índice (antes de que el helper pueda crear tablas en un fichero ajeno).
 *  3. Migrarla al esquema actual con el helper de la app, sobre la copia.
 *  4. Escribir el identificador de restauración en su cabecera (`application_id`), consolidarla en
 *     un solo fichero y anotar su estado en el diario (fase VALIDATED).
 * Si algo falla, se borra todo lo preparado y la base de la app sigue igual.
 */
class RestoreStager(
    private val context: Context,
    private val databaseDir: File,
    private val databaseName: String = CellDbHelper.DATABASE_NAME
) {
    sealed class Result {
        data class Staged(val manifest: BackupManifest) : Result()
        data class Rejected(val reason: BackupPolicy.Rejection) : Result()
    }

    val engine = RestoreEngine(databaseDir, databaseName, AndroidDatabaseOps)

    fun stage(input: InputStream): Result {
        engine.discardStaged()
        val work = engine.workDir.apply { mkdirs() }
        val extracted = BackupArchive.read(input, work, EXTRACTED_NAME)
        val reject = { reason: BackupPolicy.Rejection -> discard(); Result.Rejected(reason) }
        if (extracted.tooLarge) return reject(BackupPolicy.Rejection.TOO_LARGE)
        val manifest = extracted.manifest
        BackupPolicy.check(manifest, CellDbHelper.DATABASE_VERSION)?.let { return reject(it) }
        val database = extracted.database ?: return reject(BackupPolicy.Rejection.NOT_A_BACKUP)
        if (extracted.databaseSha256 != manifest!!.databaseSha256 || database.length() != manifest.databaseBytes) {
            return reject(BackupPolicy.Rejection.CHECKSUM_MISMATCH)
        }
        if (!looksLikeOurDatabase(database, manifest.schemaVersion)) return reject(BackupPolicy.Rejection.CORRUPT_DATABASE)

        // Migración al esquema actual sobre la copia.
        try {
            CellDbHelper(context, database.absolutePath).use { it.writableDatabase }
        } catch (_: Exception) {
            return reject(BackupPolicy.Rejection.CORRUPT_DATABASE)
        }
        val current = RestoreEngine.readApplicationId(File(databaseDir, databaseName))
        var restoreId: Int
        do { restoreId = Random.nextInt() } while (restoreId == 0 || restoreId == current)
        try {
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("PRAGMA application_id = $restoreId")
                if (AndroidDatabaseOps.pragmaInt(db, "user_version") != CellDbHelper.DATABASE_VERSION ||
                    !AndroidDatabaseOps.integrityOk(db)
                ) return reject(BackupPolicy.Rejection.CORRUPT_DATABASE)
            }
        } catch (_: Exception) {
            return reject(BackupPolicy.Rejection.CORRUPT_DATABASE)
        }
        if (!AndroidDatabaseOps.consolidate(database)) return reject(BackupPolicy.Rejection.CORRUPT_DATABASE)
        if (!database.renameTo(engine.stagedFile)) return reject(BackupPolicy.Rejection.CORRUPT_DATABASE)
        File(work, RestoreEngine.STAGED_SETTINGS).writeText(extracted.settingsJson ?: "{}")
        engine.registerStaged(restoreId)
        return Result.Staged(manifest)
    }

    fun discard() {
        File(engine.workDir, EXTRACTED_NAME).delete()
        RestoreEngine.AUX_SUFFIXES.forEach { File(engine.workDir, EXTRACTED_NAME + it).delete() }
        engine.discardStaged()
    }

    private fun looksLikeOurDatabase(file: File, expectedSchema: Int): Boolean = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            AndroidDatabaseOps.integrityOk(db) &&
                AndroidDatabaseOps.hasHistoryTable(db) &&
                AndroidDatabaseOps.pragmaInt(db, "user_version") == expectedSchema
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        const val EXTRACTED_NAME = RestoreEngine.EXTRACTED_NAME
    }
}
