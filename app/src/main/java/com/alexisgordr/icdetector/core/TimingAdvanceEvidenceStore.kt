package com.alexisgordr.icdetector.core

/**
 * 3.1.0 (#43) — Dónde se guarda la evidencia de [TimingAdvanceSanity] entre arranques, y la
 * migración de lo que guardaban versiones anteriores. Puro: las preferencias llegan por [Prefs]
 * (en el servicio, SharedPreferences; en las pruebas, un mapa en memoria).
 *
 * - 3.1.0: una clave por unidad, `ta_window_<unidad>`, con [TimingAdvanceSanity.summary].
 * - 3.0 (#33): `ta_seen_real_<unidad>` (el latch) y `ta_zero_cells_<unidad>`. Las celdas a cero se
 *   migran a la ventana ([TimingAdvanceSanity.restoreLegacy]); el latch NO, para que un valor
 *   espurio de entonces no fuerce REAL. Las dos se borran.
 * - Antes de 3.0: `ta_seen_real_value` y `ta_zero_only_cells`, para todo el teléfono. No dicen de
 *   qué tecnología vinieron: no se leen, solo se borran.
 */
class TimingAdvanceEvidenceStore(private val prefs: Prefs) {

    interface Prefs {
        fun getString(key: String): String?
        fun getStringSet(key: String): Set<String>?
        fun contains(key: String): Boolean
        /** Escribe [put] y borra [remove] en una sola edición. */
        fun write(put: Map<String, String>, remove: Set<String>)
    }

    /** Restaura [sanity], migra la evidencia anterior y borra sus claves. True si alguna unidad es STUB. */
    fun restore(sanity: TimingAdvanceSanity): Boolean {
        val obsolete = LinkedHashSet<String>()
        TimingAdvanceSanity.EVIDENCE_UNITS.forEach { unit ->
            val window = prefs.getString(WINDOW_PREFIX + unit.name)
            if (window != null) {
                sanity.restore(unit, window)
            } else {
                prefs.getStringSet(ZERO_CELLS_PREFIX + unit.name)?.takeIf { it.isNotEmpty() }?.let { sanity.restoreLegacy(unit, it) }
            }
            listOf(SEEN_REAL_PREFIX + unit.name, ZERO_CELLS_PREFIX + unit.name).filterTo(obsolete, prefs::contains)
        }
        LEGACY_GLOBAL_KEYS.filterTo(obsolete, prefs::contains)
        if (obsolete.isNotEmpty()) prefs.write(summaries(sanity), obsolete)
        return sanity.anyStub
    }

    /** Guarda la ventana de cada unidad. */
    fun persist(sanity: TimingAdvanceSanity) = prefs.write(summaries(sanity), emptySet())

    private fun summaries(sanity: TimingAdvanceSanity): Map<String, String> =
        TimingAdvanceSanity.EVIDENCE_UNITS.associate { WINDOW_PREFIX + it.name to sanity.summary(it) }

    companion object {
        const val WINDOW_PREFIX = "ta_window_"
        const val SEEN_REAL_PREFIX = "ta_seen_real_"
        const val ZERO_CELLS_PREFIX = "ta_zero_cells_"
        val LEGACY_GLOBAL_KEYS = setOf("ta_seen_real_value", "ta_zero_only_cells")
    }
}
