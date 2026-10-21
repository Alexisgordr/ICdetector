package com.alexisgordr.icdetector.backup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.core.MobilityEdge
import com.alexisgordr.icdetector.core.MobilityTrip
import com.alexisgordr.icdetector.core.MobilityTripCloseReason
import com.alexisgordr.icdetector.core.ObservationContext
import com.alexisgordr.icdetector.core.StableSiteNotices
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.ForensicCaseOrigin
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.RouteFailureHistory
import com.alexisgordr.icdetector.models.TransitionCoherenceResult
import com.alexisgordr.icdetector.service.LocalProfileController
import com.alexisgordr.icdetector.service.ObservationRow
import com.alexisgordr.icdetector.service.logObservation
import com.alexisgordr.icdetector.storage.CellDbHelper
import com.alexisgordr.icdetector.storage.SchemaV25
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * 3.0 — Compatibilidad de copias con la APK actual (esquema 25), con el SQLite real de Android:
 *  1. Copias de esquema 23 y 24 se restauran y migran a 25 conservando los datos.
 *  2. Una copia de 25 exportada y restaurada es idéntica: historial, perfil local, rutas, viajes,
 *     casos forenses (todas las tablas, fila a fila) y ajustes; la clave de OpenCellID no viaja.
 *  3. Restaurar no publica avisos antiguos de Stable-Site (se borra el episodio pendiente).
 *  4. Una copia de esquema futuro se rechaza sin tocar la base actual.
 * Usa un directorio propio; los ajustes de la app que se tocan se guardan y se devuelven al final.
 */
@RunWith(AndroidJUnit4::class)
class BackupMigrationRoundTripTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.filesDir, "backup-roundtrip-test/databases")
    private val source = File(dir, "source.db")
    private val target = File(dir, "target.db")
    private val helpers = mutableListOf<CellDbHelper>()
    private val prefs = context.getSharedPreferences(BackupSettings.PREFS_FILE, Context.MODE_PRIVATE)
    private val touched = BackupSettings.KEYS.keys + listOf(LEGACY_KEY, StableSiteNotices.PREF_PENDING_EPISODE)
    private lateinit var saved: Map<String, Any?>

    @Before fun setUp() {
        dir.deleteRecursively(); dir.mkdirs()
        saved = touched.associateWith { prefs.all[it] }
    }

    @After fun tearDown() {
        helpers.forEach { it.close() }
        File(context.filesDir, "backup-roundtrip-test").deleteRecursively()
        context.getSharedPreferences("roundtrip_profile_test", Context.MODE_PRIVATE).edit().clear().commit()
        val e = prefs.edit()
        saved.forEach { (k, v) -> when (v) { null -> e.remove(k); is Boolean -> e.putBoolean(k, v); else -> e.putString(k, v.toString()) } }
        e.commit()
    }

    private fun helper(file: File) = CellDbHelper(context, file.absolutePath).also { helpers += it }

    private val cell = CellData(isRegistered = true, networkType = "4G LTE", cellId = "100", mnc = "07", tac = "1",
        dbm = -80, mcc = "214", radioTech = RadioTech.LTE, rsrq = -10)

    /** Datos de todas las áreas: historial con evaluaciones del perfil, rutas, un viaje y un caso forense. */
    private fun populate(h: CellDbHelper) {
        val profile = LocalProfileController(h, context.getSharedPreferences("roundtrip_profile_test", Context.MODE_PRIVATE),
            "google/pixel 8", clock = { 1_790_000_100_000L })
        repeat(4) { i ->
            val row = ObservationRow(cell.copy(cellId = "10$i"), if (i == 3) "H3" else "OK", ObservationContext(1_790_000_000_000L + i, null, null))
            profile.onRowWritten(row, h.logObservation(row, "3.0.0-beta4+test"))
        }
        h.recordCellTransition(TransitionCoherenceResult(HeuristicStatus.FAILED, "x", "A", "B"))
        h.recordCellTransition(TransitionCoherenceResult(HeuristicStatus.NOT_EVALUATED, "N/A: motivo", "A", "B"))
        h.recordCellTransition(TransitionCoherenceResult(HeuristicStatus.PASSED, "ok", "B", "C"))
        h.createTrip(MobilityTrip("trip-1", 1, 1, true, "A", 1, null), "A")
        h.addTripEdge("trip-1", MobilityEdge("A", "B"))
        h.commitMobilityTrip("trip-1", MobilityTripCloseReason.STATIC_CONFIRMED, 9_000)
        h.createForensicCase(cell, ForensicCaseOrigin.ALARM)
    }

    private fun export(h: CellDbHelper, file: File): ByteArray {
        val out = ByteArrayOutputStream()
        val result = BackupExporter.export(context, h, file, "3.0.0-beta4+test", out, Instant.EPOCH)
        assertTrue(result.toString(), result is BackupExporter.Result.Ok)
        return out.toByteArray()
    }

    /** Preparar, instalar y aplicar los ajustes exactamente como RestoreStartup. */
    private fun restore(backup: ByteArray): CellDbHelper {
        val stager = RestoreStager(context, dir, target.name)
        val staged = stager.stage(ByteArrayInputStream(backup))
        assertTrue(staged.toString(), staged is RestoreStager.Result.Staged)
        stager.engine.requestInstall()
        val engine = RestoreEngine(dir, target.name, AndroidDatabaseOps, onRestored = { RestoreStartup.applySettings(context, it) })
        assertEquals(RestoreEngine.RestoreOutcome.RESTORED, engine.recover().outcome)
        return helper(target)
    }

    /** Todas las tablas de la app, fila a fila y ordenadas; [skip] = columnas que no se comparan. */
    private fun dump(db: SQLiteDatabase, skip: Map<String, Set<String>> = emptyMap()): Map<String, List<String>> {
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('android_metadata','sqlite_sequence') ORDER BY name", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { t ->
            db.rawQuery("SELECT * FROM $t", null).use { c ->
                buildList {
                    while (c.moveToNext()) add((0 until c.columnCount).filter { c.getColumnName(it) !in skip[t].orEmpty() }
                        .joinToString("|") { "${c.getColumnName(it)}=${value(c, it)}" })
                }.sorted()
            }
        }
    }

    private fun value(c: Cursor, i: Int): String = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> "NULL"
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).joinToString(",")
        else -> c.getString(i)
    }

    private fun count(h: CellDbHelper, sql: String) = h.readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }

    // ── 2. Ida y vuelta de una copia de esquema 25 ─────────────────────────────────────────

    @Test fun schema25RoundTripIsIdenticalTableByTableAndKeepsSettings() {
        val src = helper(source)
        populate(src)
        prefs.edit().putString("location_mode", "ADAPTIVE").putBoolean("stable_site_silent_notices", false)
            .putString("legacy_network_alert_mode", "NOTIFY").putString(LEGACY_KEY, "secret-key").commit()
        val backup = export(src, source)
        val expected = dump(src.readableDatabase)
        assertTrue("hay datos en todas las áreas", listOf("history", "profile_evaluations", "cell_transitions", "mobility_trips", "forensic_cases")
            .all { expected[it].orEmpty().isNotEmpty() })

        // El teléfono cambia después de la copia.
        prefs.edit().putString("location_mode", "CONTINUOUS").putBoolean("stable_site_silent_notices", true)
            .putString(LEGACY_KEY, "other-key").commit()
        helper(target).also { addOther(it) }.close()

        val restored = restore(backup)
        assertEquals(CellDbHelper.DATABASE_VERSION, restored.readableDatabase.version)
        assertEquals(expected, dump(restored.readableDatabase))
        assertEquals("ADAPTIVE", prefs.getString("location_mode", null))
        assertFalse(prefs.getBoolean("stable_site_silent_notices", true))
        assertEquals("una clave retirada no viaja en la copia", "other-key", prefs.getString(LEGACY_KEY, null))
    }

    private fun addOther(h: CellDbHelper) = h.logConnection(observedAtMs = 1L, netType = "LTE", cid = "999", mnc = "07", tac = "1",
        mcc = "214", dbm = -90, radio = RadioTech.LTE, appVersion = "3.0.0-beta4+test", locationMode = "CONTINUOUS")

    // ── 1. Copias de esquemas 23 y 24 ─────────────────────────────────────────────────────

    /** `cell_transitions` del esquema 24 (igual en 23), con las mismas filas y sin las columnas de 25. */
    private fun downgradeTransitions(db: SQLiteDatabase) {
        val old = "from_identity,to_identity,observations,trusted_observations,last_status,last_seen_ms,trip_count,last_trip_id,mobility_first_seen_ms,mobility_last_seen_ms"
        db.execSQL("ALTER TABLE cell_transitions RENAME TO ct25")
        db.execSQL(com.alexisgordr.icdetector.storage.SchemaV25UpgradeTest.V24_TRANSITIONS)
        db.execSQL("INSERT INTO cell_transitions ($old) SELECT $old FROM ct25")
        db.execSQL("DROP TABLE ct25")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cell_transitions_seen ON cell_transitions(last_seen_ms)")
    }

    private val schema25Columns = setOf("last_evaluated_status", "last_evaluated_ms", "last_explanation", "failed_count", SchemaV25.MARKER_COLUMN)

    @Test fun schema24BackupMigratesTo25KeepingEverything() {
        val src = helper(source)
        populate(src)
        val expected = dump(src.readableDatabase, mapOf("cell_transitions" to schema25Columns))
        val old = BackupRestoreTest.rewrite(context, dir, export(src, source), modifyDatabase = { db -> downgradeTransitions(db); db.version = 24 }) {
            it.copy(schemaVersion = 24)
        }
        val restored = restore(old)
        assertEquals(CellDbHelper.DATABASE_VERSION, restored.readableDatabase.version)
        assertEquals(expected, dump(restored.readableDatabase, mapOf("cell_transitions" to schema25Columns)))
        assertMigratedRoutes(restored)
    }

    @Test fun schema23BackupMigratesTo25KeepingEverything() {
        // Fabricar una base 23 a partir de una actual exige ALTER TABLE ... DROP COLUMN (SQLite 3.35, Android 14+).
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val src = helper(source)
        populate(src)
        val skip = mapOf("cell_transitions" to schema25Columns, "history" to setOf("observation_id"), "profile_evaluations" to setOf("observation_id"))
        val expected = dump(src.readableDatabase, skip)
        val old = BackupRestoreTest.rewrite(context, dir, export(src, source), modifyDatabase = { db ->
            downgradeTransitions(db)
            db.execSQL("DROP INDEX IF EXISTS idx_profile_eval_observation")
            db.execSQL("DROP INDEX IF EXISTS idx_history_observation")
            db.execSQL("ALTER TABLE history DROP COLUMN observation_id")
            db.execSQL("ALTER TABLE profile_evaluations DROP COLUMN observation_id")
            db.version = 23
        }) { it.copy(schemaVersion = 23) }
        val restored = restore(old)
        assertEquals(CellDbHelper.DATABASE_VERSION, restored.readableDatabase.version)
        assertEquals(expected, dump(restored.readableDatabase, skip))
        assertEquals("los datos de 23 no tenían identidad de observación", 0, count(restored, "SELECT COUNT(*) FROM history WHERE observation_id IS NOT NULL"))
        assertMigratedRoutes(restored)
    }

    /** Relleno conservador del esquema 25: la ruta A→B terminó en N/A (antes hubo un FAILED que 24 no guardaba). */
    private fun assertMigratedRoutes(h: CellDbHelper) {
        val routes = h.getAllCellTransitions().associateBy { it.fromIdentity + ">" + it.toIdentity }
        val ab = routes.getValue("A>B")
        assertEquals(2, ab.observations); assertEquals(1, ab.tripCount)
        assertEquals("último N/A: no se sabe si falló antes", RouteFailureHistory.LEGACY_UNKNOWN, ab.failureHistory)
        assertNull(ab.lastEvaluatedStatus)
        val bc = routes.getValue("B>C")
        assertEquals(HeuristicStatus.PASSED, bc.lastEvaluatedStatus)
        assertEquals(0, bc.failedCount)
    }

    // ── 3. Stable-Site: restaurar no publica avisos antiguos ────────────────────────────────

    @Test fun restoringClearsThePendingStableSiteNotice() {
        val src = helper(source)
        populate(src)
        val backup = export(src, source)
        prefs.edit().putString(StableSiteNotices.PREF_PENDING_EPISODE, "episode-before-restore").putBoolean(StableSiteNotices.PREF_ENABLED, true).commit()
        restore(backup)
        assertNull(prefs.getString(StableSiteNotices.PREF_PENDING_EPISODE, null))
        // Una decisión real del evaluador que SÍ avisaría con ese episodio pendiente; tras restaurar
        // no queda pendiente y no avisa.
        val evidence = com.alexisgordr.icdetector.core.StableSiteEvidence("site", com.alexisgordr.icdetector.core.StableSiteFeatureState.ACTIVE,
            siteServingDays = 7, siteStaticDays = 3, siteNeighbourDays = 3, currentServingDays = 0, currentNeighbourDays = 0,
            previousServingDays = 7, motion = com.alexisgordr.icdetector.core.MotionEvidence(com.alexisgordr.icdetector.core.MotionState.STATIC_CONFIRMED, 130, 8f, 2f),
            servingObservations = 40, previousIdentity = "214-07-31601-100-LTE")
        val decision = com.alexisgordr.icdetector.core.StableSiteEvaluator.evaluate(evidence).copy(episodeId = "episode-before-restore")
        assertEquals("control: con el pendiente avisaría", StableSiteNotices.Action.NOTIFY,
            StableSiteNotices.decide(decision, enabled = true, pendingEpisodeId = "episode-before-restore"))
        assertEquals(StableSiteNotices.Action.NONE,
            StableSiteNotices.decide(decision, enabled = true, pendingEpisodeId = prefs.getString(StableSiteNotices.PREF_PENDING_EPISODE, null)))
    }

    // ── 4. Esquema futuro ───────────────────────────────────────────────────────────────────

    @Test fun futureSchemaBackupIsRejectedWithoutTouchingTheCurrentDatabase() {
        val src = helper(source)
        populate(src)
        val future = BackupRestoreTest.rewrite(context, dir, export(src, source), modifyDatabase = { db -> db.version = CellDbHelper.DATABASE_VERSION + 1 }) {
            it.copy(schemaVersion = CellDbHelper.DATABASE_VERSION + 1)
        }
        val tgt = helper(target)
        addOther(tgt)
        val before = dump(tgt.readableDatabase)
        tgt.close()
        val sha = { f: File -> MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) } }
        val shaBefore = sha(target)

        val result = RestoreStager(context, dir, target.name).stage(ByteArrayInputStream(future))
        assertEquals(RestoreStager.Result.Rejected(BackupPolicy.Rejection.FUTURE_SCHEMA), result)
        assertFalse("nada preparado", File(dir, RestoreEngine.WORK_DIR).exists())
        assertEquals("ni un byte de la base actual cambia", shaBefore, sha(target))
        assertEquals(RestoreEngine.RestoreOutcome.NOTHING, RestoreEngine(dir, target.name, AndroidDatabaseOps).recover().outcome)
        assertEquals(before, dump(helper(target).readableDatabase))
    }

    private companion object {
        /** Ajuste retirado en 3.1.0 (ver LegacyPreferences): nunca viajó ni viaja en la copia. */
        const val LEGACY_KEY = "opencellid_key"
    }
}
