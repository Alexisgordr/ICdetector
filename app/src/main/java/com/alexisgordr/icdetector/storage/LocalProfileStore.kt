package com.alexisgordr.icdetector.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.alexisgordr.icdetector.core.profile.AbstainReason
import com.alexisgordr.icdetector.core.profile.DeviationLevel
import com.alexisgordr.icdetector.core.profile.MetricProfile
import com.alexisgordr.icdetector.core.profile.ProfileBootstrap
import com.alexisgordr.icdetector.core.profile.ProfileContext
import com.alexisgordr.icdetector.core.profile.ProfileEvaluationRecord
import com.alexisgordr.icdetector.core.profile.ProfileMaturity
import com.alexisgordr.icdetector.core.profile.ProfileMetric
import com.alexisgordr.icdetector.core.profile.ProfileMode
import com.alexisgordr.icdetector.models.RadioTech

/**
 * 3.0 — Acceso a las tablas del perfil local (esquema 22). Solo escribe en sus propias tablas:
 * `local_profile`, `profile_evaluations` y `profile_mode_events`. Nunca en `history` ni en las del
 * motor actual.
 */
object LocalProfileStore {
    private const val P = SchemaV22.TABLE_PROFILE
    private const val E = SchemaV22.TABLE_EVALUATIONS
    private const val M = SchemaV22.TABLE_MODE_EVENTS

    fun profiles(db: SQLiteDatabase, contextKey: String): Map<ProfileMetric, MetricProfile> =
        db.rawQuery("SELECT * FROM $P WHERE context_key=?", arrayOf(contextKey)).use { c ->
            buildMap { while (c.moveToNext()) readProfile(c)?.let { put(it.metric, it) } }
        }

    fun upsert(db: SQLiteDatabase, p: MetricProfile) {
        val v = ContentValues().apply {
            put("context_key", p.contextKey); put("metric", p.metric.name); put("radio", p.radio.name)
            put("device_model", p.deviceModel); put("algorithm_version", p.algorithmVersion)
            put("revision", p.revision); put("mean", p.mean); put("variance", p.variance)
            put("effective_samples", p.effectiveSamples); put("bootstrap_samples", p.bootstrapSamples)
            put("live_samples", p.liveSamples); put("quarantined_samples", p.quarantinedSamples)
            put("distinct_days", p.distinctDays); put("last_day", p.lastDay); put("day_samples", p.daySamples)
            put("day_start_mean", p.dayStartMean); put("first_evidence_ms", p.firstEvidenceMs)
            put("last_evidence_ms", p.lastEvidenceMs); put("last_sample_ms", p.lastSampleMs)
            put("maturity", p.maturity.name); put("abstain_reason", p.abstainReason?.name)
            put("updated_at_ms", p.updatedAtMs)
            put("generation", p.generation); put("day_start_variance", p.dayStartVariance)
            put("last_seen_ms", p.lastSeenMs)
        }
        db.insertWithOnConflict(P, null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Una evaluación por fila del historial: si ya existe, se conserva la primera. */
    fun insertEvaluation(db: SQLiteDatabase, r: ProfileEvaluationRecord): Long {
        val v = ContentValues().apply {
            put("history_id", r.historyId); put("evaluated_at_ms", r.evaluatedAtMs)
            put("algorithm_version", r.algorithmVersion); put("profile_revision", r.profileRevision)
            put("context_key", r.contextKey); put("mode", r.mode.name); put("deviation_level", r.level.name)
            put("deviation_score", r.score); put("metrics_evaluated", r.metricsEvaluated)
            put("metrics_total", r.metricsTotal); put("evidence", r.evidence)
            put("abstain_reason", r.abstainReason?.name); put("learned", if (r.learned) 1 else 0)
            put("holdout", if (r.holdout) 1 else 0)
        }
        return db.insertWithOnConflict(E, null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * Filas del historial con instante exacto desde [sinceMs], para el arranque del perfil. Solo
     * lectura: el historial nunca se modifica.
     */
    fun bootstrapRows(db: SQLiteDatabase, sinceMs: Long): List<ProfileBootstrap.Row> =
        db.rawQuery(
            "SELECT id, mcc, mnc, tac, cid, radio, dbm, rsrq, observed_at_ms FROM history " +
                "WHERE observed_at_ms >= ? ORDER BY observed_at_ms, id",
            arrayOf(sinceMs.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    fun s(i: Int) = if (c.isNull(i)) "" else c.getString(i)
                    fun n(i: Int) = if (c.isNull(i)) null else c.getInt(i)
                    val radio = RadioTech.entries.firstOrNull { it.name == s(5) } ?: RadioTech.UNKNOWN
                    add(ProfileBootstrap.Row(c.getLong(0), ProfileContext(s(1), s(2), s(3), s(4), radio), n(6), n(7), c.getLong(8)))
                }
            }
        }

    fun recordModeEvent(db: SQLiteDatabase, mode: ProfileMode?, appVersion: String?, atMs: Long) =
        recordEvent(db, mode?.name ?: "OFF", appVersion, atMs)

    /** Un evento del registro de modos: un modo, `OFF` o `RESET` (reinicio del perfil). */
    fun recordEvent(db: SQLiteDatabase, label: String, appVersion: String?, atMs: Long) {
        db.insert(M, null, ContentValues().apply {
            put("at_ms", atMs); put("mode", label); put("app_version", appVersion)
        })
    }

    /** Última evaluación guardada, para mostrarla con la opción experimental. */
    fun latestEvaluation(db: SQLiteDatabase): ProfileEvaluationRecord? =
        db.rawQuery("SELECT * FROM $E ORDER BY evaluated_at_ms DESC, id DESC LIMIT 1", null).use { c ->
            if (!c.moveToFirst()) return null
            fun s(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getString(it) }
            fun i(n: String) = c.getInt(c.getColumnIndexOrThrow(n))
            ProfileEvaluationRecord(
                historyId = c.getLong(c.getColumnIndexOrThrow("history_id")),
                evaluatedAtMs = c.getLong(c.getColumnIndexOrThrow("evaluated_at_ms")),
                algorithmVersion = i("algorithm_version"), profileRevision = s("profile_revision"),
                contextKey = s("context_key").orEmpty(),
                mode = ProfileMode.entries.firstOrNull { it.name == s("mode") } ?: ProfileMode.SHADOW,
                level = DeviationLevel.entries.firstOrNull { it.name == s("deviation_level") } ?: DeviationLevel.ABSTAIN,
                score = c.getColumnIndexOrThrow("deviation_score").let { if (c.isNull(it)) null else c.getDouble(it) },
                metricsEvaluated = i("metrics_evaluated"), metricsTotal = i("metrics_total"), evidence = s("evidence"),
                abstainReason = AbstainReason.entries.firstOrNull { it.name == s("abstain_reason") },
                learned = i("learned") == 1, holdout = i("holdout") == 1
            )
        }

    /** Todas las referencias guardadas (son pocas: una por celda, tecnología y métrica). */
    fun allProfiles(db: SQLiteDatabase): List<MetricProfile> =
        db.rawQuery("SELECT * FROM $P", null).use { c -> buildList { while (c.moveToNext()) readProfile(c)?.let(::add) } }

    /** Instante del último evento [label] del registro de modos (p. ej. el último reinicio), o null. */
    fun lastEventMs(db: SQLiteDatabase, label: String): Long? =
        db.rawQuery("SELECT MAX(at_ms) FROM $M WHERE mode=?", arrayOf(label)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    /** Retención: referencias sin evidencia nueva en [cutoffMs] o antes. */
    fun pruneProfiles(db: SQLiteDatabase, cutoffMs: Long): Int =
        db.delete(P, "COALESCE(last_evidence_ms, updated_at_ms) < ?", arrayOf(cutoffMs.toString()))

    private fun readProfile(c: Cursor): MetricProfile? {
        fun s(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getString(it) }
        fun l(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getLong(it) }
        fun d(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getDouble(it) }
        fun i(n: String) = c.getInt(c.getColumnIndexOrThrow(n))
        val metric = ProfileMetric.entries.firstOrNull { it.name == s("metric") } ?: return null
        return MetricProfile(
            contextKey = s("context_key")!!, metric = metric,
            radio = RadioTech.entries.firstOrNull { it.name == s("radio") } ?: RadioTech.UNKNOWN,
            deviceModel = s("device_model")!!, algorithmVersion = i("algorithm_version"),
            revision = l("revision") ?: 0L, mean = d("mean"), variance = d("variance"),
            effectiveSamples = i("effective_samples"), bootstrapSamples = i("bootstrap_samples"),
            liveSamples = i("live_samples"), quarantinedSamples = i("quarantined_samples"),
            distinctDays = i("distinct_days"), lastDay = s("last_day"), daySamples = i("day_samples"),
            dayStartMean = d("day_start_mean"), firstEvidenceMs = l("first_evidence_ms"),
            lastEvidenceMs = l("last_evidence_ms"), lastSampleMs = l("last_sample_ms"),
            maturity = ProfileMaturity.entries.firstOrNull { it.name == s("maturity") } ?: ProfileMaturity.LEARNING,
            abstainReason = AbstainReason.entries.firstOrNull { it.name == s("abstain_reason") },
            updatedAtMs = l("updated_at_ms") ?: 0L,
            generation = s("generation") ?: MetricProfile.LEGACY_GENERATION,
            dayStartVariance = d("day_start_variance"), lastSeenMs = l("last_seen_ms")
        )
    }
}
