package com.alexisgordr.icdetector.backup

import android.annotation.SuppressLint
import android.content.Context
import com.alexisgordr.icdetector.storage.CellDbHelper
import java.io.File

/** 3.0 — Lado Android de [FirstRunPolicy]. */
// commit() a propósito: estas escrituras deben estar en disco antes del paso siguiente (reinicio del
// proceso, apertura de la base o instalación de una copia). apply() podría perderlas.
@SuppressLint("ApplySharedPref")
object FirstRun {

    private fun prefs(context: Context) = RestoreStartup.prefs(context)

    fun decision(context: Context): FirstRunPolicy.Decision? =
        FirstRunPolicy.parse(prefs(context).getString(FirstRunPolicy.PREF_DECISION, null))

    /** Al arrancar el proceso, después de [RestoreStartup.run]. */
    fun resolveAtStart(context: Context, restoreOutcome: RestoreEngine.RestoreOutcome) {
        val p = prefs(context)
        val (decision, store) = FirstRunPolicy.resolveAtStart(p.getString(FirstRunPolicy.PREF_DECISION, null), p.all.keys)
        if (store) p.edit().putString(FirstRunPolicy.PREF_DECISION, decision.name).commit()
        FirstRunPolicy.afterRestore(decision, restoreOutcome)?.let {
            p.edit().putString(FirstRunPolicy.PREF_DECISION, it.name).commit()
        }
    }

    fun isPending(context: Context): Boolean = decision(context) == FirstRunPolicy.Decision.PENDING

    /** Lo usa el servicio antes de abrir nada. */
    fun allowsCollection(context: Context): Boolean =
        FirstRunPolicy.allowsCollection(decision(context), RestoreStartup.engine(context).hasPendingWork())

    /**
     * «Empezar de cero»: borra de verdad cualquier dato que hubiera (por ejemplo, copiado por una
     * herramienta de transferencia), COMPRUEBA que no queda nada y solo entonces guarda la decisión.
     * False si algo no se pudo borrar o guardar: la pregunta sigue pendiente y se puede reintentar.
     * La caché se vacía también, pero no se exige vacía: el sistema puede volver a crear ficheros.
     */
    fun startFresh(context: Context): Boolean {
        CellDbHelper.getInstance(context).close()
        context.databaseList().forEach { context.deleteDatabase(it) }
        val databaseDir = context.getDatabasePath(CellDbHelper.DATABASE_NAME).parentFile
        databaseDir?.listFiles()?.forEach { it.deleteRecursively() }
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }

        val p = prefs(context)
        val editor = p.edit()
        p.all.keys.filter { it != FirstRunPolicy.PREF_DECISION }.forEach { editor.remove(it) }
        val prefsCleared = editor.commit()

        val complete = prefsCleared && FirstRunPolicy.freshCleanupComplete(
            remainingDatabaseFiles = databaseDir?.list()?.toList().orEmpty(),
            remainingFiles = context.filesDir.list()?.toList().orEmpty(),
            remainingPrefKeys = p.all.keys
        )
        if (!complete) return false
        val stored = p.edit().putString(FirstRunPolicy.PREF_DECISION, FirstRunPolicy.Decision.FRESH.name).commit()
        return stored && decision(context) == FirstRunPolicy.Decision.FRESH
    }

    /** ¿Hay datos que no salieron de esta instalación (base presente con la pregunta pendiente)? */
    fun hasForeignData(context: Context): Boolean =
        context.getDatabasePath(CellDbHelper.DATABASE_NAME).let { File(it.path).exists() }
}
