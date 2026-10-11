package com.alexisgordr.icdetector.backup

/**
 * 3.0 — Qué copias de seguridad se aceptan. Sin dependencias de Android, para probarlo en la JVM.
 *
 * Regla metodológica, no técnica: los datos de la 3.0 no se mezclan con los de la campaña v2.10.x
 * (ver IMPORTANT.md y los cortes de dataset de Status.md). Por eso una copia hecha por la v2.10.x
 * se rechaza aunque su base de datos se pudiera migrar. Se identifica por la versión de origen
 * del índice y por el formato de la copia, además del esquema.
 *
 * Se acepta solo, y a la vez:
 *  - el formato de copia que entiende esta app ([BackupFormat.FORMAT]); un formato futuro, no;
 *  - una copia de ICdetection;
 *  - una versión de origen 3.0.0 o posterior. Las betas cuentan como 3.0 de forma explícita:
 *    `3.0.0-beta1` … `3.0.0-beta4+d0e75dd` se aceptan aunque una comparación de versiones
 *    estándar las pondría antes de `3.0.0`;
 *  - un esquema entre [BackupFormat.MIN_SCHEMA] y el de esta app; uno futuro, no.
 */
object BackupPolicy {

    enum class Rejection {
        /** No hay índice, o no se puede leer. */
        NOT_A_BACKUP,
        NOT_ICDETECTION,
        UNKNOWN_FORMAT,
        MISSING_VERSION,
        /** Copia de la v2.10.x o anterior: corte de la campaña. */
        CAMPAIGN_VERSION,
        /** Esquema anterior a la 3.0 aunque la versión diga otra cosa. */
        UNSUPPORTED_SCHEMA,
        /** Copia de una versión más nueva que esta app. */
        FUTURE_SCHEMA,
        CHECKSUM_MISMATCH,
        /** La base de datos no abre, no pasa `integrity_check` o no es de ICdetection. */
        CORRUPT_DATABASE,
        TOO_LARGE
    }

    fun check(manifest: BackupManifest?, currentSchema: Int): Rejection? = when {
        manifest == null -> Rejection.NOT_A_BACKUP
        manifest.format != BackupFormat.FORMAT -> Rejection.UNKNOWN_FORMAT
        manifest.appId != BackupFormat.APP_ID -> Rejection.NOT_ICDETECTION
        manifest.appVersion.isBlank() -> Rejection.MISSING_VERSION
        !isThreeZeroOrLater(manifest.appVersion) -> Rejection.CAMPAIGN_VERSION
        manifest.schemaVersion < BackupFormat.MIN_SCHEMA -> Rejection.UNSUPPORTED_SCHEMA
        manifest.schemaVersion > currentSchema -> Rejection.FUTURE_SCHEMA
        manifest.databaseBytes <= 0 || manifest.databaseBytes > BackupFormat.MAX_DATABASE_BYTES -> Rejection.TOO_LARGE
        else -> null
    }

    /**
     * Versión base (antes de `-` y de `+`) de tres números, 3.0.0 o mayor. Lo que va detrás (`-beta4`,
     * `+commit`) no cuenta: todas las betas de la 3.0 son 3.0. Cualquier otra forma, no.
     */
    fun isThreeZeroOrLater(appVersion: String): Boolean {
        val base = appVersion.trim().substringBefore('+').substringBefore('-')
        val parts = base.split('.')
        if (parts.size != 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return false
        val major = parts[0].toIntOrNull() ?: return false
        if (parts.drop(1).any { it.toIntOrNull() == null }) return false
        // 3.x.y y posteriores (y 4.0.0…). Una 2.x nunca, aunque sea 2.99.99.
        return major >= 3
    }
}
