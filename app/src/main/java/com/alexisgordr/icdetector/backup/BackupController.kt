package com.alexisgordr.icdetector.backup

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.alexisgordr.icdetector.core.AppBuildId
import com.alexisgordr.icdetector.storage.CellDbHelper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.core.content.edit

/**
 * 3.0 — Operaciones de copia de seguridad que lanza la interfaz. Todas bloquean: se llaman desde
 * un hilo de E/S.
 */
// commit() a propósito: estas escrituras deben estar en disco antes del paso siguiente (reinicio del
// proceso, apertura de la base o instalación de una copia). apply() podría perderlas.
@SuppressLint("ApplySharedPref")
object BackupController {

    fun suggestedName(prefix: String, now: LocalDateTime = LocalDateTime.now()): String =
        "$prefix-${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))}.${BackupFormat.EXTENSION}"

    fun exportTo(context: Context, uri: Uri): BackupExporter.Result {
        val helper = CellDbHelper.getInstance(context)
        val database = context.getDatabasePath(CellDbHelper.DATABASE_NAME)
        val version = AppBuildId.current(
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        ) ?: ""
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                BackupExporter.export(context, helper, database, version, out)
            } ?: BackupExporter.Result.Failed("NO_OUTPUT")
        } catch (e: Exception) {
            BackupExporter.Result.Failed(e.javaClass.simpleName)
        }
    }

    fun stage(context: Context, uri: Uri): RestoreStager.Result {
        val stager = stager(context)
        return try {
            context.contentResolver.openInputStream(uri)?.use { stager.stage(it) }
                ?: RestoreStager.Result.Rejected(BackupPolicy.Rejection.NOT_A_BACKUP)
        } catch (_: Exception) {
            stager.discard()
            RestoreStager.Result.Rejected(BackupPolicy.Rejection.NOT_A_BACKUP)
        }
    }

    fun discardStaged(context: Context) = stager(context).discard()

    /**
     * Guarda los datos actuales en [preBackupUri] (la monitorización ya está parada) y, solo si sale
     * bien, confirma la restauración para el siguiente arranque. Si falla, no se reemplaza nada.
     */
    fun saveCurrentAndConfirm(context: Context, preBackupUri: Uri): BackupExporter.Result {
        val result = exportTo(context, preBackupUri)
        if (result !is BackupExporter.Result.Ok) {
            discardStaged(context)
            return result
        }
        RestoreStartup.prefs(context).edit(commit = true) {
            putString(RestoreStartup.PREF_PRE_BACKUP_NAME, displayName(context, preBackupUri) ?: "")
        }
        RestoreStartup.engine(context).requestInstall()
        return result
    }

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun stager(context: Context): RestoreStager {
        val database = context.getDatabasePath(CellDbHelper.DATABASE_NAME)
        return RestoreStager(context, database.parentFile!!, CellDbHelper.DATABASE_NAME)
    }
}
