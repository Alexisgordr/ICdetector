package com.alexisgordr.icdetector.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 3.0 — Lectura y escritura del fichero `.icdbackup` (un ZIP). Sin Android, para probarlo en la JVM.
 *
 * Al leer solo se aceptan las tres entradas conocidas, por nombre exacto: ningún otro nombre llega
 * al disco (sin rutas, sin `..`). La base de datos se escribe en el directorio que se indique y se
 * calcula su SHA-256 mientras se copia.
 */
object BackupArchive {

    fun write(
        out: OutputStream,
        manifest: BackupManifest,
        database: File,
        settingsJson: String
    ) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST_ENTRY))
            zip.write(manifest.toJson().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupFormat.DATABASE_ENTRY))
            database.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupFormat.SETTINGS_ENTRY))
            zip.write(settingsJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    class Contents(
        val manifest: BackupManifest?,
        /** Base de datos extraída, o null si la copia no la traía. */
        val database: File?,
        /** SHA-256 calculado de la base extraída. */
        val databaseSha256: String?,
        val settingsJson: String?,
        /** La base superaba [BackupFormat.MAX_DATABASE_BYTES] y no se ha extraído entera. */
        val tooLarge: Boolean
    )

    /** Extrae la copia en [targetDir] (la base como [databaseFileName]). */
    fun read(input: InputStream, targetDir: File, databaseFileName: String): Contents {
        targetDir.mkdirs()
        var manifest: BackupManifest? = null
        var database: File? = null
        var sha: String? = null
        var settings: String? = null
        var tooLarge = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    BackupFormat.MANIFEST_ENTRY ->
                        manifest = BackupManifest.parse(readLimited(zip, MAX_TEXT_BYTES) ?: "")
                    BackupFormat.SETTINGS_ENTRY ->
                        settings = readLimited(zip, MAX_TEXT_BYTES)
                    BackupFormat.DATABASE_ENTRY -> {
                        val file = File(targetDir, databaseFileName)
                        val digest = MessageDigest.getInstance("SHA-256")
                        var total = 0L
                        file.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > BackupFormat.MAX_DATABASE_BYTES) { tooLarge = true; break }
                                digest.update(buffer, 0, n)
                                out.write(buffer, 0, n)
                            }
                            out.fd.sync()
                        }
                        if (tooLarge) file.delete() else {
                            database = file
                            sha = digest.digest().toHex()
                        }
                    }
                    else -> Unit // Entradas desconocidas: se ignoran y nunca se escriben.
                }
                zip.closeEntry()
                if (tooLarge) break
            }
        }
        return Contents(manifest, database, sha, settings, tooLarge)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private const val MAX_TEXT_BYTES = 1024 * 1024

    private fun readLimited(input: InputStream, limit: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (bytes.size() + n > limit) return null
            bytes.write(buffer, 0, n)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
