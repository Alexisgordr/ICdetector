package com.alexisgordr.icdetector.forensics

/** 3.0 — Nota de privacidad del ZIP de Stable-Site. Sin Android, para poder probarla en la JVM. */
object StableSiteExportNote {
    /**
     * 3.0 — Nota de privacidad que viaja dentro del ZIP, como `privacyNote` en topología y Geometry.
     * Las claves de sitio NO son anónimas: son un hash determinista y sin sal de una celda de una
     * cuadrícula de ~500 m, así que cualquiera puede recalcular las claves de una zona y emparejarlas.
     */
    const val PRIVACY_NOTE_FILE = "PRIVACY.txt"
    val PRIVACY_NOTE = """
        ICdetection - Stable-Site export: privacy note

        Site keys (site_key) are not anonymous.
        - Each key is a deterministic, unsalted SHA-256 hash (first 10 bytes) of a cell of a fixed
          ~500 m grid. Four overlapping grids are used, so one place can have up to four keys.
        - The same place gives the same key on every phone and in every export.
        - Anyone can compute the keys of every grid cell in an area (a candidate search) and match
          them, so a key can be linked to an approximate location (about 500 m).

        The rest of the files can also reveal habits:
        - site_cells.csv and shadow_episodes.csv contain complete cell identities;
        - first/last-seen times, days, observation counts and motion bands show when and how often
          you were at each place.

        No exact coordinates, IMSI, IMEI, phone number or credentials are included.
        Treat this ZIP as sensitive location and routine data and review it before sharing it.
    """.trimIndent() + "\n"

    /** Contenido del ZIP: la nota primero y después los CSV, sin cambiarlos. Pura, para las pruebas. */
    fun withPrivacyNote(files: Map<String, String>): LinkedHashMap<String, String> =
        linkedMapOf(PRIVACY_NOTE_FILE to PRIVACY_NOTE).apply { putAll(files) }
}
