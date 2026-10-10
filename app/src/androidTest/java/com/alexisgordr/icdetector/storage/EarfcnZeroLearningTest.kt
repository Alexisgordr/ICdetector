package com.alexisgordr.icdetector.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.models.CellRfStability
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 3.0 (#22) — Una celda LTE madura en el EARFCN 0 (Banda 1) aprende sus PCI en la portadora 0, y
 * no se mezcla con las filas sin frecuencia (UNKNOWN_ARFCN).
 */
@RunWith(AndroidJUnit4::class)
class EarfcnZeroLearningTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CellDbHelper

    @Before fun before() { context.deleteDatabase(NAME); db = CellDbHelper(context); db.writableDatabase }
    @After fun after() { db.close(); context.deleteDatabase(NAME) }

    @Test fun matureHistoryOnEarfcnZeroKeepsItsOwnCarrier() {
        val now = System.currentTimeMillis()
        // 6 observaciones limpias repartidas en 3 días (madurez: 5 en 2 días).
        repeat(6) { i ->
            db.logConnection(
                observedAtMs = now - (i / 2) * 86_400_000L - i * 60_000L, netType = "LTE",
                cid = "100", mnc = "07", tac = "1", mcc = "214", dbm = -90, radio = RadioTech.LTE,
                pci = 10, arfcn = 0, failedHeuristics = "OK"
            )
        }
        val stability = db.getCellRfStability("100", "07", "1", "214", RadioTech.LTE)
        assertEquals(6, stability.totalObservations)
        assertEquals(listOf(10 to 6), stability.pciByArfcn[0])
        assertFalse(stability.pciByArfcn.containsKey(CellRfStability.UNKNOWN_ARFCN))
    }

    private companion object { const val NAME = "icdetector_history.db" }
}
