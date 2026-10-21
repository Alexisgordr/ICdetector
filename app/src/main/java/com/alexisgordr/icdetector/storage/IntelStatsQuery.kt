package com.alexisgordr.icdetector.storage

import com.alexisgordr.icdetector.core.ScoreThreshold
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.SUBTHRESHOLD_PREFIX
import com.alexisgordr.icdetector.models.StoredReasons

/** 3.0 (UI1) — Cifras del panel de estadísticas, ya agregadas. Ninguna lista de filas. */
data class IntelStats(
    /** Filas del historial (observaciones). */
    val observations: Long,
    /** Identidades completas distintas (MCC-MNC-área-CID-tecnología). */
    val cells: Long,
    val averageScore: Double,
    /** Celdas con alguna observación por debajo del umbral (score < 70). NO son alarmas confirmadas. */
    val lowScoreCells: Long,
    /** Observaciones (no celdas) con latitud y longitud. */
    val observationsWithGps: Long,
    /** Celdas con algún motivo guardado que no sea limpio ni sub-umbral (incluye alarmas en confirmación). */
    val cellsWithReason: Long,
    /** Celda más observada y su número de observaciones; empate: la que aparece antes en el historial. */
    val mostSeen: Pair<String, Long>?,
    /** Hasta 3 tipos de red con su número de observaciones; empate: el que aparece antes. Null = sin dato. */
    val networkTypes: List<Pair<String?, Long>>,
    /** Fecha (10 primeros caracteres) de la observación más antigua y de la más reciente. */
    val dateFrom: String?,
    val dateTo: String?
)

/**
 * 3.0 (UI1) — Estadísticas del historial con consultas agregadas SQL. Antes IntelPanel leía TODO
 * el historial (`getRecords()` sin límite) y agrupaba las filas en memoria, en Compose.
 *
 * Semántica idéntica a la de entonces (comprobada contra una réplica del cálculo anterior):
 *  - Identidad completa `mcc-mnc-tac-cid-RADIO`; un texto nulo cuenta como "null" (como la
 *    plantilla de Kotlin) y una tecnología desconocida o inválida como UNKNOWN.
 *  - Score nulo = 0 y no entero truncado (como `Cursor.getInt`).
 *  - «Con motivo»: motivo no en blanco (los mismos espacios que `isBlank` de Kotlin), distinto de
 *    "OK" y que no empiece por el prefijo sub-umbral. Las alarmas aún en confirmación cuentan.
 *  - Orden del historial `observed_at_ms DESC, id DESC` (nulos al final) para los empates y las fechas.
 * Diferencia deliberada: una fila con un campo de texto de identidad, fecha o red NULL hacía fallar
 * la lectura anterior, que se quedaba con las filas leídas hasta ahí (y descartaba en silencio esa
 * fila y todas las más antiguas). Aquí cuentan todas.
 *
 * Sin funciones de ventana: Android 10 (minSdk 29) trae SQLite 3.22. Sin parámetros: todo lo que
 * entra en el SQL son constantes del código.
 */
object IntelStatsQuery {
    // Mismos nombres que CellDbHelper (comprobado por IntelStatsQueryTest).
    const val TABLE = "history"
    private const val ID = "id"
    private const val TIMESTAMP = "timestamp"
    private const val NET_TYPE = "net_type"
    private const val CID = "cid"
    private const val MNC = "mnc"
    private const val TAC = "tac"
    private const val MCC = "mcc"
    private const val SCORE = "score"
    private const val FAILED = "failed_heuristics"
    private const val LAT = "lat"
    private const val LON = "lon"
    private const val RADIO = "radio"
    private const val OBSERVED = "observed_at_ms"

    fun interface Rows {
        /** Ejecuta [sql] (sin parámetros) y devuelve sus filas: Long, Double, String o null. */
        fun query(sql: String): List<List<Any?>>
    }

    private fun lit(s: String) = "'" + s.replace("'", "''") + "'"

    /** Los caracteres que `Char.isWhitespace` de Kotlin considera blancos, para TRIM. */
    private val BLANKS: String = (0..0xFFFF).filter { it.toChar().isWhitespace() }.joinToString(",", "char(", ")")

    private fun text(col: String) = "IFNULL(CAST($col AS TEXT),'null')"

    private val RADIO_EXPR = "(CASE WHEN $RADIO IN (${RadioTech.entries.joinToString(",") { lit(it.name) }}) " +
        "THEN $RADIO ELSE ${lit(RadioTech.UNKNOWN.name)} END)"

    /** Misma cadena que `HistoryRecord.identityKey`. */
    val IDENTITY_SQL = "${text(MCC)}||'-'||${text(MNC)}||'-'||${text(TAC)}||'-'||${text(CID)}||'-'||$RADIO_EXPR"

    private val SCORE_EXPR = "CAST(IFNULL($SCORE,0) AS INTEGER)"

    private val WITH_REASON = "(TRIM(IFNULL($FAILED,''), $BLANKS) <> '' AND IFNULL($FAILED,'') <> ${lit(StoredReasons.CLEAN)} " +
        "AND substr(IFNULL($FAILED,''),1,${SUBTHRESHOLD_PREFIX.length}) <> ${lit(SUBTHRESHOLD_PREFIX)})"

    val SUMMARY_SQL = """
        SELECT COUNT(*), COUNT(DISTINCT ident), AVG(sc),
          COUNT(DISTINCT CASE WHEN sc < ${ScoreThreshold.SUSPICIOUS_BELOW} THEN ident END),
          IFNULL(SUM(CASE WHEN $LAT IS NOT NULL AND $LON IS NOT NULL THEN 1 ELSE 0 END),0),
          COUNT(DISTINCT CASE WHEN reasoned THEN ident END)
        FROM (SELECT $IDENTITY_SQL AS ident, $SCORE_EXPR AS sc, $LAT, $LON, $WITH_REASON AS reasoned FROM $TABLE)
    """.trimIndent()

    /**
     * Grupos de [keyExpr] por número de filas; empate: el grupo cuya fila más reciente (orden del
     * historial) va antes, igual que `groupBy` + orden estable sobre la lista NEWEST_FIRST.
     */
    private fun rankedGroupsSql(keyExpr: String, limit: Int) = """
        WITH b AS (SELECT $keyExpr AS k, $OBSERVED AS obs, $ID AS rid FROM $TABLE),
        c AS (SELECT k, COUNT(*) AS n, MAX(obs) AS mo FROM b GROUP BY k),
        t AS (SELECT b.k AS k, MAX(b.rid) AS mid FROM b JOIN c ON b.k IS c.k
              AND (b.obs = c.mo OR (c.mo IS NULL AND b.obs IS NULL)) GROUP BY b.k)
        SELECT c.k, c.n FROM c JOIN t ON c.k IS t.k
        ORDER BY c.n DESC, (c.mo IS NULL) ASC, c.mo DESC, t.mid DESC LIMIT $limit
    """.trimIndent()

    val MOST_SEEN_SQL = rankedGroupsSql(IDENTITY_SQL, 1)
    val NETWORK_TYPES_SQL = rankedGroupsSql(NET_TYPE, 3)

    /** La más reciente es la primera del orden del historial; la más antigua, la última. */
    val NEWEST_DATE_SQL = "SELECT substr($TIMESTAMP,1,10) FROM $TABLE ORDER BY $OBSERVED DESC, $ID DESC LIMIT 1"
    val OLDEST_DATE_SQL = "SELECT substr($TIMESTAMP,1,10) FROM $TABLE ORDER BY $OBSERVED ASC, $ID ASC LIMIT 1"

    /**
     * Ejecuta las consultas; [checkpoint] se llama entre ellas (cancelación). Null si no hay filas.
     */
    fun load(rows: Rows, checkpoint: () -> Unit = {}): IntelStats? {
        val s = rows.query(SUMMARY_SQL).single()
        val observations = (s[0] as Number).toLong()
        if (observations == 0L) return null
        checkpoint()
        val most = rows.query(MOST_SEEN_SQL).firstOrNull()
        checkpoint()
        val nets = rows.query(NETWORK_TYPES_SQL).map { (it[0] as String?) to (it[1] as Number).toLong() }
        checkpoint()
        val newest = rows.query(NEWEST_DATE_SQL).firstOrNull()?.get(0) as String?
        val oldest = rows.query(OLDEST_DATE_SQL).firstOrNull()?.get(0) as String?
        fun n(i: Int) = (s[i] as Number?)?.toLong() ?: 0L
        return IntelStats(
            observations = observations,
            cells = n(1),
            averageScore = (s[2] as Number).toDouble(),
            lowScoreCells = n(3),
            observationsWithGps = n(4),
            cellsWithReason = n(5),
            mostSeen = most?.let { (it[0] as String) to (it[1] as Number).toLong() },
            networkTypes = nets,
            dateFrom = oldest,
            dateTo = newest
        )
    }
}
