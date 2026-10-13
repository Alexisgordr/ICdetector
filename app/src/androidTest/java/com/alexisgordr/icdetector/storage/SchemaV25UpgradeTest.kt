package com.alexisgordr.icdetector.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.core.MobilityEdge
import com.alexisgordr.icdetector.core.MobilityTrip
import com.alexisgordr.icdetector.core.MobilityTripCloseReason
import com.alexisgordr.icdetector.core.RouteReview
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RouteFailureHistory
import com.alexisgordr.icdetector.models.TransitionCoherenceResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 3.0 (UI2) — Esquema 25 con el SQLite de Android: actualización real desde 24 (rutas y contadores
 * intactos, pasado sin inventar), BD nueva, y las escrituras reales del helper (handover y viajes).
 */
@RunWith(AndroidJUnit4::class)
class SchemaV25UpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "schema25_upgrade_test.db"
    private val fresh = "schema25_fresh_test.db"
    private val helpers = mutableListOf<CellDbHelper>()

    companion object {
        /** `cell_transitions` del esquema 24 (createTransitionTable). */
        const val V24_TRANSITIONS =
            "CREATE TABLE IF NOT EXISTS cell_transitions (from_identity TEXT NOT NULL, to_identity TEXT NOT NULL, " +
                "observations INTEGER NOT NULL DEFAULT 0, trusted_observations INTEGER NOT NULL DEFAULT 0, " +
                "last_status TEXT NOT NULL, last_seen_ms INTEGER NOT NULL, trip_count INTEGER NOT NULL DEFAULT 0, last_trip_id TEXT, " +
                "mobility_first_seen_ms INTEGER, mobility_last_seen_ms INTEGER, PRIMARY KEY(from_identity, to_identity))"
    }

    @Before fun createSchema24() {
        listOf(name, fresh).forEach(context::deleteDatabase)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            SchemaV20UpgradeTest.V19.forEach(db::execSQL)
            listOf(SchemaV20.STATEMENTS, SchemaV21.STATEMENTS, SchemaV22.STATEMENTS, SchemaV23.STATEMENTS, SchemaV24.STATEMENTS).forEach { it.forEach(db::execSQL) }
            db.execSQL(V24_TRANSITIONS)
            db.execSQL("INSERT INTO cell_transitions VALUES ('A','F',4,1,'FAILED',2000,0,NULL,NULL,NULL)")
            db.execSQL("INSERT INTO cell_transitions VALUES ('A','P',9,6,'PASSED',3000,2,'t0',100,200)")
            db.execSQL("INSERT INTO cell_transitions VALUES ('A','N',2,0,'NOT_EVALUATED',4000,0,NULL,NULL,NULL)")
            db.version = 24
        }
    }

    @After fun after() { helpers.forEach { it.close() }; listOf(name, fresh).forEach(context::deleteDatabase) }

    private fun open(db: String) = CellDbHelper(context, context.getDatabasePath(db).absolutePath).also { helpers += it }
    private fun CellDbHelper.route(from: String, to: String) = getAllCellTransitions().single { it.fromIdentity == from && it.toIdentity == to }

    @Test fun upgradeKeepsRoutesAndDoesNotInventThePast() {
        val h = open(name)
        assertEquals(CellDbHelper.DATABASE_VERSION, h.writableDatabase.version)
        val f = h.route("A", "F")
        assertEquals(4, f.observations); assertEquals(1, f.trustedObservations)
        assertEquals(RouteFailureHistory.LEGACY_FAILED, f.failureHistory)
        assertTrue(RouteReview.needsReview(f))
        val p = h.route("A", "P")
        assertEquals(9, p.observations); assertEquals(6, p.trustedObservations); assertEquals(2, p.tripCount)
        assertEquals(HeuristicStatus.PASSED, p.lastEvaluatedStatus); assertEquals(3000L, p.lastEvaluatedMs)
        assertEquals(RouteFailureHistory.LEGACY_UNKNOWN, p.failureHistory)
        val n = h.route("A", "N")
        assertNull(n.lastEvaluatedStatus)
        assertEquals(RouteReview.Current.NOT_EVALUATED, RouteReview.current(n))
    }

    @Test fun realWritesKeepTheEvaluatedResultAndTripsDoNotTouchIt() {
        val h = open(name)
        h.recordCellTransition(TransitionCoherenceResult(HeuristicStatus.FAILED, "x", "B", "C"))
        h.recordCellTransition(TransitionCoherenceResult(HeuristicStatus.NOT_EVALUATED, "N/A: no hay fix GPS en la observación de la celda nueva.", "B", "C"))
        var r = h.route("B", "C")
        assertEquals(RouteReview.Current.NOT_EVALUATED, RouteReview.current(r))
        assertEquals(HeuristicStatus.FAILED, RouteReview.previousEvaluated(r)?.first)
        assertEquals(1, r.failedCount); assertEquals(RouteFailureHistory.COMPLETE, r.failureHistory)
        assertEquals("N/A: no hay fix GPS en la observación de la celda nueva.", r.lastExplanation)

        h.createTrip(MobilityTrip("trip-1", 1, 1, true, "B", 1, null), "B")
        h.addTripEdge("trip-1", MobilityEdge("B", "C"))
        h.addTripEdge("trip-1", MobilityEdge("C", "D"))
        h.commitMobilityTrip("trip-1", MobilityTripCloseReason.STATIC_CONFIRMED, 9_000)
        val after = h.route("B", "C")
        assertEquals(r.copy(tripCount = 1, lastTripId = "trip-1", mobilityFirstSeenMs = 9_000, mobilityLastSeenMs = 9_000), after)
        r = h.route("C", "D")
        assertEquals(RouteReview.Current.NO_HANDOVER_OBSERVED, RouteReview.current(r))
        assertEquals(RouteReview.Failures.Complete(0), RouteReview.failures(r))
    }

    @Test fun freshDatabaseHasTheNewColumns() {
        val db = open(fresh).writableDatabase
        assertEquals(CellDbHelper.DATABASE_VERSION, db.version)
        val cols = db.rawQuery("PRAGMA table_info(cell_transitions)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
        assertTrue(cols.containsAll(listOf("last_evaluated_status", "last_evaluated_ms", "last_explanation", "failed_count", "failure_history")))
    }
}
