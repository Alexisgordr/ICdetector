package com.alexisgordr.icdetector.storage

/**
 * 3.1.0 (#47) — La base de datos es de una versión más nueva de ICdetection (por ejemplo, tras
 * volver a instalar una versión anterior sin borrar los datos). SQLiteOpenHelper lanzaba por
 * defecto una SQLiteException genérica («Can't downgrade database…») al abrir; ahora [CellDbHelper]
 * lanza esta, con las dos versiones, y [DatabaseGate] la convierte en un estado que la app enseña
 * en un diálogo localizado. El mensaje es solo para el log. No se modifica nada: ni se borra ni se
 * intenta adaptar un esquema que esta versión no conoce.
 */
class DatabaseDowngradeException(val storedVersion: Int, val supportedVersion: Int) : IllegalStateException(
    "Database schema $storedVersion is newer than the supported $supportedVersion; nothing was changed."
) {
    companion object {
        /** La misma comprobación que hace SQLiteOpenHelper antes de llamar a onDowngrade. */
        fun requireSupported(storedVersion: Int, supportedVersion: Int) {
            if (storedVersion > supportedVersion) throw DatabaseDowngradeException(storedVersion, supportedVersion)
        }
    }
}
