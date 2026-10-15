package com.alexisgordr.icdetector.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.core.ObservationContext
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.service.LocalProfileController
import com.alexisgordr.icdetector.service.ObservationRow
import com.alexisgordr.icdetector.service.logObservation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 3.0 (L2) — Con SQLite real: las filas periódica y de alarma de una lectura, una sola evaluación. */
@RunWith(AndroidJUnit4::class)
class ProfileObservationDedupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "profile_observation_dedup_test.db"
    private lateinit var db: CellDbHelper
    private val cell = CellData(isRegistered = true, networkType = "4G LTE", cellId = "100", mnc = "07", tac = "1",
        dbm = -80, mcc = "214", radioTech = RadioTech.LTE, rsrq = -10)

    @Before fun before() { context.deleteDatabase(name); db = CellDbHelper(context, context.getDatabasePath(name).absolutePath); db.writableDatabase }
    @After fun after() { db.close(); context.deleteDatabase(name) }

    private fun controller() = LocalProfileController(db, context.getSharedPreferences("profile_dedup_test", Context.MODE_PRIVATE), "google/pixel 8",
        clock = { 1_790_000_100_000L })

    private fun write(row: ObservationRow, c: LocalProfileController = controller()): Long =
        db.logObservation(row, "3.0").also { c.onRowWritten(row, it) }

    private fun count(sql: String) = db.readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }

    @Test fun twoRowsOfOneObservationGiveOneEvaluationAndBothRowsStay() {
        val ctx = ObservationContext(1_790_000_000_000L, null, null)
        write(ObservationRow(cell, "OK", ctx))
        write(ObservationRow(cell.copy(suspiciousReason = "H3"), "H3", ctx))
        assertEquals(2, count("SELECT COUNT(*) FROM history WHERE observation_id='${ctx.observationId}'"))
        assertEquals(1, count("SELECT COUNT(*) FROM profile_evaluations WHERE observation_id='${ctx.observationId}'"))
        assertEquals(1, count("SELECT COUNT(*) FROM profile_evaluations"))
    }

    @Test fun distinctObservationsWithEqualValuesAreBothEvaluatedAndARestartStillDeduplicates() {
        val a = ObservationContext(1_790_000_000_000L, null, null)
        val b = ObservationContext(1_790_000_000_000L, null, null)
        write(ObservationRow(cell, "OK", a)); write(ObservationRow(cell, "OK", b))
        assertEquals(2, count("SELECT COUNT(*) FROM profile_evaluations"))
        // Proceso nuevo (controlador y helper nuevos): la evaluación guardada sigue deduplicando.
        db.close(); db = CellDbHelper(context, context.getDatabasePath(name).absolutePath)
        write(ObservationRow(cell, "H3", a), controller())
        assertEquals(2, count("SELECT COUNT(*) FROM profile_evaluations"))
        assertEquals(3, count("SELECT COUNT(*) FROM history"))
    }
}
