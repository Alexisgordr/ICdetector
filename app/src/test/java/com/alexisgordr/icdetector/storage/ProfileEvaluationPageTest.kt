package com.alexisgordr.icdetector.storage

import com.alexisgordr.icdetector.core.profile.ProfileEvaluationQuery
import com.alexisgordr.icdetector.core.profile.ProfileEvaluationQuery.LevelFilter
import com.alexisgordr.icdetector.core.profile.ProfileEvaluationQuery.PageCursor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * 3.0 — La consulta de Historial → Perfil local contra un esquema 23 real: orden, páginas sin
 * repetidos ni huecos, filtros, filas anteriores a 3.0 y evaluaciones que sobreviven a un reinicio.
 */
class ProfileEvaluationPageTest {
    private lateinit var db: Connection

    @Before fun openSchema23() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys = ON")
        SchemaV20MigrationTest.V19_TABLES.forEach { exec(it) }
        SchemaV20.STATEMENTS.forEach { exec(it) }
        SchemaV21.STATEMENTS.forEach { exec(it) }
        SchemaV22.STATEMENTS.forEach { exec(it) }
        SchemaV23.STATEMENTS.forEach { exec(it) }
        SchemaV24.STATEMENTS.forEach { exec(it) }
        val levels = listOf("NONE", "LOW", "ABSTAIN", "HIGH", "NONE", "MODERATE", "NONE")
        levels.forEachIndexed { i, level ->
            val observedAt = if (i == 0) "NULL" else (1_000L * (i + 1)).toString()      // fila 1: anterior a 3.0
            exec("INSERT INTO history (timestamp, cid, mnc, tac, mcc, radio, dbm, score, failed_heuristics, observed_at_ms) " +
                "VALUES ('2026-10-0${i + 1} 10:00:00', '${100 + i}', '07', '1', '214', 'LTE', -80, 100, 'OK', $observedAt)")
            val score = if (level == "ABSTAIN") "NULL" else "1.0"
            val reason = if (level == "ABSTAIN") "'PROFILE_IMMATURE'" else "NULL"
            exec("INSERT INTO profile_evaluations (history_id, evaluated_at_ms, algorithm_version, context_key, mode, " +
                "deviation_level, deviation_score, metrics_evaluated, metrics_total, abstain_reason) VALUES " +
                "(${i + 1}, ${500 + i}, 2, 'k', 'SHADOW', '$level', $score, ${if (level == "ABSTAIN") 0 else 1}, 2, $reason)")
        }
        // Reiniciar el perfil borra solo las referencias: las evaluaciones siguen ahí.
        exec("DELETE FROM local_profile")
    }

    @After fun close() = db.close()

    private data class Row(val id: Long, val sortKey: Long, val level: String, val hasObservation: Boolean)

    private fun page(filter: LevelFilter, after: PageCursor?, limit: Int): List<Row> {
        val (sql, args) = ProfileEvaluationQuery.pageSql(filter, after, limit)
        return db.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setString(i + 1, a) }
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val observed = rs.getLong("observed_at_ms").takeUnless { rs.wasNull() }
                        add(Row(rs.getLong("e_id"), observed ?: rs.getLong("evaluated_at_ms"), rs.getString("deviation_level"),
                            rs.getObject("h_id") != null))
                    }
                }
            }
        }
    }

    @Test fun `all pages together list every evaluation once, newest first`() {
        val seen = mutableListOf<Row>()
        var cursor: PageCursor? = null
        var pages = 0
        do {
            val fetched = page(LevelFilter.ALL, cursor, limit = 3)
            val rows = fetched.take(3)
            seen += rows
            cursor = rows.lastOrNull()?.let { PageCursor(it.sortKey, it.id) }
            // Los argumentos van como texto, igual que en Android: sin CAST en la consulta, el cursor
            // no avanzaba y la paginación no terminaba nunca.
            assertTrue("la paginación no avanza", ++pages <= 3)
        } while (fetched.size > 3)
        assertEquals((1L..7L).toSet(), seen.map { it.id }.toSet())
        assertEquals(7, seen.size)
        assertEquals(seen.sortedWith(compareByDescending<Row> { it.sortKey }.thenByDescending { it.id }), seen)
        assertEquals("sin instante exacto: ordenada por la evaluación", 1L, seen.last().id)
    }

    @Test fun `filters include normal observations and abstentions`() {
        assertEquals(3, page(LevelFilter.NONE, null, 50).size)
        assertEquals(listOf("ABSTAIN"), page(LevelFilter.ABSTAIN, null, 50).map { it.level })
        assertEquals(setOf("LOW", "MODERATE", "HIGH"), page(LevelFilter.DEVIATIONS, null, 50).map { it.level }.toSet())
        assertEquals(listOf("HIGH"), page(LevelFilter.HIGH, null, 50).map { it.level })
    }

    @Test fun `deleted history takes its evaluations along, and an empty table gives an empty page`() {
        exec("DELETE FROM history")
        assertTrue(page(LevelFilter.ALL, null, 50).isEmpty())
        db.createStatement().use { st -> st.executeQuery(ProfileEvaluationQuery.LEVEL_COUNTS_SQL).use { assertTrue(!it.next()) } }
    }

    @Test fun `level counts come from the stored evaluations`() {
        val counts = db.createStatement().use { st ->
            st.executeQuery(ProfileEvaluationQuery.LEVEL_COUNTS_SQL).use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) } }
        }
        assertEquals(mapOf("NONE" to 3, "LOW" to 1, "ABSTAIN" to 1, "HIGH" to 1, "MODERATE" to 1), counts)
        assertNull(counts["OTHER"])
    }

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }
}
