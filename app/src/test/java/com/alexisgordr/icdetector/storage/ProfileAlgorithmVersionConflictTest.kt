package com.alexisgordr.icdetector.storage

import com.alexisgordr.icdetector.core.profile.ProfileEvaluationWrite
import com.alexisgordr.icdetector.core.profile.ProfileEvaluationWrite.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * 3.0 (L3) — Con SQLite real y el esquema actual: la evaluación es única por fila, así que el arranque
 * de una versión futura del algoritmo no puede guardar la suya de una observación ya evaluada. Se
 * reproduce el conflicto, se comprueba que la antigua se conserva y que un fallo real no se confunde
 * con él. ALGORITHM_VERSION no cambia.
 */
class ProfileAlgorithmVersionConflictTest {
    private lateinit var db: Connection

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys = ON")
        SchemaV20MigrationTest.V19_TABLES.forEach(::exec)
        listOf(SchemaV20.STATEMENTS, SchemaV21.STATEMENTS, SchemaV22.STATEMENTS, SchemaV23.STATEMENTS, SchemaV24.STATEMENTS).forEach { it.forEach(::exec) }
        exec("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics, observed_at_ms, observation_id) VALUES ('2026-10-01 10:00:00', '1', -85, 100, 'OK', 5000, 'obs-1')")
        exec("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics, observed_at_ms) VALUES ('2026-10-01 10:05:00', '1', -85, 100, 'OK', 6000)")
        // Evaluación en sombra de la versión 2, guardada como hoy.
        assertEquals(1, insertOrIgnore(historyId = 1, version = 2, observationId = "obs-1", level = "NONE"))
    }

    @After fun close() = db.close()

    /** Lo que hace LocalProfileStore.insertEvaluation (INSERT ... OR IGNORE): filas cambiadas. */
    private fun insertOrIgnore(historyId: Long, version: Int, observationId: String?, level: String, contextKey: String? = "k"): Int =
        db.prepareStatement("INSERT OR IGNORE INTO profile_evaluations (history_id, evaluated_at_ms, algorithm_version, context_key, mode, " +
            "deviation_level, metrics_evaluated, metrics_total, holdout, observation_id) VALUES (?, 1, ?, ?, 'SHADOW', ?, 1, 2, 1, ?)").use { st ->
            st.setLong(1, historyId); st.setInt(2, version); st.setString(3, contextKey); st.setString(4, level); st.setString(5, observationId)
            st.executeUpdate()
        }

    /** Lo que hace LocalProfileStore.insertEvaluationChecked. */
    private fun checked(historyId: Long, version: Int, observationId: String?, level: String, contextKey: String? = "k"): Outcome {
        val inserted = insertOrIgnore(historyId, version, observationId, level, contextKey)
        val (sql, args) = ProfileEvaluationWrite.existsQuery(historyId, observationId)
        // Array<String>: el tipo ya impide argumentos nulos. Lo que puede fallar es el número.
        assertEquals("un argumento por marcador", sql.count { it == '?' }, args.size)
        val exists = inserted == 0 && db.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setString(i + 1, a) }; st.executeQuery().use { it.next() }
        }
        return ProfileEvaluationWrite.outcome(if (inserted == 1) 99L else -1L, exists)
    }

    @Test fun `a future version's bootstrap evaluation of an evaluated row is ignored, and the old one is kept`() {
        assertEquals(Outcome.ALREADY_EVALUATED, checked(historyId = 1, version = 3, observationId = "obs-1", level = "HIGH"))
        assertEquals("se conserva la evaluación de la versión 2, no se reemplaza", "2|NONE",
            single("SELECT algorithm_version || '|' || deviation_level FROM profile_evaluations WHERE history_id = 1"))
        assertEquals("1", single("SELECT COUNT(*) FROM profile_evaluations"))
        // Otra fila de la misma observación: mismo conflicto por la identidad de la observación.
        exec("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics, observed_at_ms, observation_id) VALUES ('2026-10-01 10:00:00', '1', -85, 100, 'H3', 5000, 'obs-1')")
        assertEquals(Outcome.ALREADY_EVALUATED, checked(historyId = 3, version = 3, observationId = "obs-1", level = "HIGH"))
    }

    @Test fun `an old row without observation id is a conflict, queried by row only`() {
        exec("INSERT INTO profile_evaluations (history_id, evaluated_at_ms, algorithm_version, context_key, mode, deviation_level, " +
            "metrics_evaluated, metrics_total) VALUES (2, 1, 2, 'k', 'SHADOW', 'LOW', 1, 2)")
        assertEquals(ProfileEvaluationWrite.EXISTS_BY_HISTORY_SQL to listOf("2"),
            ProfileEvaluationWrite.existsQuery(2, null).let { it.first to it.second.toList() })
        assertEquals(Outcome.ALREADY_EVALUATED, checked(historyId = 2, version = 3, observationId = null, level = "HIGH"))
        assertEquals("2|LOW", single("SELECT algorithm_version || '|' || deviation_level FROM profile_evaluations WHERE history_id = 2"))
    }

    @Test fun `a row without evaluation is stored normally`() {
        assertEquals(Outcome.INSERTED, checked(historyId = 2, version = 2, observationId = null, level = "LOW"))
    }

    @Test fun `a real write failure is not mistaken for the expected conflict`() {
        // OR IGNORE también descarta en silencio una restricción NOT NULL: no hay evaluación de esa fila.
        assertEquals(Outcome.FAILED, checked(historyId = 2, version = 2, observationId = null, level = "LOW", contextKey = null))
        assertEquals("0", single("SELECT COUNT(*) FROM profile_evaluations WHERE history_id = 2"))
    }

    @Test fun `bootstrap only marks itself done after a clean transaction, and the version is unchanged`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val controller = main.resolve("com/alexisgordr/icdetector/service/LocalProfileController.kt").readText()
        val bootstrap = controller.substringAfter("fun bootstrapIfNeeded() {").substringBefore("fun recordFailure(")
        assertTrue(bootstrap.contains("LocalProfileStore.insertEvaluationChecked(db, step.record)"))
        assertTrue(bootstrap.contains("check(written != com.alexisgordr.icdetector.core.profile.ProfileEvaluationWrite.Outcome.FAILED)"))
        assertTrue("se marca después de cerrar la transacción con éxito",
            bootstrap.indexOf("prefs.edit().putBoolean(key, true)") > bootstrap.indexOf("db.endTransaction()"))
        assertTrue(bootstrap.indexOf("db.setTransactionSuccessful()") < bootstrap.indexOf("} finally {"))
        val model = main.resolve("com/alexisgordr/icdetector/core/profile/LocalProfileModel.kt").readText()
        assertTrue(model.contains("const val ALGORITHM_VERSION = 2"))
        val manual = listOf(File("MANUAL.md"), File("../MANUAL.md")).first { it.exists() }.readText()
        assertTrue(manual.contains("**Changing the algorithm version (known limitation).**"))
        assertTrue(manual.contains("holding out the most recent 20 % of those observations"))
        assertTrue(manual.contains("not a fixed number of days"))
    }

    private fun exec(sql: String) { db.createStatement().use { it.execute(sql) } }
    private fun single(sql: String): String? = db.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
}
