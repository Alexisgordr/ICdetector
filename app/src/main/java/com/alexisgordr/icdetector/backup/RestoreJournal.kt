package com.alexisgordr.icdetector.backup

import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * 3.0 — Diario de una restauración. Registra la FASE de la operación y se escribe en disco de forma
 * atómica (temporal, sincronizado, renombrado) ANTES del paso que describe. Tras un corte, la
 * recuperación decide con la última fase escrita y con los ficheros que existen de verdad: no
 * registra cada fichero movido, así que un corte entre mover un fichero y anotarlo no la confunde.
 */
class RestoreJournal(private val file: File) {

    enum class Phase {
        /** Copia validada y preparada en el directorio de trabajo; el usuario aún no ha confirmado. */
        VALIDATED,
        /** El usuario ha confirmado; se instala en el siguiente arranque, antes de abrir la base. */
        INSTALL_REQUESTED,
        /** Checkpoint completo y comprobado; conexiones cerradas. Nada movido aún. */
        CHECKPOINTED,
        /** Moviendo los ficheros de la base actual a `previous/`. */
        MOVING_OLD,
        /** Todos los ficheros de la base actual están en `previous/`. */
        OLD_SAVED,
        /** Renombrando la copia preparada a su sitio. */
        INSTALLING_NEW,
        /** La copia está en su sitio; falta reabrirla y verificarla. */
        NEW_INSTALLED,
        /** Verificada. Queda limpiar `previous/` y el directorio de trabajo; luego se borra el diario. */
        DONE,
        /** Volviendo a la base anterior. */
        ROLLING_BACK,
        /**
         * El diario existe pero no se puede leer: no se sabe en qué estado quedaron los ficheros. No
         * se toca nada y la restauración queda pendiente (la monitorización sigue bloqueada).
         */
        UNREADABLE
    }

    data class State(
        val phase: Phase,
        /** Identificador escrito en la cabecera de la base restaurada (`PRAGMA application_id`). */
        val restoreId: Int,
        /**
         * SHA-256 del fichero preparado en el estado concreto en que quedó tras validarlo y cerrarlo
         * (modo de diario DELETE, sin ficheros auxiliares). Solo se compara antes de abrirlo otra vez:
         * abrir o migrar SQLite cambia sus ficheros. Después, la base se reconoce por [restoreId].
         */
        val stagedSha256: String,
        val startedAtMs: Long,
        val reason: String? = null
    )

    fun exists(): Boolean = file.exists()

    /** Null si no hay diario. Un diario ilegible devuelve la fase [Phase.UNREADABLE]. */
    fun read(): State? {
        if (!file.exists()) return null
        return try {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            State(
                phase = Phase.valueOf(o.getString("phase")),
                restoreId = o.getInt("restoreId"),
                stagedSha256 = o.getString("stagedSha256"),
                startedAtMs = o.getLong("startedAtMs"),
                reason = if (o.has("reason")) o.getString("reason") else null
            )
        } catch (_: Exception) {
            State(Phase.UNREADABLE, restoreId = 0, stagedSha256 = "", startedAtMs = 0L, reason = "UNREADABLE_JOURNAL")
        }
    }

    fun write(state: State) {
        file.parentFile?.mkdirs()
        val json = JSONObject().apply {
            put("phase", state.phase.name)
            put("restoreId", state.restoreId)
            put("stagedSha256", state.stagedSha256)
            put("startedAtMs", state.startedAtMs)
            state.reason?.let { put("reason", it) }
        }.toString()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw java.io.IOException("No se pudo escribir el diario de restauración")
        syncDirectory(file.parentFile)
    }

    /** El diario se borra siempre el último. */
    fun delete() {
        File(file.parentFile, file.name + ".tmp").delete()
        file.delete()
        syncDirectory(file.parentFile)
    }

    companion object {
        /** Hace persistentes los renombrados del directorio (mejor esfuerzo: no todos los sistemas lo permiten). */
        fun syncDirectory(dir: File?) {
            if (dir == null) return
            try {
                FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } catch (_: Exception) {
            }
        }
    }
}
