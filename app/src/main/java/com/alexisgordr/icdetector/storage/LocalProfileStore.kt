package com.alexisgordr.icdetector.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.alexisgordr.icdetector.core.profile.AbstainReason
import com.alexisgordr.icdetector.core.profile.MetricProfile
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

    fun recordModeEvent(db: SQLiteDatabase, mode: ProfileMode?, appVersion: String?, atMs: Long) {
        db.insert(M, null, ContentValues().apply {
            put("at_ms", atMs); put("mode", mode?.name ?: "OFF"); put("app_version", appVersion)
        })
    }

    /** Reiniciar el perfil (todo, o un contexto). Las evaluaciones ya hechas se conservan. */
    fun reset(db: SQLiteDatabase, contextKey: String? = null) {
        if (contextKey == null) db.delete(P, null, null) else db.delete(P, "context_key=?", arrayOf(contextKey))
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
            updatedAtMs = l("updated_at_ms") ?: 0L
        )
    }
}
