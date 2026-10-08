package com.alexisgordr.icdetector.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2.10.10 — Un fix GPS solo rellena una fila sin coordenadas si es reciente. Antes rellenaba la
 * última fila de la celda aunque fuera de hacía minutos, y esa fila antigua quedaba con la
 * posición actual (contaminando H11/H13/H16).
 */
@RunWith(AndroidJUnit4::class)
class CoordinateBackfillWindowTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CellDbHelper

    @Before fun before() { context.deleteDatabase(NAME); db = CellDbHelper(context); db.writableDatabase }
    @After fun after() { db.close(); context.deleteDatabase(NAME) }

    private fun row(observedAtMs: Long) = db.logConnection(
        observedAtMs = observedAtMs, netType = "LTE", cid = "100", mnc = "07", tac = "1",
        mcc = "214", dbm = -90, radio = RadioTech.LTE
    )

    private fun fill(fixTimeMs: Long) = db.updateNullCoordinates(
        "100", "07", "1", "214", RadioTech.LTE, 40.4, -3.7, fixTimeMs = fixTimeMs
    )

    @Test fun anOldRowWithoutCoordinatesStaysEmpty() {
        val now = System.currentTimeMillis()
        row(now - 15 * 60_000L)                       // fila de hace 15 min sin GPS
        assertEquals(0, fill(now))
        assertNull(db.getRecords().first().lat)
    }

    @Test fun aRecentRowIsFilled() {
        val now = System.currentTimeMillis()
        row(now - 30_000L)                            // espera normal del fix preciso
        assertEquals(1, fill(now))
        assertEquals(40.4, db.getRecords().first().lat!!, 1e-9)
    }

    private companion object { const val NAME = "icdetector_history.db" }
}
