package com.alexisgordr.icdetector.backup

import android.annotation.SuppressLint
import android.content.Context
import com.alexisgordr.icdetector.storage.CellDbHelper
import java.io.File

/**
 * 3.0 — Al arrancar el proceso principal (ICDetectionApp.onCreate), antes de que nada abra la base:
 * termina, completa o deshace una restauración pendiente y guarda el resultado para mostrarlo una vez.
 */
// commit() a propósito: estas escrituras deben estar en disco antes del paso siguiente (reinicio del
// proceso, apertura de la base o instalación de una copia). apply() podría perderlas.
@SuppressLint("ApplySharedPref")
object RestoreStartup {
    /** Resultado de la última restauración: `RESTORED`, `ROLLED_BACK:<motivo>` o `PENDING:<motivo>`. */
    const val PREF_LAST_RESULT = FirstRunPolicy.PREF_RESTORE_RESULT
    /** Nombre del fichero donde el usuario guardó la copia de sus datos antes de restaurar. */
    const val PREF_PRE_BACKUP_NAME = FirstRunPolicy.PREF_PRE_BACKUP_NAME

    fun engine(context: Context, onRestored: (File) -> Unit = {}): RestoreEngine {
        val database = context.getDatabasePath(CellDbHelper.DATABASE_NAME)
        return RestoreEngine(database.parentFile!!, CellDbHelper.DATABASE_NAME, AndroidDatabaseOps, onRestored = onRestored)
    }

    fun run(context: Context): RestoreEngine.Result {
        val engine = engine(context) { applySettings(context, it) }
        if (!engine.hasPendingWork() && !engine.workDir.exists()) return RestoreEngine.Result(RestoreEngine.RestoreOutcome.NOTHING)
        val result = engine.recover()
        val restoreOutcome = result.outcome
        val text = when (restoreOutcome) {
            RestoreEngine.RestoreOutcome.RESTORED -> "RESTORED"
            RestoreEngine.RestoreOutcome.ROLLED_BACK -> "ROLLED_BACK:${result.reason ?: ""}"
            RestoreEngine.RestoreOutcome.PENDING -> "PENDING:${result.reason ?: ""}"
            RestoreEngine.RestoreOutcome.NOTHING -> null
            RestoreEngine.RestoreOutcome.DISCARDED -> null
        }
        if (text != null) prefs(context).edit().putString(PREF_LAST_RESULT, text).commit()
        // 3.0 — Un historial restaurado (quizá de otro teléfono) no arranca el perfil local.
        // La opción experimental del perfil local no viaja en las copias y vuelve a estar desactivada:
        // las referencias restauradas pueden ser de otro teléfono. Si estaba activa, se anota.
        if (restoreOutcome == RestoreEngine.RestoreOutcome.RESTORED) {
            val p = prefs(context)
            val wasOn = p.getBoolean(com.alexisgordr.icdetector.core.profile.LocalProfileOption.PREF_KEY, false)
            val editor = p.edit()
                .putBoolean(FirstRunPolicy.PREF_HISTORY_RESTORED, true)
                .putBoolean(com.alexisgordr.icdetector.core.profile.LocalProfileOption.PREF_KEY, false)
            if (wasOn) editor.putBoolean(FirstRunPolicy.PREF_PROFILE_OFF_BY_RESTORE, true)
            editor.commit()
        }
        return result
    }

    /** Repetible: escribe los mismos valores si se vuelve a llamar tras un corte. */
    private fun applySettings(context: Context, file: File) {
        val values = BackupSettings.fromJson(runCatching { file.readText() }.getOrNull())
        val editor = prefs(context).edit()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        // 3.0 — Los episodios de Stable-Site restaurados nunca avisan: se borra el episodio pendiente
        // de aviso (ver StableSiteNotices). Ese dato es interno y no viaja en la copia.
        editor.remove(com.alexisgordr.icdetector.core.StableSiteNotices.PREF_PENDING_EPISODE)
        // 3.0 (G3) — Un viaje restaurado no tiene continuidad demostrable con este arranque: sin su
        // ancla monotónica, el servicio lo cierra en su última hora observada.
        editor.remove(com.alexisgordr.icdetector.core.MobilityTripAnchor.PREF_KEY)
        editor.commit()
    }

    fun prefs(context: Context) =
        context.getSharedPreferences(BackupSettings.PREFS_FILE, Context.MODE_PRIVATE)
}
