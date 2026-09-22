package com.alexisgordr.icdetector.storage

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.LOCAL_TRUST_RECONFIGURATION_REASON
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.SUBTHRESHOLD_PREFIX
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class CellDbHelperTrustEvidenceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var helper: CellDbHelper

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        helper = CellDbHelper(context)
        helper.writableDatabase
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun frequentCellUsesFullTemporalWindowAndBoundedDetailedScan() {
        insertRows(
            countPerDay = 50,
            days = 14,
            pci = 179,
            arfcn = 2850,
            failedHeuristics = "OK"
        )

        val evidence = helper.getLocalCellTrustEvidence(cell(pci = 179, arfcn = 2850))

        assertEquals(700, evidence.cleanObservations)
        assertEquals(14, evidence.distinctDays)
        assertEquals(42, evidence.cappedCleanObservations)
        assertEquals(500, evidence.rfObservations)
        assertEquals(500, evidence.locatedObservations)
        assertEquals(setOf(179), evidence.knownPcis)
        assertEquals(setOf(2850), evidence.knownArfcns)
    }

    @Test
    fun reconfigurationUsesFullWindowButRecentOldPairStillVetoesIt() {
        insertRows(
            countPerDay = 20,
            days = 1,
            pci = 179,
            arfcn = 2850,
            failedHeuristics = "OK",
            firstDayAgo = 20
        )
        insertRows(
            countPerDay = 50,
            days = 14,
            pci = 311,
            arfcn = 2850,
            failedHeuristics = "$SUBTHRESHOLD_PREFIX $LOCAL_TRUST_RECONFIGURATION_REASON (PCI)"
        )

        val before = helper.getLocalCellTrustEvidence(cell(pci = 311, arfcn = 2850))
            .reconfigurationCandidate
        assertNotNull(before)
        assertEquals(14, before!!.distinctDays)
        assertEquals(42, before.cappedObservations)
        assertEquals(500, before.locatedObservations)
        assertFalse(before.oldPairSeenRecently)

        insertRows(
            countPerDay = 1,
            days = 1,
            pci = 179,
            arfcn = 2850,
            failedHeuristics = "OK"
        )
        val after = helper.getLocalCellTrustEvidence(cell(pci = 311, arfcn = 2850))
            .reconfigurationCandidate
        assertNotNull(after)
        assertTrue(after!!.oldPairSeenRecently)
    }

    private fun insertRows(
        countPerDay: Int,
        days: Int,
        pci: Int,
        arfcn: Int,
        failedHeuristics: String,
        firstDayAgo: Int = days - 1
    ) {
        val db = helper.writableDatabase
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        db.beginTransaction()
        try {
            repeat(days) { day ->
                val timestamp = format.format(Date(System.currentTimeMillis() -
                    (firstDayAgo - day) * DAY_MS))
                repeat(countPerDay) {
                    val values = ContentValues().apply {
                        put(CellDbHelper.COLUMN_TIMESTAMP, timestamp)
                        put(CellDbHelper.COLUMN_NET_TYPE, "4G LTE")
                        put(CellDbHelper.COLUMN_CID, CID)
                        put(CellDbHelper.COLUMN_MNC, MNC)
                        put(CellDbHelper.COLUMN_TAC, TAC)
                        put(CellDbHelper.COLUMN_MCC, MCC)
                        put(CellDbHelper.COLUMN_DBM, -90)
                        put(CellDbHelper.COLUMN_VERIFIED, "NOT_FOUND")
                        put(CellDbHelper.COLUMN_SCORE, 100)
                        put(CellDbHelper.COLUMN_FAILED_H, failedHeuristics)
                        put(CellDbHelper.COLUMN_LAT, 42.8)
                        put(CellDbHelper.COLUMN_LON, -1.6)
                        put(CellDbHelper.COLUMN_PCI, pci)
                        put(CellDbHelper.COLUMN_ARFCN, arfcn)
                        put(CellDbHelper.COLUMN_RADIO, RadioTech.LTE.name)
                    }
                    check(db.insertOrThrow(CellDbHelper.TABLE_HISTORY, null, values) > 0)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun cell(pci: Int, arfcn: Int) = CellData(
        isRegistered = true,
        networkType = "4G LTE",
        cellId = CID,
        mnc = MNC,
        tac = TAC,
        dbm = -90,
        mcc = MCC,
        radioTech = RadioTech.LTE,
        pci = pci,
        arfcn = arfcn
    )

    private companion object {
        const val DATABASE_NAME = "icdetector_history.db"
        const val CID = "79362070"
        const val MNC = "07"
        const val TAC = "31601"
        const val MCC = "214"
        const val DAY_MS = 24L * 60 * 60 * 1_000
    }
}
