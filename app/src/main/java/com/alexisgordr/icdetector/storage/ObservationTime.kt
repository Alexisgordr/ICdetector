package com.alexisgordr.icdetector.storage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 3.0 (#23) — Instante inequívoco de cada observación.
 *
 * Hasta 2.10.x las horas se guardaban solo como texto local `yyyy-MM-dd HH:mm:ss`, sin zona.
 * Eso tiene dos fallos: al viajar entre zonas cambia la edad que se calcula, y en el cambio de
 * hora de otoño la misma hora se repite (dos instantes distintos con el mismo texto), así que
 * el orden dentro de esa hora queda indefinido.
 *
 * Desde 3.0 cada fila nueva guarda además el instante en milisegundos desde epoch (UTC). Las
 * ventanas, caducidades, poda y ordenaciones usan ese instante.
 *
 * LAS FILAS ANTIGUAS NO SE REINTERPRETAN. Su zona nunca se guardó y no siempre se puede
 * reconstruir, así que su instante queda en NULL ("desconocido"). Para no perder el historial
 * de la campaña, esas filas siguen usando la misma comparación aproximada sobre el texto que
 * usaban antes; no se les inventa un instante ni se exporta uno.
 *
 * El texto local se sigue escribiendo: es lo que se muestra y define el "día" de las
 * evidencias por días.
 */
object ObservationTime {

    const val LEGACY_PATTERN = "yyyy-MM-dd HH:mm:ss"

    /** Texto local con el formato histórico. Solo para mostrar y para comparar filas antiguas. */
    fun localText(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat(LEGACY_PATTERN, Locale.ROOT).apply { timeZone = zone }.format(Date(epochMs))

    /** Instante en UTC, ISO-8601 con milisegundos (`2026-10-25T01:30:00.000Z`). Para exportar. */
    fun utcIso(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMs))

    /** Lee un texto local antiguo con la zona indicada. Null si no se puede leer. */
    fun parseLegacy(text: String?, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            SimpleDateFormat(LEGACY_PATTERN, Locale.ROOT).apply { timeZone = zone; isLenient = false }
                .parse(text)?.time
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Condición SQL "observada en o después del corte". Usa dos parámetros, en este orden:
     * el corte en milisegundos y el mismo corte como texto local ([cutoffArgs]).
     */
    fun sinceClause(msColumn: String, textColumn: String, strict: Boolean = false): String {
        val op = if (strict) ">" else ">="
        return "($msColumn$op? OR ($msColumn IS NULL AND $textColumn$op?))"
    }

    /** Condición SQL "observada antes del corte". Mismos dos parámetros que [sinceClause]. */
    fun beforeClause(msColumn: String, textColumn: String): String =
        "($msColumn<? OR ($msColumn IS NULL AND $textColumn<?))"

    /** Condición SQL "observada en o antes del corte". Mismos dos parámetros que [sinceClause]. */
    fun untilClause(msColumn: String, textColumn: String): String =
        "($msColumn<=? OR ($msColumn IS NULL AND $textColumn<=?))"

    /**
     * Los dos parámetros de [sinceClause] / [beforeClause] / [untilClause] para un corte.
     *
     * Estas condiciones no se deben negar con `NOT`: en una fila antigua la primera mitad vale
     * NULL y `NOT (NULL OR FALSE)` también es NULL, que la descartaría. Para un intervalo se
     * combinan [sinceClause] y [untilClause].
     */
    fun cutoffArgs(cutoffMs: Long, zone: TimeZone = TimeZone.getDefault()): Array<String> =
        arrayOf(cutoffMs.toString(), localText(cutoffMs, zone))

    /**
     * Orden cronológico ascendente. Las filas antiguas (sin instante) son siempre anteriores a
     * la migración, así que van primero, ordenadas por su texto como antes; las nuevas, por su
     * instante. El id desempata.
     */
    fun orderAscending(msColumn: String, textColumn: String, idColumn: String): String =
        "($msColumn IS NOT NULL) ASC, $msColumn ASC, $textColumn ASC, $idColumn ASC"

    /**
     * Mejor estimación disponible del instante de una fila: el instante guardado si existe; si
     * no, la lectura aproximada del texto antiguo, igual que antes de 3.0. Null si no hay nada.
     */
    fun bestEffortMs(epochMs: Long?, legacyText: String?, zone: TimeZone = TimeZone.getDefault()): Long? =
        epochMs ?: parseLegacy(legacyText, zone)

    /** ¿La fila es posterior al corte? Mismo criterio que [sinceClause], pero en memoria. */
    fun isAfter(epochMs: Long?, legacyText: String?, cutoffMs: Long, zone: TimeZone = TimeZone.getDefault()): Boolean =
        if (epochMs != null) epochMs > cutoffMs
        else !legacyText.isNullOrEmpty() && legacyText > localText(cutoffMs, zone)
}
