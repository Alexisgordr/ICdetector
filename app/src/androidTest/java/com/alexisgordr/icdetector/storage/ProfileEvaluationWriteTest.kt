package com.alexisgordr.icdetector.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.core.profile.DeviationLevel
import com.alexisgordr.icdetector.core.profile.ProfileEvaluationRecord
import com.alexisgordr.icdetector.core.profile.ProfileEvaluationWrite.Outcome
import com.alexisgordr.icdetector.core.profile.ProfileMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 3.0 (L5) — insertEvaluationChecked con el SQLite de Android: una fila antigua sin observation_id
 * se consulta solo por history_id (Android no admite argumentos null); con identidad, por fila u
 * observación; y un fallo real de escritura no se confunde con un conflicto.
 */
@RunWith(AndroidJUnit4::class)
class ProfileEvaluationWriteTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "profile_evaluation_write_test.db"
    private lateinit var helper: CellDbHelper

    @Before fun before() {
        context.deleteDatabase(name)
        helper = CellDbHelper(context, context.getDatabasePath(name).absolutePath)
        helper.writableDatabase.execSQL("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics, observed_at_ms) " +
            "VALUES ('2026-10-01 10:00:00', '1', -85, 100, 'OK', 5000)")                                   // id 1: antigua, sin identidad
        helper.writableDatabase.execSQL("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics, observed_at_ms, observation_id) " +
            "VALUES ('2026-10-01 10:05:00', '1', -85, 100, 'OK', 6000, 'obs-2')")                           // id 2
        helper.writableDatabase.execSQL("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics, observed_at_ms, observation_id) " +
            "VALUES ('2026-10-01 10:05:00', '1', -85, 100, 'H3', 6000, 'obs-2')")                           // id 3: misma observación
        helper.writableDatabase.execSQL("INSERT INTO history (timestamp, cid, dbm, score, failed_heuristics, observed_at_ms) " +
            "VALUES ('2026-10-01 10:10:00', '1', -85, 100, 'OK', 7000)")                                   // id 4: sin evaluación
    }

    @After fun after() { helper.close(); context.deleteDatabase(name) }

    private fun record(historyId: Long, version: Int, level: DeviationLevel, observationId: String?) = ProfileEvaluationRecord(
        historyId = historyId, evaluatedAtMs = 1L, algorithmVersion = version, profileRevision = null, contextKey = "k",
        mode = ProfileMode.SHADOW, level = level, score = null, metricsEvaluated = 0, metricsTotal = 2, evidence = null,
        abstainReason = null, learned = false, holdout = true, observationId = observationId)

    private fun stored(historyId: Long): String? = helper.readableDatabase.rawQuery(
        "SELECT algorithm_version || '|' || deviation_level FROM profile_evaluations WHERE history_id = ?", arrayOf(historyId.toString())
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    @Test fun oldRowWithoutObservationIdIsAlreadyEvaluatedWithoutException() {
        val db = helper.writableDatabase
        // Evaluación existente de la fila antigua (versión 2, sin identidad de observación).
        db.execSQL("INSERT INTO profile_evaluations (history_id, evaluated_at_ms, algorithm_version, context_key, mode, " +
            "deviation_level, metrics_evaluated, metrics_total) VALUES (1, 1, 2, 'k', 'SHADOW', 'NONE', 1, 2)")
        // Otra versión intenta guardar la suya: conflicto esperado, sin excepción por argumentos null.
        assertEquals(Outcome.ALREADY_EVALUATED, LocalProfileStore.insertEvaluationChecked(db, record(1, 3, DeviationLevel.HIGH, null)))
        assertEquals("se conserva la anterior", "2|NONE", stored(1))
        // Y una fila antigua sin evaluación se guarda con normalidad.
        assertEquals(Outcome.INSERTED, LocalProfileStore.insertEvaluationChecked(db, record(4, 2, DeviationLevel.LOW, null)))
    }

    @Test fun withObservationIdTheRowOrTheObservationMakesItAConflict() {
        val db = helper.writableDatabase
        assertEquals(Outcome.INSERTED, LocalProfileStore.insertEvaluationChecked(db, record(2, 2, DeviationLevel.NONE, "obs-2")))
        // Misma fila, otra versión.
        assertEquals(Outcome.ALREADY_EVALUATED, LocalProfileStore.insertEvaluationChecked(db, record(2, 3, DeviationLevel.HIGH, "obs-2")))
        // Otra fila de la misma observación.
        assertEquals(Outcome.ALREADY_EVALUATED, LocalProfileStore.insertEvaluationChecked(db, record(3, 3, DeviationLevel.HIGH, "obs-2")))
        assertEquals("2|NONE", stored(2))
        assertEquals(null, stored(3))
    }

    @Test fun aRealWriteFailureIsNotTakenForAConflict() {
        val db = helper.writableDatabase
        // Solo en esta base de prueba: un trigger que descarta el INSERT en silencio, como un
        // OR IGNORE ante una restricción. No hay evaluación previa de esas filas.
        db.execSQL("CREATE TEMP TRIGGER drop_eval BEFORE INSERT ON profile_evaluations BEGIN SELECT RAISE(IGNORE); END")
        try {
            assertEquals(Outcome.FAILED, LocalProfileStore.insertEvaluationChecked(db, record(4, 2, DeviationLevel.LOW, null)))
            assertEquals(Outcome.FAILED, LocalProfileStore.insertEvaluationChecked(db, record(2, 2, DeviationLevel.LOW, "obs-2")))
            assertEquals(null, stored(4))
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS temp.drop_eval")
        }
    }
}
