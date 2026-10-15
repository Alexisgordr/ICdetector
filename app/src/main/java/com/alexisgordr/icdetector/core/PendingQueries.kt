package com.alexisgordr.icdetector.core

/**
 * 3.0 (V4) — Qué consulta es dueña del PENDING de cada celda. Al descartar una consulta antigua
 * (la configuración cambió) se borraba el PENDING de la celda sin mirar de quién era: si ya había
 * empezado otra consulta de esa celda con los ajustes nuevos, se le borraba el suyo. Ahora cada
 * consulta reclama un número y solo libera lo que sigue siendo suyo. Seguro entre hilos.
 */
class PendingQueries {
    private val owners = HashMap<String, Long>()
    private var next = 0L

    /** La consulta nueva de [key] pasa a ser la dueña; devuelve su número. */
    @Synchronized fun claim(key: String): Long = (++next).also { owners[key] = it }

    @Synchronized fun owns(key: String, query: Long): Boolean = owners[key] == query

    /** Libera [key] si [query] sigue siendo su dueña; si no, no toca nada y devuelve false. */
    @Synchronized fun release(key: String, query: Long): Boolean =
        (owners[key] == query).also { if (it) owners.remove(key) }

    @Synchronized fun clear() = owners.clear()
}
