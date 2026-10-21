package com.alexisgordr.icdetector.storage

/**
 * 3.1.0 (#47) — La base de datos es de una versión más nueva de ICdetection (por ejemplo, tras
 * volver a instalar una versión anterior sin borrar los datos). SQLiteOpenHelper lanzaba por
 * defecto una SQLiteException genérica («Can't downgrade database…») al abrir; ahora [CellDbHelper]
 * falla con este mensaje, que dice qué pasa y qué hacer. No se modifica nada: ni se borra ni se
 * intenta adaptar un esquema que esta versión no conoce.
 */
class DatabaseDowngradeException(val storedVersion: Int, val supportedVersion: Int) : IllegalStateException(
    "La base de datos es de una versión más nueva de ICdetection (esquema $storedVersion; esta versión usa el " +
        "$supportedVersion). No se ha modificado nada. Instala esa versión o una posterior, o borra los datos de " +
        "la app y restaura una copia de seguridad hecha con esta versión."
)
