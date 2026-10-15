package com.alexisgordr.icdetector.storage

import com.alexisgordr.icdetector.core.RetentionClockGuard
import com.alexisgordr.icdetector.core.RetentionClockGuard.Decision
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.TimeZone

/**
 * 3.0 — La guardia del reloj con SQLite real, el esquema actual y los parámetros como TEXTO (como
 * Android). Una campaña de 100 días con 10 filas por día; el reloj de pared varía.
 */
class RetentionGuardSqlTest {
    private lateinit var db: Connection
    private val day = 86_400_000L
    private val utc = TimeZone.getTimeZone("UTC")
    private val realNow = 1_790_000_000_000L
    private val retention = 120 * day

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        SchemaV20MigrationTest.V19_TABLES.forEach(::exec)
        listOf(SchemaV20.STATEMENTS, SchemaV21.STATEMENTS, SchemaV22.STATEMENTS, SchemaV23.STATEMENTS, SchemaV24.STATEMENTS).forEach { it.forEach(::exec) }
        db.autoCommit = false
        db.prepareStatement("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics, observed_at_ms) VALUES (?, '1', -85, 100, 'OK', ?)").use { st ->
            for (d in 0 until 100) for (h in 0 until 10) {
                // Media hora de margen: ninguna fila cae justo en un corte de días enteros.
                val at = realNow - d * day - h * 3_600_000L - 1_800_000L
                st.setString(1, ObservationTime.localText(at, utc)); st.setLong(2, at); st.addBatch()
            }
            st.executeBatch()
        }
        db.commit(); db.autoCommit = true
    }

    @After fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun count(): Int = db.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM history").use { it.next(); it.getInt(1) } }

    /** Lo que hace pruneOldRecords con un reloj de pared [wallNow]: guardia y, si deja, borrado. */
    private fun prune(wallNow: Long): Decision {
        val cutoff = wallNow - retention
        val args = ObservationTime.cutoffArgs(cutoff, utc)
        val newest = db.createStatement().use { s ->
            s.executeQuery(RetentionGuardSql.NEWEST_OBSERVED).use { r -> r.next(); r.getLong(1).takeUnless { r.wasNull() } }
        }
        val (total, due) = db.prepareStatement(RetentionGuardSql.DUE_AND_TOTAL).use { st ->
            st.setString(1, args[0]); st.setString(2, args[1])
            st.executeQuery().use { r -> r.next(); r.getInt(1) to r.getInt(2) }
        }
        val decision = RetentionClockGuard.decide(cutoff, newest, due, total)
        if (decision == Decision.Prune) {
            db.prepareStatement("DELETE FROM history WHERE " + ObservationTime.beforeClause("observed_at_ms", "timestamp")).use { st ->
                st.setString(1, args[0]); st.setString(2, args[1]); st.executeUpdate()
            }
        }
        return decision
    }

    @Test fun `with the right clock nothing of a 100-day campaign is due`() {
        assertEquals(Decision.Prune, prune(realNow))
        assertEquals(1_000, count())
    }

    @Test fun `without the guard a clock a year ahead deleted the whole campaign`() {
        val args = ObservationTime.cutoffArgs(realNow + 365 * day - retention, utc)
        db.prepareStatement("DELETE FROM history WHERE " + ObservationTime.beforeClause("observed_at_ms", "timestamp")).use { st ->
            st.setString(1, args[0]); st.setString(2, args[1]); st.executeUpdate()
        }
        assertEquals("reproducción del bug", 0, count())
    }

    @Test fun `a clock a year ahead deletes nothing`() {
        assertTrue(prune(realNow + 365 * day) is Decision.CutoffAfterNewest)
        assertEquals(1_000, count())
    }

    @Test fun `a clock 60 days ahead would delete 40 percent and is skipped`() {
        // Corte 60 días por delante: los 40 días más antiguos (400 de 1.000 filas).
        assertEquals(Decision.TooManyRows(due = 400, total = 1_000), prune(realNow + 60 * day))
        assertEquals(1_000, count())
    }

    @Test fun `a short gap after the campaign still prunes the oldest days`() {
        // 30 días más tarde: se borran los 10 días más antiguos (100 filas, 10 %).
        assertEquals(Decision.Prune, prune(realNow + 30 * day))
        assertEquals(900, count())
    }

    @Test fun `an empty history prunes normally`() {
        exec("DELETE FROM history")
        assertEquals(Decision.Prune, prune(realNow + 365 * day))
    }
}
