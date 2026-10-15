package com.alexisgordr.icdetector.core

/**
 * 3.0 — Identificador de la compilación que se guarda en `AppVersion` de cada fila y en las
 * exportaciones: `versionName+commit`, por ejemplo `3.0.0-beta4+6bb5ed3`.
 *
 * Bug found during testing: todas las compilaciones de una beta comparten `versionName` y
 * `versionCode`, así que las filas grabadas con builds distintas (antes y después de un cambio de
 * reglas o del GPS) no se podían separar. El commit lo pone Gradle al compilar (`BUILD_COMMIT`):
 *  - desde el repositorio: el commit corto, con `-dirty` si había cambios sin commitear;
 *  - desde un ZIP hecho con `git archive`: el commit del fichero `BUILD_COMMIT` (export-subst);
 *  - si no hay forma de saberlo: `nogit`.
 * Lo que va detrás del `+` es metadato de compilación: no cambia la versión ni su orden.
 */
object AppBuildId {
    const val NO_GIT = "nogit"
    private val COMMIT = Regex("[0-9a-f]{7,40}(-dirty)?")

    fun format(versionName: String?, commit: String?): String? {
        val version = versionName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val build = commit?.trim()?.takeIf { COMMIT.matches(it) } ?: NO_GIT
        return "$version+$build"
    }

    /** El de esta compilación. */
    fun current(versionName: String?): String? =
        format(versionName, com.alexisgordr.icdetector.BuildConfig.BUILD_COMMIT)
}
