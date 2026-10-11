package com.alexisgordr.icdetector.backup

import org.json.JSONObject

/**
 * 3.0 — Índice de una copia de seguridad (`manifest.json` dentro del fichero `.icdbackup`).
 *
 * La copia es un ZIP con tres entradas: este índice, la base de datos completa y los ajustes que
 * se pueden restaurar ([BackupSettings]). La clave de OpenCelliD no viaja nunca.
 */
data class BackupManifest(
    /** Formato del fichero de copia; ver [BackupFormat.FORMAT]. */
    val format: Int,
    /** Identificador de la app que hizo la copia. */
    val appId: String,
    /** `AppVersion` de la compilación que hizo la copia (`versionName+commit`). */
    val appVersion: String,
    /** Versión de la base de datos (`PRAGMA user_version`) en el momento de la copia. */
    val schemaVersion: Int,
    val createdAtUtc: String,
    /** SHA-256 en hexadecimal de la entrada de la base de datos tal como va en el ZIP. */
    val databaseSha256: String,
    val databaseBytes: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("format", format)
        put("appId", appId)
        put("appVersion", appVersion)
        put("schemaVersion", schemaVersion)
        put("createdAtUtc", createdAtUtc)
        put("databaseSha256", databaseSha256)
        put("databaseBytes", databaseBytes)
    }.toString(2)

    companion object {
        /** Null si no es un índice reconocible (falta un campo o tiene otro tipo). */
        fun parse(json: String): BackupManifest? = try {
            val o = JSONObject(json)
            BackupManifest(
                format = o.getInt("format"),
                appId = o.getString("appId"),
                appVersion = o.optString("appVersion", ""),
                schemaVersion = o.getInt("schemaVersion"),
                createdAtUtc = o.optString("createdAtUtc", ""),
                databaseSha256 = o.getString("databaseSha256"),
                databaseBytes = o.getLong("databaseBytes")
            )
        } catch (_: Exception) {
            null
        }
    }
}

object BackupFormat {
    /** Único formato de copia que entiende esta versión de la app. */
    const val FORMAT = 1
    const val APP_ID = "com.alexisgordr.icdetector"
    const val MANIFEST_ENTRY = "manifest.json"
    const val DATABASE_ENTRY = "icdetector_history.db"
    const val SETTINGS_ENTRY = "settings.json"
    const val EXTENSION = "icdbackup"
    const val MIME_TYPE = "application/octet-stream"

    /**
     * Primera versión de la base de datos de la 3.0 (esquema 20, 3.0.0-beta1). La v2.10.x usa el
     * esquema 19. Ver [BackupPolicy]: el corte de la campaña se aplica por versión de origen y por
     * esquema, no solo porque se pueda migrar.
     */
    const val MIN_SCHEMA = 20

    /** Límite de tamaño de la base de datos dentro de una copia (protección ante ficheros raros). */
    const val MAX_DATABASE_BYTES = 2L * 1024 * 1024 * 1024
}
