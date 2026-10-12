package com.alexisgordr.icdetector.core.profile

import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0 — Consulta de evaluaciones guardadas: solo datos de entonces, nunca el perfil actual. */
class ProfileEvaluationQueryTest {

    private fun observation(failed: String? = "OK", notEvaluated: String? = "NONE", observedAt: Long? = 2_000L) = HistoricalObservation(
        timestamp = "2026-10-12 10:00:00", observedAtMs = observedAt, mcc = "214", mnc = "07", tac = "1", cid = "100",
        radio = RadioTech.LTE, dbm = -60, rsrq = -10, sinr = 12, score = 100, failedHeuristics = failed,
        notEvaluatedHeuristics = notEvaluated, locationMode = "CONTINUOUS", appVersion = "3.0.0-beta4+abc1234"
    )

    @Test fun `evidence gives the stored reference, in both algorithm formats`() {
        val v2 = EvidenceParser.parse("SIGNAL_DBM -60 vs -80.000±4.000 z=5.000; SIGNAL_RSRQ -10 vs -11.500±1.500 z=1.000")
        assertEquals(listOf(
            MetricEvidence(ProfileMetric.SIGNAL_DBM, -60, -80.0, 4.0, 5.0),
            MetricEvidence(ProfileMetric.SIGNAL_RSRQ, -10, -11.5, 1.5, 1.0)
        ), v2)
        assertEquals(listOf(MetricEvidence(ProfileMetric.SIGNAL_DBM, -74, -80.0, 3.0, 2.0)),
            EvidenceParser.parse("SIGNAL_DBM -74 vs -80.0±3.0 z=2.00"))                // algoritmo 1
    }

    @Test fun `without stored evidence there is no reference, never an invented one`() {
        assertTrue(EvidenceParser.parse(null).isEmpty())                              // abstención
        assertTrue(EvidenceParser.parse("").isEmpty())
        assertTrue(EvidenceParser.parse("garbage; SIGNAL_FOO -1 vs 2.0±1.0 z=1.0").isEmpty())
        assertEquals(1, EvidenceParser.parse("SIGNAL_DBM -60 vs -80.000±4.000 z=5.000").size)  // RSRQ no evaluada
    }

    @Test fun `H13 is shown only as far as the stored row allows`() {
        assertEquals(H13History.FAILED, H13History.of(observation(failed = "Potencia anómala vs historial (+20dB)")))
        assertEquals(H13History.FAILED, H13History.of(observation(failed = "[sub-umbral] Huella RF incoherente con el historial (RSRQ/SINR muy desviados)")))
        assertEquals(H13History.NOT_EVALUATED, H13History.of(observation(notEvaluated = "H1;H13")))
        assertEquals(H13History.NO_FAILURE_RECORDED, H13History.of(observation()))
        assertEquals(H13History.NO_FAILURE_RECORDED, H13History.of(observation(notEvaluated = "H1;H12")))
        assertEquals("fila anterior a 3.0", H13History.UNKNOWN, H13History.of(observation(notEvaluated = null)))
        assertEquals(H13History.UNKNOWN, H13History.of(null))
    }

    @Test fun `filters include no-deviation and abstentions`() {
        assertEquals(DeviationLevel.entries.toSet(), ProfileEvaluationQuery.LevelFilter.ALL.levels)
        assertEquals(setOf(DeviationLevel.NONE), ProfileEvaluationQuery.LevelFilter.NONE.levels)
        assertEquals(setOf(DeviationLevel.ABSTAIN), ProfileEvaluationQuery.LevelFilter.ABSTAIN.levels)
        assertFalse(DeviationLevel.ABSTAIN in ProfileEvaluationQuery.LevelFilter.DEVIATIONS.levels)
    }

    @Test fun `pages follow the observation time and fall back to the evaluation time`() {
        fun entry(id: Long, obs: HistoricalObservation?) = EvaluationEntry(id, id, 5_000L, 2, null, "k", ProfileMode.SHADOW,
            DeviationLevel.NONE, 0.5, 1, 2, null, null, learned = true, holdout = false, observation = obs)
        assertEquals(2_000L, entry(1, observation()).sortKeyMs)
        assertEquals(5_000L, entry(2, observation(observedAt = null)).sortKeyMs)
        assertEquals(5_000L, entry(3, null).sortKeyMs)
        assertEquals(ProfileEvaluationQuery.PageCursor(5_000L, 3), ProfileEvaluationQuery.cursorAfter(listOf(entry(1, observation()), entry(3, null))))
        assertNull(ProfileEvaluationQuery.cursorAfter(emptyList()))
        assertTrue(ProfileEvaluationQuery.hasMore(51))
        assertFalse(ProfileEvaluationQuery.hasMore(50))
    }

    @Test fun `the query reads stored evaluations and history only, never the current profile`() {
        val (sql, args) = ProfileEvaluationQuery.pageSql(ProfileEvaluationQuery.LevelFilter.DEVIATIONS, ProfileEvaluationQuery.PageCursor(10, 7))
        assertFalse(sql.contains("local_profile"))
        assertTrue(sql.startsWith("SELECT "))
        listOf("INSERT", "UPDATE", "DELETE").forEach { assertFalse(sql.uppercase().contains(it)) }
        assertTrue(sql.contains("LEFT JOIN history h ON h.id = e.history_id"))
        assertTrue(sql.endsWith("LIMIT ${ProfileEvaluationQuery.PAGE_SIZE + 1}"))
        assertEquals(listOf("HIGH", "LOW", "MODERATE", "10", "10", "7"), args.toList())
    }

    @Test fun `a response for another filter or an older request is never applied`() {
        val current = ProfileEvaluationQuery.RequestToken(ProfileEvaluationQuery.LevelFilter.HIGH, 3)
        assertTrue(ProfileEvaluationQuery.accepts(current, ProfileEvaluationQuery.RequestToken(ProfileEvaluationQuery.LevelFilter.HIGH, 3)))
        assertFalse("otro filtro", ProfileEvaluationQuery.accepts(current, ProfileEvaluationQuery.RequestToken(ProfileEvaluationQuery.LevelFilter.ALL, 3)))
        assertFalse("antes de reintentar o cambiar", ProfileEvaluationQuery.accepts(current, ProfileEvaluationQuery.RequestToken(ProfileEvaluationQuery.LevelFilter.HIGH, 2)))
    }

    @Test fun `appending a page never repeats an evaluation already listed`() {
        fun entry(id: Long) = EvaluationEntry(id, id, id, 2, null, "k", ProfileMode.SHADOW, DeviationLevel.NONE, 0.5, 1, 2,
            null, null, learned = true, holdout = false, observation = null)
        val merged = ProfileEvaluationQuery.append(listOf(entry(5), entry(4)), listOf(entry(4), entry(3), entry(3), entry(2)))
        assertEquals(listOf(5L, 4L, 3L, 2L), merged.map { it.evaluationId })
    }
}
