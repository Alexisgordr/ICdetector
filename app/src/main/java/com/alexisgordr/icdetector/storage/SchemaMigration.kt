package com.alexisgordr.icdetector.storage

/**
 * 3.1.0 (#47) — Qué error de una migración se tolera: solo «duplicate column», una columna que ya
 * existe (instalación parcial o una migración que se repite). Cualquier otro fallo (tabla que no
 * existe, disco lleno, base corrupta) se propaga: seguir con un esquema a medias haría fallar en
 * silencio cada escritura después, que es peor que no abrir la base de datos.
 *
 * Las migraciones 7-19 se tragaban cualquier excepción; desde la 20 ya solo toleraban esta. Puro:
 * sirve igual para el SQLite de Android y para sqlite-jdbc en las pruebas.
 */
internal object SchemaMigration {
    fun isDuplicateColumn(error: Throwable): Boolean =
        error.message?.contains("duplicate column", ignoreCase = true) == true

    inline fun tolerateDuplicateColumn(execute: () -> Unit) {
        try {
            execute()
        } catch (e: Exception) {
            if (!isDuplicateColumn(e)) throw e
        }
    }
}
