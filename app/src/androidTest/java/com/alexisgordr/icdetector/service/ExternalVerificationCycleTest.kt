package com.alexisgordr.icdetector.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.core.ObservedFix
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.VerificationStatus
import com.alexisgordr.icdetector.storage.CellDbHelper
import com.alexisgordr.icdetector.utils.ExportUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 3.1.0 — Ciclo completo de escritura sin verificación externa: el mismo controlador y la misma
 * escritura que usa el servicio, contra SQLite real. Cada fila (traspaso, periódica y alarma
 * confirmada) queda como PENDING con api_lat/api_lon vacías, también en el CSV, aunque la celda
 * llegue con otro estado.
 */
@RunWith(AndroidJUnit4::class)
class ExternalVerificationCycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CellDbHelper

    @Before fun before() { context.deleteDatabase(NAME); db = CellDbHelper(context); db.writableDatabase }
    @After fun after() { db.close(); context.deleteDatabase(NAME) }

    @Test fun everyRowOfACycleIsPendingWithoutApiCoordinates() {
        var now = System.currentTimeMillis()
        val persistence = ObservationPersistenceController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            insert = { db.logObservation(it, "3.1.0+test") },
            location = { ObservedFix(40.4168, -3.7038, 8f) },
            onWrite = {},
            onPeriodicMissingLocation = {},
            writeDispatcher = Dispatchers.Unconfined,
            clock = { now }
        )
        // Un estado antiguo en memoria no puede llegar a la fila.
        val cell = CellData(isRegistered = true, networkType = "4G LTE", cellId = "100", mnc = "07", tac = "1",
            dbm = -80, mcc = "214", radioTech = RadioTech.LTE, verified = VerificationStatus.VERIFIED)
        persistence.recordHandover(cell, persistence.capture())
        now += 10L * 60_000L
        persistence.recordPeriodicIfDue(cell, screenOn = true, context = persistence.capture())
        now += 1_000L
        persistence.recordConfirmedAlarm(cell.copy(isSuspicious = true, suspiciousReason = "H6"), persistence.capture())

        val records = db.getRecords()
        assertEquals(3, records.size)
        val header = ExportUtils.CSV_HEADER.split(",")
        records.forEach { record ->
            assertEquals(VerificationStatus.PENDING, record.verified)
            assertNull(record.apiLat)
            assertNull(record.apiLon)
            val row = ExportUtils.csvRow(record).split(",")
            assertEquals("PENDING", row[header.indexOf("Verified")])
            assertEquals("", row[header.indexOf("ApiLat")])
            assertEquals("", row[header.indexOf("ApiLon")])
        }
    }

    private companion object { const val NAME = "icdetector_history.db" }
}
