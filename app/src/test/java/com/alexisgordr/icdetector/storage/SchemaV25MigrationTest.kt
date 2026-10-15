package com.alexisgordr.icdetector.storage

import com.alexisgordr.icdetector.core.RouteReview
import com.alexisgordr.icdetector.core.RouteReview.Current
import com.alexisgordr.icdetector.core.RouteReview.Failures
import com.alexisgordr.icdetector.models.CellTransitionSummary
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RouteFailureHistory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * 3.0 (UI2) — Esquema 25 en SQLite real: migración desde 24 sin perder rutas ni contadores, BD
 * nueva, y las MISMAS sentencias que usa CellDbHelper para registrar handovers y confirmar viajes.
 * Se comprueba lo que muestra la pantalla (estado actual, último evaluado y fecha, fallos, REVISAR).
 */
class SchemaV25MigrationTest {
    private lateinit var db: Connection

    companion object {
        /** `createTransitionTable` de CellDbHelper (esquema 24); se comprueba abajo que coincide. */
        const val V24_TRANSITIONS =
            "CREATE TABLE IF NOT EXISTS cell_transitions (" +
                "from_identity TEXT NOT NULL, to_identity TEXT NOT NULL, " +
                "observations INTEGER NOT NULL DEFAULT 0, trusted_observations INTEGER NOT NULL DEFAULT 0, " +
                "last_status TEXT NOT NULL, last_seen_ms INTEGER NOT NULL, " +
                "trip_count INTEGER NOT NULL DEFAULT 0, last_trip_id TEXT, " +
                "mobility_first_seen_ms INTEGER, mobility_last_seen_ms INTEGER, " +
                "PRIMARY KEY(from_identity, to_identity))"
    }

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec(V24_TRANSITIONS)
    }

    @After fun close() = db.close()

    /** applySchemaV25: columnas y, solo si la marcadora se acaba de crear, el relleno. */
    private fun migrate() {
        var marker = false
        SchemaV25.COLUMNS.forEach { sql ->
            try { exec(sql); if (sql.endsWith("ADD COLUMN ${SchemaV25.MARKER_COLUMN} TEXT")) marker = true }
            catch (e: java.sql.SQLException) { if (e.message?.contains("duplicate column", true) != true) throw e }
        }
        if (marker) SchemaV25.BACKFILL.forEach(::exec)
    }

    private fun legacy(from: String, to: String, obs: Int, trusted: Int, status: String, seen: Long, trips: Int = 0) =
        exec("INSERT INTO cell_transitions (from_identity,to_identity,observations,trusted_observations,last_status,last_seen_ms,trip_count,last_trip_id) " +
            "VALUES ('$from','$to',$obs,$trusted,'$status',$seen,$trips,${if (trips > 0) "'t0'" else "NULL"})")

    /** recordCellTransition: misma lógica (UPDATE; si no cambia nada, INSERT) y mismos argumentos. */
    private fun record(from: String, to: String, status: HeuristicStatus, at: Long, explanation: String = "N/A: motivo $at", trusted: Int = 0) {
        val args = SchemaV25.observationArgs(trusted, status.name, at, explanation)
        val changed = db.prepareStatement(SchemaV25.RECORD_UPDATE).use { st -> bind(st, arrayOf<Any?>(*args, from, to)); st.executeUpdate() }
        if (changed == 0) db.prepareStatement(SchemaV25.RECORD_INSERT).use { st -> bind(st, arrayOf<Any?>(from, to, *args)); st.executeUpdate() }
    }

    /** commitMobilityTrip para una arista. */
    private fun commitTrip(tripId: String, from: String, to: String, closedAt: Long) {
        db.prepareStatement(SchemaV25.TRIP_INSERT_IF_ABSENT).use { st -> bind(st, arrayOf<Any?>(from, to, closedAt)); st.executeUpdate() }
        db.prepareStatement(SchemaV25.TRIP_UPDATE).use { st -> bind(st, arrayOf<Any?>(tripId, closedAt, closedAt, from, to, tripId)); st.executeUpdate() }
    }

    /** queryCellTransitions: misma lectura que CellDbHelper. */
    private fun route(from: String, to: String): CellTransitionSummary = db.createStatement().use { st ->
        st.executeQuery("SELECT from_identity,to_identity,observations,trusted_observations,last_status,last_seen_ms,trip_count,last_trip_id," +
            "mobility_first_seen_ms,mobility_last_seen_ms,last_evaluated_status,last_evaluated_ms,last_explanation,failed_count,failure_history " +
            "FROM cell_transitions WHERE from_identity='$from' AND to_identity='$to'").use { c ->
            assertTrue("existe $from>$to", c.next())
            fun long(i: Int) = c.getLong(i).takeUnless { c.wasNull() }
            fun str(i: Int) = c.getString(i)
            CellTransitionSummary(str(1), str(2), c.getInt(3), c.getInt(4),
                runCatching { HeuristicStatus.valueOf(str(5)) }.getOrDefault(HeuristicStatus.NOT_EVALUATED), c.getLong(6),
                c.getInt(7), str(8), long(9), long(10),
                lastEvaluatedStatus = RouteReview.evaluatedFrom(str(11)), lastEvaluatedMs = long(12), lastExplanation = str(13),
                failedCount = c.getInt(14), failureHistory = RouteFailureHistory.fromStored(str(15)))
        }
    }

    @Test fun `migration from schema 24 keeps every route and counter and does not invent the past`() {
        legacy("A", "P", obs = 10, trusted = 7, status = "PASSED", seen = 1_000, trips = 2)
        legacy("A", "F", obs = 4, trusted = 1, status = "FAILED", seen = 2_000)
        legacy("A", "N", obs = 3, trusted = 0, status = "NOT_EVALUATED", seen = 3_000)
        legacy("A", "T", obs = 0, trusted = 0, status = "NOT_EVALUATED", seen = 4_000, trips = 5)   // solo viajes
        migrate()
        assertEquals("4", single("SELECT COUNT(*) FROM cell_transitions"))
        assertEquals("10|7|PASSED|1000|2", single("SELECT observations||'|'||trusted_observations||'|'||last_status||'|'||last_seen_ms||'|'||trip_count FROM cell_transitions WHERE to_identity='P'"))

        val p = route("A", "P")
        assertEquals(Current.COHERENT, RouteReview.current(p))
        assertEquals(HeuristicStatus.PASSED to 1_000L, p.lastEvaluatedStatus!! to p.lastEvaluatedMs!!)
        assertEquals("PASSED antiguo: no se sabe si falló antes", Failures.UnknownBefore(0), RouteReview.failures(p))
        assertFalse(RouteReview.needsReview(p))

        val f = route("A", "F")
        assertEquals(Failures.AtLeast(0), RouteReview.failures(f))
        assertTrue("un FAILED antiguo conservado entra en REVISAR", RouteReview.needsReview(f))

        val n = route("A", "N")
        assertEquals(Current.NOT_EVALUATED, RouteReview.current(n))
        assertNull("el último evaluado de un N/A antiguo es desconocido, no se inventa", n.lastEvaluatedStatus)
        assertNull("su motivo no se guardó", n.lastExplanation)
        assertEquals(Failures.UnknownBefore(0), RouteReview.failures(n))
        assertFalse("N/A solo no es REVISAR", RouteReview.needsReview(n))

        val t = route("A", "T")
        assertEquals(Current.NO_HANDOVER_OBSERVED, RouteReview.current(t))
        assertEquals("sin handovers antes: historial completo", Failures.Complete(0), RouteReview.failures(t))
        assertEquals(5, t.tripCount)
    }

    @Test fun `running the migration twice never re-applies the backfill`() {
        legacy("A", "F", 4, 1, "FAILED", 2_000)
        migrate()
        record("A", "N", HeuristicStatus.PASSED, 9_000)   // ruta nueva, completa
        migrate()                                          // columnas ya existen: sin relleno
        assertEquals(Failures.Complete(0), RouteReview.failures(route("A", "N")))
        assertEquals(Failures.AtLeast(0), RouteReview.failures(route("A", "F")))
    }

    @Test fun `a new database has the columns and new routes are fully tracked`() {
        db.createStatement().use { it.execute("DROP TABLE cell_transitions") }
        exec(V24_TRANSITIONS); migrate()      // onCreate: createTransitionTable + applySchemaV25
        record("X", "Y", HeuristicStatus.NOT_EVALUATED, 100)
        val r = route("X", "Y")
        assertEquals(Current.NOT_EVALUATED, RouteReview.current(r))
        assertEquals("N/A: motivo 100", r.lastExplanation)
        assertNull(RouteReview.previousEvaluated(r))
        assertEquals(Failures.Complete(0), RouteReview.failures(r))
        assertFalse(RouteReview.needsReview(r))
    }

    @Test fun `PASSED then N-A shows not evaluated and the earlier PASSED apart with its date`() {
        migrate()
        record("A", "B", HeuristicStatus.PASSED, 1_000, trusted = 1)
        record("A", "B", HeuristicStatus.NOT_EVALUATED, 2_000, "N/A: no hay fix GPS en la observación de la celda nueva.")
        val r = route("A", "B")
        assertEquals(Current.NOT_EVALUATED, RouteReview.current(r))
        assertEquals(2_000L, r.lastSeenMs)
        assertEquals("N/A: no hay fix GPS en la observación de la celda nueva.", r.lastExplanation)
        assertEquals(HeuristicStatus.PASSED to 1_000L, RouteReview.previousEvaluated(r))
        assertEquals(2, r.observations); assertEquals("el aprendizaje no cambia", 1, r.trustedObservations)
        assertFalse(RouteReview.needsReview(r))
    }

    @Test fun `FAILED then N-A stays in REVIEW and keeps the FAILED apart`() {
        migrate()
        record("A", "B", HeuristicStatus.FAILED, 1_000)
        record("A", "B", HeuristicStatus.NOT_EVALUATED, 2_000)
        val r = route("A", "B")
        assertEquals(Current.NOT_EVALUATED, RouteReview.current(r))
        assertEquals(HeuristicStatus.FAILED to 1_000L, RouteReview.previousEvaluated(r))
        assertEquals(Failures.Complete(1), RouteReview.failures(r))
        assertTrue(RouteReview.needsReview(r))
    }

    @Test fun `FAILED then PASSED is coherent now but still in REVIEW`() {
        migrate()
        record("A", "B", HeuristicStatus.FAILED, 1_000)
        record("A", "B", HeuristicStatus.PASSED, 2_000, trusted = 1)
        val r = route("A", "B")
        assertEquals(Current.COHERENT, RouteReview.current(r))
        assertNull("el último evaluado ES la lectura actual: no se repite aparte", RouteReview.previousEvaluated(r))
        assertNull("el motivo de N/A no queda tras una lectura evaluada", r.lastExplanation)
        assertEquals(HeuristicStatus.PASSED to 2_000L, r.lastEvaluatedStatus!! to r.lastEvaluatedMs!!)
        assertEquals(Failures.Complete(1), RouteReview.failures(r))
        assertTrue(RouteReview.needsReview(r))
    }

    @Test fun `only N-A is never REVIEW and has no evaluated result`() {
        migrate()
        repeat(3) { record("A", "B", HeuristicStatus.NOT_EVALUATED, 1_000L + it) }
        val r = route("A", "B")
        assertEquals(Current.NOT_EVALUATED, RouteReview.current(r))
        assertNull(r.lastEvaluatedStatus); assertNull(RouteReview.previousEvaluated(r))
        assertEquals(Failures.Complete(0), RouteReview.failures(r))
        assertFalse(RouteReview.needsReview(r))
        assertEquals(3, r.observations)
    }

    @Test fun `trip confirmation never alters the new data and creates trip-only routes cleanly`() {
        migrate()
        record("A", "B", HeuristicStatus.FAILED, 1_000)
        record("A", "B", HeuristicStatus.NOT_EVALUATED, 2_000, "N/A: x")
        val before = route("A", "B")
        commitTrip("t1", "A", "B", 5_000)
        commitTrip("t1", "A", "B", 6_000)    // el mismo viaje no cuenta dos veces
        commitTrip("t2", "C", "D", 7_000)    // ruta solo de viaje
        val after = route("A", "B")
        assertEquals(before.copy(tripCount = 1, lastTripId = "t1", mobilityFirstSeenMs = 5_000, mobilityLastSeenMs = 5_000), after)
        val trip = route("C", "D")
        assertEquals(Current.NO_HANDOVER_OBSERVED, RouteReview.current(trip))
        assertEquals(Failures.Complete(0), RouteReview.failures(trip))
        assertNull(trip.lastEvaluatedStatus); assertNull(trip.lastExplanation)
        assertEquals(1, trip.tripCount); assertEquals(0, trip.observations)
        // Después llega un handover real a esa ruta: se sigue completa.
        record("C", "D", HeuristicStatus.FAILED, 8_000)
        assertEquals(Failures.Complete(1), RouteReview.failures(route("C", "D")))
        assertEquals(1, route("C", "D").tripCount)
    }

    @Test fun `CellDbHelper uses these exact statements and runs schema 25 on create and upgrade`() {
        val helper = listOf("app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt", "src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(helper.contains("const val DATABASE_VERSION = SchemaV25.VERSION"))
        assertTrue(helper.contains("if (oldVersion < 25) applySchemaV25(db)"))
        assertTrue(helper.substringAfter("override fun onCreate(db: SQLiteDatabase) {").substringBefore("override fun onUpgrade").contains("applySchemaV25(db)"))
        val apply = helper.substringAfter("private fun applySchemaV25(db: SQLiteDatabase) {").substringBefore("\n    }\n")
        assertTrue(apply.contains("applySchemaV24(db)") && apply.contains("createTransitionTable(db)") && apply.contains("if (markerCreated) SchemaV25.BACKFILL.forEach(db::execSQL)"))
        val record = helper.substringAfter("fun recordCellTransition(").substringBefore("private fun bindAll(")
        assertTrue(record.contains("SchemaV25.RECORD_UPDATE") && record.contains("SchemaV25.RECORD_INSERT"))
        val trip = helper.substringAfter("override fun commitMobilityTrip(").substringBefore("override fun discardMobilityTrip(")
        assertTrue(trip.contains("SchemaV25.TRIP_INSERT_IF_ABSENT") && trip.contains("SchemaV25.TRIP_UPDATE"))
        // Ninguna otra escritura de cell_transitions fuera de estas, el borrado total y la retención.
        val writes = Regex("(UPDATE|INSERT[A-Z ]*INTO|DELETE FROM|db\\.(update|insert\\w*|delete))\\W+\\\$?\\{?TABLE_CELL_TRANSITIONS").findAll(helper).count()
        assertEquals("solo DELETE (borrar historial) y delete (retención)", 2, writes)
        // La tabla del esquema 24 es la que se migra aquí.
        val ddl = helper.substringAfter("private fun createTransitionTable(db: SQLiteDatabase) {").substringBefore("db.execSQL(\n            \"CREATE INDEX")
        listOf("observations INTEGER NOT NULL DEFAULT 0", "last_status TEXT NOT NULL", "trip_count INTEGER NOT NULL DEFAULT 0", "PRIMARY KEY(from_identity, to_identity)")
            .forEach { assertTrue(it, ddl.contains(it) && V24_TRANSITIONS.contains(it)) }
        assertTrue(SchemaV25.COLUMNS.all { it.startsWith("ALTER TABLE cell_transitions ADD COLUMN") })
    }

    private fun bind(st: java.sql.PreparedStatement, args: Array<out Any?>) = args.forEachIndexed { i, v -> st.setObject(i + 1, v) }
    private fun exec(sql: String) { db.createStatement().use { it.execute(sql) } }
    private fun single(sql: String): String? = db.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
}
