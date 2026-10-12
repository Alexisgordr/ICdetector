package com.alexisgordr.icdetector.storage

/**
 * 3.0 — Migración a esquema 23. Aditiva: tres columnas en [SchemaV22.TABLE_PROFILE], tras la revisión
 * técnica del perfil local (637761d). No toca el historial ni ninguna otra tabla.
 *
 *  - `generation`: identidad de cada vida de una referencia. Tras reiniciar, caducar o reaprender,
 *    la revisión vuelve a empezar; la generación distingue las dos referencias (F04).
 *  - `day_start_variance`: varianza al empezar el día, para acotar cuánto crece la dispersión (F02).
 *  - `last_seen_ms`: última muestra procesada, aprendida o en cuarentena, para no contar dos veces
 *    la misma (F09).
 *
 * Las filas del esquema 22 quedan con NULL: generación `legacy`, sin dispersión inicial ni muestra
 * procesada conocida. Sin dependencias de Android, como [SchemaV22].
 */
object SchemaV23 {

    const val VERSION = 23

    /** Sentencias en orden; ver [CellDbHelper.onUpgrade]. */
    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE ${SchemaV22.TABLE_PROFILE} ADD COLUMN generation TEXT",
        "ALTER TABLE ${SchemaV22.TABLE_PROFILE} ADD COLUMN day_start_variance REAL",
        "ALTER TABLE ${SchemaV22.TABLE_PROFILE} ADD COLUMN last_seen_ms INTEGER"
    )
}
