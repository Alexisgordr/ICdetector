// 3.1.0 (lint UseKtx) — Las transacciones de la base se dejan con begin/try/finally explícito en vez
// de `SQLiteDatabase.transaction {}`. El orden (setTransactionSuccessful al final del try,
// endTransaction en el finally) se lee en el código y hay pruebas que lo comprueban; con
// `transaction {}`, un `return` anticipado dentro del bloque desharía la transacción sin avisar.
@file:Suppress("UseKtx")

package com.alexisgordr.icdetector.service

import android.content.SharedPreferences
import com.alexisgordr.icdetector.core.profile.LocalProfile
import com.alexisgordr.icdetector.core.profile.LocalProfileHealth
import com.alexisgordr.icdetector.core.profile.LocalProfileLearner
import com.alexisgordr.icdetector.core.profile.LocalProfileShadow
import com.alexisgordr.icdetector.core.profile.MetricProfile
import com.alexisgordr.icdetector.core.profile.ProfileBootstrap
import com.alexisgordr.icdetector.core.profile.ProfileContext
import com.alexisgordr.icdetector.core.profile.ProfileMetric
import com.alexisgordr.icdetector.core.profile.ProfileMode
import com.alexisgordr.icdetector.core.profile.Provenance
import com.alexisgordr.icdetector.storage.CellDbHelper
import com.alexisgordr.icdetector.storage.LocalProfileStore
import androidx.core.content.edit

/**
 * 3.0 — Perfil local en sombra. Se llama DESPUÉS de guardar cada fila del historial, en la misma
 * cola de escritura, y solo escribe en sus propias tablas. No recibe ni devuelve nada al motor:
 * scores, rachas, alertas, aprendizaje actual y decisiones forenses no dependen de él. Cualquier
 * fallo aquí lo absorbe [ObservationPersistenceController] sin tocar la fila ya guardada.
 */
internal class LocalProfileController(
    private val dbHelper: CellDbHelper,
    private val prefs: SharedPreferences,
    private val deviceModel: String,
    private val mode: () -> ProfileMode = { ProfileMode.SHADOW },
    private val appVersion: String? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val shadow = LocalProfileShadow(LocalProfileLearner())
    private val processor = com.alexisgordr.icdetector.core.profile.ProfileObservationProcessor(shadow)

    /**
     * 3.0 (L2) — Una evaluación por observación: la segunda fila de una misma observación (p. ej. la
     * de alarma confirmada) no se vuelve a evaluar, aprender ni contar (ver ProfileObservationProcessor).
     * La comprobación y la escritura van en la misma transacción.
     */
    fun onRowWritten(row: ObservationRow, historyId: Long) {
        val cell = row.cell
        val obs = LocalProfileShadow.Observation(
            historyId = historyId,
            context = ProfileContext(cell.mcc, cell.mnc, cell.tac, cell.cellId, cell.radioTech),
            dbm = cell.dbm, rsrq = cell.rsrq,
            observedAtMs = row.context.observedAtMs,
            deviceModel = deviceModel,
            observationId = row.context.observationId
        )
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // F10 — Si la evaluación no se guarda, lanza y la transacción se deshace con el aprendizaje.
            processor.onRow(LocalProfileStore.processorStore(db), obs, mode(), clock())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Una vez por versión del algoritmo: aprende del historial reciente con instante exacto y evalúa
     * con el perfil resultante la parte final reservada (el 20 % final de las observaciones, cortado
     * por instante; no un número fijo de días). Nunca con un historial restaurado de una copia: puede
     * venir de otro teléfono y sus filas no tienen el modelo de dispositivo. Se marca como hecho solo
     * si la transacción termina bien.
     *
     * 3.0 (L3) — Límite conocido: la evaluación es única por fila (y por observación). Si se sube
     * [LocalProfile.ALGORITHM_VERSION], las reservadas de observaciones que ya tienen evaluación de la
     * versión anterior no se guardan (conflicto esperado, ver ProfileEvaluationWrite). Antes de subirla
     * hay que decidir la política (ver MANUAL, *Changing the algorithm version*).
     */
    fun bootstrapIfNeeded() {
        val key = PREF_BOOTSTRAP_DONE_PREFIX + LocalProfile.ALGORITHM_VERSION
        if (prefs.getBoolean(key, false)) return
        val now = clock()
        val restored = prefs.getBoolean(PREF_HISTORY_RESTORED, false)
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            if (!restored) {
                val rows = LocalProfileStore.bootstrapRows(db, now - BOOTSTRAP_WINDOW_MS)
                val plan = ProfileBootstrap.plan(rows, now, restoredFromBackup = false, maxAgeMs = BOOTSTRAP_WINDOW_MS)
                val profiles = HashMap<String, MutableMap<ProfileMetric, MetricProfile>>()
                fun of(ctx: ProfileContext) = profiles.getOrPut(ctx.key) { LocalProfileStore.profiles(db, ctx.key).toMutableMap() }
                plan.learn.forEach { r ->
                    val current = of(r.context)
                    shadow.learn(current, observation(r), Provenance.BOOTSTRAP_HISTORY, now).forEach { current[it.metric] = it }
                }
                profiles.values.flatMap { it.values }.forEach { LocalProfileStore.upsert(db, it) }
                plan.holdout.forEach { r ->
                    val step = shadow.process(of(r.context), observation(r), ProfileMode.SHADOW, now, holdout = true)
                    // 3.0 (L3) — Un conflicto (la fila u observación ya tiene evaluación, quizá de otra
                    // versión) es esperado: la anterior se conserva. Un fallo real lanza, deshace la
                    // transacción y el arranque NO se marca como hecho: se reintenta la próxima vez.
                    val written = LocalProfileStore.insertEvaluationChecked(db, step.record)
                    check(written != com.alexisgordr.icdetector.core.profile.ProfileEvaluationWrite.Outcome.FAILED) { "bootstrap evaluation not written" }
                }
            }
            // Primera activación de esta versión del algoritmo, con el modo en que empieza.
            LocalProfileStore.recordModeEvent(db, mode(), appVersion, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        prefs.edit { putBoolean(key, true) }
    }

    /**
     * F10 — Registra un fallo absorbido: contador, último fallo y hora en las preferencias (los
     * muestra Ajustes) y una línea de log como mucho cada [LocalProfileHealth.LOG_INTERVAL_MS].
     */
    fun recordFailure(where: String, e: Throwable) {
        val now = clock()
        val text = LocalProfileHealth.describe(where, e)
        prefs.edit {
            putInt(LocalProfileHealth.PREF_ERROR_COUNT, prefs.getInt(LocalProfileHealth.PREF_ERROR_COUNT, 0) + 1)
            putString(LocalProfileHealth.PREF_LAST_ERROR, text)
            putLong(LocalProfileHealth.PREF_LAST_ERROR_MS, now)
        }
        if (LocalProfileHealth.shouldLog(lastLoggedMs, now)) {
            lastLoggedMs = now
            android.util.Log.w("LocalProfile", text, e)
        }
    }

    @Volatile private var lastLoggedMs: Long? = null

    /**
     * Si una restauración desactivó la opción experimental, lo anota como `OFF` en el registro de
     * modos (la restauración ocurre antes de que se pueda abrir la base).
     */
    fun logSwitchOffByRestore() {
        if (!prefs.getBoolean(com.alexisgordr.icdetector.backup.FirstRunPolicy.PREF_PROFILE_OFF_BY_RESTORE, false)) return
        LocalProfileStore.recordModeEvent(dbHelper.writableDatabase, null, appVersion, clock())
        prefs.edit { remove(com.alexisgordr.icdetector.backup.FirstRunPolicy.PREF_PROFILE_OFF_BY_RESTORE) }
    }

    private fun observation(r: ProfileBootstrap.Row) =
        LocalProfileShadow.Observation(r.historyId, r.context, r.dbm, r.rsrq, r.observedAtMs!!, deviceModel, r.observationId)

    companion object {
        const val PREF_BOOTSTRAP_DONE_PREFIX = "local_profile_bootstrap_v"
        /** Lo escribe RestoreStartup al restaurar una copia: ese historial no arranca el perfil. */
        const val PREF_HISTORY_RESTORED = com.alexisgordr.icdetector.backup.FirstRunPolicy.PREF_HISTORY_RESTORED
        const val BOOTSTRAP_WINDOW_MS = 30L * LocalProfileLearner.DAY_MS
    }
}
