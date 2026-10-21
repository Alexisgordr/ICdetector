package com.alexisgordr.icdetector.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.alexisgordr.icdetector.storage.CellDbHelper
import java.io.File
import java.io.OutputStream
import java.time.Instant

/**
 * 3.0 — Exporta una copia de seguridad completa y coherente aunque la monitorización esté en marcha.
 *
 * Con una transacción exclusiva abierta en la conexión de la app (nadie más puede escribir y las
 * escrituras en curso terminan antes) se copian el fichero principal y, si existe, su `-wal`. La
 * copia se consolida aparte (checkpoint + modo DELETE + `integrity_check`) y ese único fichero es
 * el que va al ZIP con su SHA-256. El contenido de la base de la app no se modifica.
 *
 * 3.1.0 (#39) — La base de la app usa WAL. Antes de copiar se hace un checkpoint PASIVO (no espera
 * ni bloquea a nadie): vuelca lo que pueda al fichero principal para que el `-wal` que se copia sea
 * pequeño. Lo que no haya podido volcar sigue en el `-wal`, que se copia igual.
 */
object BackupExporter {

    sealed class Result {
        data class Ok(val manifest: BackupManifest) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun export(
        context: Context,
        helper: CellDbHelper,
        databaseFile: File,
        appVersion: String,
        out: OutputStream,
        now: Instant = Instant.now()
    ): Result {
        val workDir = File(context.cacheDir, "backup-export").apply { deleteRecursively(); mkdirs() }
        try {
            val copy = File(workDir, BackupFormat.DATABASE_ENTRY)
            val db = helper.writableDatabase
            runCatching { db.rawQuery("PRAGMA wal_checkpoint(PASSIVE)", null).use { it.moveToFirst() } }
            db.beginTransaction()
            try {
                databaseFile.copyTo(copy, overwrite = true)
                val wal = File(databaseFile.path + "-wal")
                if (wal.exists() && wal.length() > 0) wal.copyTo(File(copy.path + "-wal"), overwrite = true)
            } finally {
                db.endTransaction()
            }
            if (!AndroidDatabaseOps.consolidate(copy)) return Result.Failed("CONSOLIDATE")
            val schema = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { snap ->
                if (!AndroidDatabaseOps.integrityOk(snap) || !AndroidDatabaseOps.hasHistoryTable(snap)) {
                    return Result.Failed("INTEGRITY")
                }
                AndroidDatabaseOps.pragmaInt(snap, "user_version")
            }
            val manifest = BackupManifest(
                format = BackupFormat.FORMAT,
                appId = BackupFormat.APP_ID,
                appVersion = appVersion,
                schemaVersion = schema,
                createdAtUtc = now.toString(),
                databaseSha256 = BackupArchive.sha256(copy),
                databaseBytes = copy.length()
            )
            val prefs = context.getSharedPreferences(BackupSettings.PREFS_FILE, Context.MODE_PRIVATE)
            BackupArchive.write(out, manifest, copy, BackupSettings.toJson(prefs.all))
            return Result.Ok(manifest)
        } catch (e: Exception) {
            return Result.Failed(e.javaClass.simpleName)
        } finally {
            workDir.deleteRecursively()
        }
    }
}
