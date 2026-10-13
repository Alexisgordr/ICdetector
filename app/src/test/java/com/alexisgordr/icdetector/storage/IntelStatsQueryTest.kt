package com.alexisgordr.icdetector.storage

import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.SUBTHRESHOLD_PREFIX
import com.alexisgordr.icdetector.models.StoredReasons
import com.alexisgordr.icdetector.models.VerificationStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.random.Random

/**
 * 3.0 (UI1) — Las consultas agregadas dan EXACTAMENTE las cifras que calculaba IntelPanel sobre la
 * lista completa: se compara con una réplica literal de aquel cálculo (misma lectura de filas que
 * `readRecord`, mismo `groupBy`, mismos filtros y desempates) en SQLite real, con nulls, varias
 * tecnologías, motivos sub-umbral, alarmas en confirmación y empates.
 */
class IntelStatsQueryTest {
    private lateinit var db: Connection

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.execute("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT, net_type TEXT, cid TEXT, mnc TEXT, " +
                "tac TEXT, mcc TEXT, dbm INTEGER, verified TEXT, score INTEGER DEFAULT 100, failed_heuristics TEXT, lat REAL, lon REAL, " +
                "radio TEXT, observed_at_ms INTEGER)")
        }
    }

    @After fun close() = db.close()

    private val rows = IntelStatsQuery.Rows { sql ->
        db.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val out = mutableListOf<List<Any?>>()
                val n = rs.metaData.columnCount
                while (rs.next()) out += (1..n).map { rs.getObject(it)?.let { v -> if (v is Int) v.toLong() else v } }
                out
            }
        }
    }

    private data class Row(val ts: String?, val net: String?, val cid: String?, val mnc: String?, val tac: String?, val mcc: String?,
                           val verified: String?, val score: Any?, val failed: String?, val lat: Double?, val lon: Double?,
                           val radio: String?, val observed: Long?)

    private fun insert(r: Row) {
        db.prepareStatement("INSERT INTO history (timestamp, net_type, cid, mnc, tac, mcc, dbm, verified, score, failed_heuristics, lat, lon, radio, observed_at_ms) VALUES (?,?,?,?,?,?,-90,?,?,?,?,?,?,?)").use { p ->
            listOf(r.ts, r.net, r.cid, r.mnc, r.tac, r.mcc, r.verified, r.score, r.failed, r.lat, r.lon, r.radio, r.observed)
                .forEachIndexed { i, v -> p.setObject(i + 1, v) }
            p.executeUpdate()
        }
    }

    // ── Réplica literal del cálculo anterior de IntelPanel ─────────────────────────────────
    private data class Rec(val timestamp: String, val netType: String, val identityKey: String, val verified: VerificationStatus,
                           val score: Int, val failedHeuristics: String, val lat: Double?, val lon: Double?)

    /** getRecords(): orden NEWEST_FIRST, y readRecord con su NPE en textos nulos (se corta ahí). */
    private fun legacyRecords(): List<Rec> {
        val out = mutableListOf<Rec>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT * FROM history ORDER BY observed_at_ms DESC, id DESC").use { rs ->
                while (rs.next()) {
                    // readRecord: un texto nulo en un campo no nulo lanza NPE → getRecords devuelve lo leído.
                    val texts = listOf("timestamp", "net_type", "cid", "mnc", "tac", "mcc").map { rs.getString(it) }
                    if (texts.any { it == null }) break
                    val (ts, net, cid, mnc, tac) = texts.map { it!! }
                    val mcc = texts[5]!!
                    val radio = rs.getString("radio")?.let { runCatching { RadioTech.valueOf(it) }.getOrNull() } ?: RadioTech.UNKNOWN
                    val verified = runCatching { VerificationStatus.valueOf(rs.getString("verified")) }.getOrDefault(VerificationStatus.PENDING)
                    val score = rs.getInt("score")
                    val lat = rs.getDouble("lat").takeUnless { rs.wasNull() }
                    val lon = rs.getDouble("lon").takeUnless { rs.wasNull() }
                    out += Rec(ts, net, "$mcc-$mnc-$tac-$cid-${radio.name}", verified, score, rs.getString("failed_heuristics") ?: "", lat, lon)
                }
            }
        }
        return out
    }

    private fun legacy(): IntelStats? {
        val items = legacyRecords()
        if (items.isEmpty()) return null
        val groups = LinkedHashMap<String, MutableList<Rec>>()
        items.forEach { groups.getOrPut(it.identityKey) { mutableListOf() }.add(it) }
        val grouped = groups.entries.map { it.key to it.value }
        return IntelStats(
            observations = items.size.toLong(),
            cells = grouped.size.toLong(),
            averageScore = items.asSequence().map { it.score }.average(),
            lowScoreCells = grouped.count { (_, r) -> r.any { it.score < 70 } }.toLong(),
            verifiedCells = grouped.count { (_, r) -> r.any { it.verified.name == "VERIFIED" } }.toLong(),
            notFoundCells = grouped.count { (_, r) -> r.any { it.verified.name == "NOT_FOUND" } }.toLong(),
            observationsWithGps = items.count { it.lat != null && it.lon != null }.toLong(),
            cellsWithReason = grouped.count { (_, r) -> r.any { it.failedHeuristics.isNotBlank() && it.failedHeuristics != "OK" && !it.failedHeuristics.startsWith(SUBTHRESHOLD_PREFIX) } }.toLong(),
            mostSeen = grouped.maxByOrNull { it.second.size }?.let { it.first to it.second.size.toLong() },
            networkTypes = items.groupBy { it.netType }.mapValues { it.value.size }.entries.sortedByDescending { it.value }.take(3)
                .map { it.key to it.value.toLong() },
            dateFrom = items.lastOrNull()?.timestamp?.take(10),
            dateTo = items.firstOrNull()?.timestamp?.take(10)
        )
    }

    private fun assertSame(expected: IntelStats?, actual: IntelStats?) {
        if (expected == null || actual == null) { assertEquals(expected, actual); return }
        assertEquals(expected.averageScore, actual.averageScore, 1e-9)
        assertEquals(expected.copy(averageScore = 0.0), actual.copy(averageScore = 0.0))
    }

    private val reasons = listOf(null, "", "   ", "\t\n", " ", "  ", "OK", "ok", "${SUBTHRESHOLD_PREFIX} Vecinos fantasma",
        "[sub-umbral]", "[sub-umbra", "${StoredReasons.confirmingPrefix(2, 3)} Celda aislada", "Celda aislada",
        "Salto potencia${StoredReasons.SEPARATOR}Desviación TAC", " ${SUBTHRESHOLD_PREFIX} con espacio delante")

    private fun randomRow(r: Random) = Row(
        ts = "2026-0${r.nextInt(1, 10)}-1${r.nextInt(0, 10)} 10:00:00",
        net = listOf("LTE", "NR", "UMTS", "GSM", "LTE-CA").random(r),
        cid = listOf("1", "2", "3", "0001").random(r), mnc = listOf("07", "7", "01").random(r),
        tac = listOf("100", "200").random(r), mcc = "214",
        verified = listOf(null, "VERIFIED", "NOT_FOUND", "PENDING", "REJECTED", "ERROR", "verified", "BOGUS").random(r),
        score = listOf(null, 0L, 69L, 70L, 71L, 85L, 100L, 69.9, 70.0, "abc").random(r),
        failed = reasons.random(r),
        lat = listOf(null, 42.8).random(r), lon = listOf(null, -1.6).random(r),
        radio = listOf(null, "LTE", "NR", "GSM", "UMTS", "UNKNOWN", "lte", "5G").random(r),
        observed = if (r.nextInt(6) == 0) null else r.nextLong(1_000, 1_040)   // muchos empates de hora
    )

    @Test fun `random datasets give exactly the previous figures`() {
        repeat(25) { seed ->
            db.createStatement().use { it.execute("DELETE FROM history") }
            val r = Random(seed)
            repeat(r.nextInt(1, 300)) { insert(randomRow(r)) }
            assertSame(legacy(), IntelStatsQuery.load(rows))
        }
    }

    @Test fun `empty history gives no statistics`() {
        assertNull(IntelStatsQuery.load(rows))
    }

    @Test fun `ties for most seen cell and network types follow history order`() {
        fun row(cid: String, net: String, obs: Long?) = Row("2026-01-01 00:00", net, cid, "07", "1", "214", null, 100L, null, null, null, "LTE", obs)
        // Dos celdas con 2 observaciones; la más reciente (obs 30) es de la B → gana B.
        listOf(row("A", "LTE", 10), row("B", "NR", 30), row("A", "LTE", 20), row("B", "NR", 5)).forEach(::insert)
        assertEquals("214-07-1-B-LTE" to 2L, IntelStatsQuery.load(rows)!!.mostSeen)
        assertEquals(listOf("NR" to 2L, "LTE" to 2L), IntelStatsQuery.load(rows)!!.networkTypes)
        assertSame(legacy(), IntelStatsQuery.load(rows))
        // Misma hora máxima: decide el id mayor. Y con horas nulas, van después de las conocidas.
        db.createStatement().use { it.execute("DELETE FROM history") }
        listOf(row("A", "LTE", 50), row("B", "NR", 50), row("C", "GSM", null), row("C", "GSM", null), row("A", "LTE", null), row("B", "NR", null)).forEach(::insert)
        // Las tres con 2; A y B comparten la hora máxima (50): gana B por id mayor; C (solo nulas) va detrás.
        assertEquals("214-07-1-B-LTE" to 2L, IntelStatsQuery.load(rows)!!.mostSeen)
        assertSame(legacy(), IntelStatsQuery.load(rows))
    }

    @Test fun `low score is not a confirmed alarm and the reason filter excludes clean and sub-threshold rows`() {
        fun row(cid: String, score: Long, failed: String?) = Row("2026-01-01", "LTE", cid, "07", "1", "214", null, score, failed, null, null, "LTE", 1)
        insert(row("1", 69, "$SUBTHRESHOLD_PREFIX Vecinos fantasma"))     // bajo el umbral pero sub-umbral
        insert(row("2", 85, "${StoredReasons.confirmingPrefix(1, 3)} Celda aislada"))  // en confirmación
        insert(row("3", 100, "OK"))
        insert(row("4", 70, "  "))
        val s = IntelStatsQuery.load(rows)!!
        assertEquals("solo score < 70", 1L, s.lowScoreCells)
        assertEquals("con motivo: también en confirmación, nunca sub-umbral ni limpio", 1L, s.cellsWithReason)
        assertSame(legacy(), s)
    }

    @Test fun `null text fields no longer hide older rows (deliberate difference)`() {
        insert(Row("2026-01-02", "LTE", "1", "07", "1", "214", null, 100L, null, null, null, "LTE", 30))
        insert(Row("2026-01-01", null, null, "07", "1", "214", null, 50L, null, null, null, "LTE", 20))   // textos nulos
        insert(Row("2025-12-31", "NR", "3", "07", "1", "214", "VERIFIED", 100L, null, 1.0, 1.0, "NR", 10))
        assertEquals("la lectura anterior se cortaba en la fila con nulos", 1L, legacy()!!.observations)
        val s = IntelStatsQuery.load(rows)!!
        assertEquals(3L, s.observations)
        assertEquals(3L, s.cells)
        assertTrue(s.networkTypes.contains(null to 1L))
        assertEquals("2025-12-31", s.dateFrom)
        assertEquals(1L, s.verifiedCells)
    }

    @Test fun `the identity matches HistoryRecord identityKey and the column names match CellDbHelper`() {
        insert(Row("t", "LTE", "9", "7", "300", "214", null, 100L, null, null, null, "lte", 1))
        val ident = rows.query("SELECT ${IntelStatsQuery.IDENTITY_SQL} FROM history").single().single()
        assertEquals("214-7-300-9-UNKNOWN", ident)
        val helper = listOf("app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt", "src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt")
            .map(::File).first { it.exists() }.readText()
        mapOf("TABLE_HISTORY" to "history", "COLUMN_ID" to "id", "COLUMN_TIMESTAMP" to "timestamp", "COLUMN_NET_TYPE" to "net_type",
            "COLUMN_CID" to "cid", "COLUMN_MNC" to "mnc", "COLUMN_TAC" to "tac", "COLUMN_MCC" to "mcc", "COLUMN_VERIFIED" to "verified",
            "COLUMN_SCORE" to "score", "COLUMN_FAILED_H" to "failed_heuristics", "COLUMN_LAT" to "lat", "COLUMN_LON" to "lon",
            "COLUMN_RADIO" to "radio").forEach { (k, v) -> assertTrue(k, helper.contains("const val $k = \"$v\"")) }
        assertTrue(helper.contains("NEWEST_FIRST = \"\$COLUMN_OBSERVED_AT_MS DESC, \$COLUMN_ID DESC\""))
    }

    @Test fun `no window functions (Android 10 ships SQLite 3_22)`() {
        listOf(IntelStatsQuery.SUMMARY_SQL, IntelStatsQuery.MOST_SEEN_SQL, IntelStatsQuery.NETWORK_TYPES_SQL).forEach {
            assertTrue(!it.contains(" OVER", ignoreCase = true) && !it.contains("ROW_NUMBER", ignoreCase = true))
        }
    }
}
