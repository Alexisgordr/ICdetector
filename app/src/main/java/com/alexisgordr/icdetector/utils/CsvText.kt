package com.alexisgordr.icdetector.utils

/**
 * 3.0 — Escritura compartida de campos CSV para todos los exportadores.
 *
 * Varios campos llegan de la red o de fuentes externas sin control de la app (nombre de red,
 * nombre CSG, PLMN, identidades del módem) y una hoja de cálculo puede interpretar como FÓRMULA un
 * texto que empieza por `=`, `+`, `-` o `@` (o por un carácter de control seguido de uno de ellos).
 * [guard] antepone un apóstrofo (`'`) a esos textos para que se lean como texto.
 *
 * Reglas, en un solo sitio:
 *  - Solo se protege un texto que empieza por `=`, `+`, `-`, `@` (o sus variantes de ancho completo),
 *    por un carácter de control (tabulador, retorno, salto de línea…) o por `'`.
 *  - Un número literal (`-95`, `-12.5`, `40.4168`, `-1.0E-5`) NO se toca: dBm, RSRQ, coordenadas
 *    y demás números negativos legítimos salen igual que antes.
 *  - El apóstrofo es el único cambio y es reversible: si un campo de texto empieza por `'`, quitar
 *    exactamente UNO devuelve el valor original (por eso un valor que ya empezaba por `'` también
 *    lo recibe).
 *  - Las comillas, comas y saltos de línea se siguen escapando según RFC 4180 DESPUÉS de proteger.
 *
 * No es una protección universal: lo que haga cada hoja de cálculo depende del programa, de su
 * configuración y de cómo se importe el fichero (abrir con doble clic, asistente de importación,
 * columnas marcadas como texto…). La base de datos y los JSON de evidencia no se modifican: esto
 * solo afecta al texto escrito en los CSV.
 */
object CsvText {

    const val TEXT_GUARD = '\''

    private val FORMULA_START = setOf('=', '+', '-', '@', '＝', '＋', '－', '＠', TEXT_GUARD)
    private val PLAIN_NUMBER = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""")

    /** [value] listo para escribirse como texto en un CSV (sin escapar todavía). */
    fun guard(value: String): String {
        if (value.isEmpty()) return value
        val first = value[0]
        val risky = first in FORMULA_START || Character.isISOControl(first)
        if (!risky || PLAIN_NUMBER.matches(value)) return value
        return TEXT_GUARD + value
    }

    /** Campo con comillas solo cuando hace falta (historial, eventos de servicio, Stable-Site). */
    fun field(value: Any?): String {
        val s = guard(value?.toString().orEmpty())
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) quote(s) else s
    }

    /** Campo siempre entre comillas (caso forense, topología, Geometry). */
    fun quoted(value: Any?): String = quote(guard(value?.toString().orEmpty()))

    private fun quote(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""
}
