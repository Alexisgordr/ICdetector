package com.alexisgordr.icdetector.core.profile

import com.alexisgordr.icdetector.models.RadioTech

/**
 * 3.0 — Consulta de las evaluaciones del perfil local ya guardadas (Historial → Perfil local). Pura:
 * sin Android ni base de datos.
 *
 * Solo lee lo que se guardó en su momento (`profile_evaluations` y la fila de historial enlazada).
 * Nunca recalcula una evaluación pasada ni usa el perfil actual como referencia histórica.
 */
object ProfileEvaluationQuery {
    /** Tamaño de página de la lista. */
    const val PAGE_SIZE = 50

    /** Filtro por nivel. [ALL] incluye observaciones sin desviación y abstenciones. */
    enum class LevelFilter(val levels: Set<DeviationLevel>) {
        ALL(DeviationLevel.entries.toSet()),
        DEVIATIONS(setOf(DeviationLevel.LOW, DeviationLevel.MODERATE, DeviationLevel.HIGH)),
        HIGH(setOf(DeviationLevel.HIGH)),
        MODERATE(setOf(DeviationLevel.MODERATE)),
        LOW(setOf(DeviationLevel.LOW)),
        NONE(setOf(DeviationLevel.NONE)),
        ABSTAIN(setOf(DeviationLevel.ABSTAIN))
    }

    /**
     * Posición en la lista para pedir la página siguiente (paginación por clave, estable aunque se
     * guarden evaluaciones nuevas mientras se consulta). Orden: instante de la observación (o de la
     * evaluación si la fila no lo tiene) y después id, ambos de más reciente a más antiguo.
     */
    data class PageCursor(val sortKeyMs: Long, val evaluationId: Long)

    fun cursorAfter(page: List<EvaluationEntry>): PageCursor? = page.lastOrNull()?.let { PageCursor(it.sortKeyMs, it.evaluationId) }

    /** ¿Hay más páginas? Se pide una fila de más para saberlo sin contar toda la tabla. */
    fun hasMore(fetched: Int, pageSize: Int = PAGE_SIZE) = fetched > pageSize

    private const val SORT_KEY = "COALESCE(h.observed_at_ms, e.evaluated_at_ms)"

    /**
     * Consulta de una página: evaluaciones con su fila de historial (LEFT JOIN: una evaluación sin
     * fila se muestra igualmente, con la observación como desconocida), filtradas por nivel, desde
     * [after] y con [limit] + 1 filas para saber si hay más. Solo lectura.
     */
    fun pageSql(filter: LevelFilter, after: PageCursor?, limit: Int = PAGE_SIZE): Pair<String, Array<String>> {
        val levels = filter.levels.map { it.name }.sorted()
        val args = levels.toMutableList()
        val where = StringBuilder("e.deviation_level IN (${levels.joinToString(",") { "?" }})")
        if (after != null) {
            // Android pasa los argumentos como texto, y en SQLite un número es siempre menor que un
            // texto: sin CAST, "< ?" sería siempre cierto y la página siguiente repetiría la primera.
            where.append(" AND ($SORT_KEY < CAST(? AS INTEGER) OR ($SORT_KEY = CAST(? AS INTEGER) AND e.id < CAST(? AS INTEGER)))")
            args += listOf(after.sortKeyMs.toString(), after.sortKeyMs.toString(), after.evaluationId.toString())
        }
        val sql = "SELECT e.id AS e_id, e.history_id, e.evaluated_at_ms, e.algorithm_version, e.profile_revision, " +
            "e.context_key, e.mode, e.deviation_level, e.deviation_score, e.metrics_evaluated, e.metrics_total, " +
            "e.evidence, e.abstain_reason, e.learned, e.holdout, h.id AS h_id, h.timestamp, h.observed_at_ms, " +
            "h.mcc, h.mnc, h.tac, h.cid, h.radio, h.dbm, h.rsrq, h.sinr, h.score, h.failed_heuristics, " +
            "h.not_evaluated_heuristics, h.location_mode, h.app_version " +
            "FROM profile_evaluations e LEFT JOIN history h ON h.id = e.history_id " +
            "WHERE $where ORDER BY $SORT_KEY DESC, e.id DESC LIMIT ${limit + 1}"
        return sql to args.toTypedArray()
    }

    /**
     * Identifica una petición de la lista: el filtro y una generación que aumenta con cada cambio de
     * filtro o reintento. Una respuesta solo se aplica si su ficha coincide con la actual: así una
     * página pedida con otro filtro (o antes de reintentar) nunca se mezcla con la lista nueva.
     */
    data class RequestToken(val filter: LevelFilter, val generation: Int)

    fun accepts(current: RequestToken, response: RequestToken): Boolean = current == response

    /** Añade una página sin repetir evaluaciones ya mostradas (la lista usa el id como clave). */
    fun append(existing: List<EvaluationEntry>, page: List<EvaluationEntry>): List<EvaluationEntry> {
        val seen = existing.mapTo(HashSet()) { it.evaluationId }
        return existing + page.filter { seen.add(it.evaluationId) }
    }

    /** Evaluaciones guardadas por nivel, para los filtros. */
    const val LEVEL_COUNTS_SQL = "SELECT deviation_level, COUNT(*) FROM profile_evaluations GROUP BY deviation_level"
}

/**
 * Una evaluación guardada y su observación. Los campos de la observación son null si la fila de
 * historial no existe o no los tenía (filas anteriores a 3.0): desconocido, nunca supuesto.
 */
data class EvaluationEntry(
    val evaluationId: Long,
    val historyId: Long,
    val evaluatedAtMs: Long,
    val algorithmVersion: Int,
    val profileRevision: String?,
    val contextKey: String,
    val mode: ProfileMode?,
    val level: DeviationLevel?,
    val score: Double?,
    val metricsEvaluated: Int,
    val metricsTotal: Int,
    val evidence: String?,
    val abstainReason: AbstainReason?,
    val learned: Boolean,
    val holdout: Boolean,
    val observation: HistoricalObservation?
) {
    /** Clave de orden: instante de la observación, o el de la evaluación si no se conoce. */
    val sortKeyMs: Long get() = observation?.observedAtMs ?: evaluatedAtMs
}

/** La fila de historial asociada, tal como se guardó. */
data class HistoricalObservation(
    val timestamp: String?,
    val observedAtMs: Long?,
    val mcc: String?,
    val mnc: String?,
    val tac: String?,
    val cid: String?,
    val radio: RadioTech,
    val dbm: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val score: Int?,
    val failedHeuristics: String?,
    val notEvaluatedHeuristics: String?,
    val locationMode: String?,
    val appVersion: String?
)

/** Valores de una métrica tal como quedaron en `evidence`: observado, referencia y desviación. */
data class MetricEvidence(val metric: ProfileMetric, val observed: Int, val mean: Double, val spread: Double, val deviation: Double)

/**
 * Lee el texto `evidence` guardado (`SIGNAL_DBM -60 vs -80.000±4.000 z=5.000`; el algoritmo 1 usaba un
 * decimal). Es la ÚNICA fuente de la referencia histórica: si una métrica no aparece, no hay
 * referencia guardada para ella.
 */
object EvidenceParser {
    private val PART = Regex("""([A-Z_]+) (-?\d+) vs (-?\d+(?:\.\d+)?)±(\d+(?:\.\d+)?) z=(\d+(?:\.\d+)?)""")

    fun parse(evidence: String?): List<MetricEvidence> =
        evidence.orEmpty().split(";").mapNotNull { part ->
            val m = PART.find(part.trim()) ?: return@mapNotNull null
            val (name, observed, mean, spread, z) = m.destructured
            val metric = ProfileMetric.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            MetricEvidence(metric, observed.toInt(), mean.toDouble(), spread.toDouble(), z.toDouble())
        }
}

/**
 * Resultado de H13 (línea base de señal) en una fila del historial, solo con lo que se conserva:
 *  - [NOT_EVALUATED]: H13 figura en `not_evaluated_heuristics` (filas 3.0 en adelante).
 *  - [FAILED]: su motivo aparece en el motivo guardado de la fila.
 *  - [NO_FAILURE_RECORDED]: se evaluó y su motivo no aparece. NO se afirma que pasara: el motivo
 *    guardado puede sustituirse después (por ejemplo, por una contradicción de confianza local).
 *  - [UNKNOWN]: fila anterior a 3.0 (sin columna de reglas no evaluadas) o sin observación.
 *
 * H13 y el perfil local miran la misma señal de la misma celda: que coincidan no es una
 * confirmación independiente.
 */
enum class H13History {
    FAILED, NOT_EVALUATED, NO_FAILURE_RECORDED, UNKNOWN;

    companion object {
        /** Textos con que ThreatAnalyzer anota un fallo de H13 (potencia y huella RF). */
        val FAILURE_MARKERS = listOf("Potencia anómala vs historial", "Huella RF incoherente con el historial")

        fun of(observation: HistoricalObservation?): H13History {
            if (observation == null) return UNKNOWN
            val reason = observation.failedHeuristics.orEmpty()
            if (FAILURE_MARKERS.any { reason.contains(it) }) return FAILED
            val notEvaluated = observation.notEvaluatedHeuristics ?: return UNKNOWN
            return if (notEvaluated.split(";").any { it.trim() == "H13" }) NOT_EVALUATED else NO_FAILURE_RECORDED
        }
    }
}
