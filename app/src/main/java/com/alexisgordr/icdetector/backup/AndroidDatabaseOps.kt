package com.alexisgordr.icdetector.backup

import android.database.sqlite.SQLiteDatabase
import com.alexisgordr.icdetector.storage.CellDbHelper
import java.io.File

/**
 * 3.0 — Operaciones de SQLite para la copia de seguridad. Todas abren la base con una conexión
 * propia y la cierran: se usan al arrancar el proceso (antes de que el helper de la app la abra) o
 * sobre copias en el directorio de trabajo.
 */
object AndroidDatabaseOps : RestoreEngine.DatabaseOps {

    /**
     * `PRAGMA wal_checkpoint(TRUNCATE)` devuelve (busy, frames en el WAL, frames volcados). Completo:
     * busy = 0 y todos los frames volcados (o -1/-1 si la base no está en modo WAL).
     */
    override fun checkpointAndClose(database: File): Boolean {
        if (!database.exists()) return true
        return try {
            val db = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                    c.moveToFirst() && c.getInt(0) == 0 && c.getInt(1) == c.getInt(2)
                }
            } finally {
                db.close()
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun verifyInstalled(database: File, restoreId: Int): Boolean = try {
        val db = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            pragmaInt(db, "application_id") == restoreId &&
                pragmaInt(db, "user_version") == CellDbHelper.DATABASE_VERSION &&
                integrityOk(db) && hasHistoryTable(db)
        } finally {
            db.close()
        }
    } catch (_: Exception) {
        false
    }

    fun pragmaInt(db: SQLiteDatabase, name: String): Int =
        db.rawQuery("PRAGMA $name", null).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }

    fun integrityOk(db: SQLiteDatabase): Boolean =
        db.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" && c.count == 1 }

    fun hasHistoryTable(db: SQLiteDatabase): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(CellDbHelper.TABLE_HISTORY)
    ).use { it.moveToFirst() }

    /**
     * Deja [database] en un solo fichero: checkpoint completo y modo de diario DELETE, sin `-wal` ni
     * `-shm`. Así su contenido y su SHA-256 corresponden a un estado concreto.
     */
    fun consolidate(database: File): Boolean {
        val ok = try {
            val db = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                val checkpointed = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                    c.moveToFirst() && c.getInt(0) == 0 && c.getInt(1) == c.getInt(2)
                }
                val mode = db.rawQuery("PRAGMA journal_mode=DELETE", null).use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
                checkpointed && mode.equals("delete", ignoreCase = true)
            } finally {
                db.close()
            }
        } catch (_: Exception) {
            false
        }
        if (!ok) return false
        // Tras cerrar en modo DELETE no deben quedar auxiliares con contenido. Uno vacío sí se borra.
        for (suffix in RestoreEngine.AUX_SUFFIXES) {
            val aux = File(database.path + suffix)
            if (!aux.exists()) continue
            if (aux.length() > 0) return false
            aux.delete()
        }
        return true
    }
}
