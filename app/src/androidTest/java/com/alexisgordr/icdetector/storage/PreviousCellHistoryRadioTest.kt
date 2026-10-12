package com.alexisgordr.icdetector.storage

import android.content.ContentValues
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.core.RadioChannels
import com.alexisgordr.icdetector.core.ThreatAnalyzer
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 3.0 — getPreviousCellHistory devuelve registros con su tecnología, de modo que H11 puede comparar
 * PCI y canal válidos, y los centinelas siguen sin contar como discrepancia. Los datos superan los
 * filtros reales: madurez (≥5 muestras limpias en ≥2 días), score ≥85 sin fallos, GPS, más de
 * 5 minutos y menos de 30 días, y a más de 50 m de la ubicación actual.
 */
@RunWith(AndroidJUnit4::class)
class PreviousCellHistoryRadioTest {
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
    fun recordsKeepTheirTechnologySoValidRfDifferencesCountAndSentinelsDoNot() {
        // 3 días × 3 filas con PCI/EARFCN válidos y 1 día × 2 filas con centinelas, todas limpias.
        insertRows(daysAgo = listOf(1, 2, 3), perDay = 3, pci = 100, arfcn = 1300)
        insertRows(daysAgo = listOf(4), perDay = 2, pci = Int.MAX_VALUE, arfcn = Int.MAX_VALUE)

        val here = Location("test").apply { latitude = 43.5; longitude = -1.6 }   // ~78 km de las filas
        val history = helper.getPreviousCellHistory(CID, MNC, TAC, MCC, RadioTech.LTE, here)

        assertEquals("devuelve todos los registros que pasan los filtros", 11, history.size)
        assertTrue("conservan su tecnología", history.all { it.radio == RadioTech.LTE })

        val valid = history.filter { it.pci == 100 && it.arfcn == 1300 }
        val sentinel = history.filter { it.pci == Int.MAX_VALUE && it.arfcn == Int.MAX_VALUE }
        assertEquals(9, valid.size)
        assertEquals(2, sentinel.size)

        // Lectura actual con otro PCI y otro canal LTE válidos: discrepancia real (+25).
        val current = cell(pci = 200, arfcn = 6300)
        valid.forEach { record ->
            assertEquals(RadioChannels.RfDifference(physicalId = true, channel = true),
                RadioChannels.rfDifference(current.radioTech, current.pci, current.arfcn, record.radio, record.pci, record.arfcn))
            assertEquals(65, ThreatAnalyzer.mobileCellRecord(current, record, distance = 30_000.0, threshold = 10_000.0).severity)
        }
        // Centinelas guardados: sin discrepancia RF, solo la severidad geográfica.
        sentinel.forEach { record ->
            assertEquals(RadioChannels.RfDifference(physicalId = false, channel = false),
                RadioChannels.rfDifference(current.radioTech, current.pci, current.arfcn, record.radio, record.pci, record.arfcn))
            assertEquals(40, ThreatAnalyzer.mobileCellRecord(current, record, distance = 30_000.0, threshold = 10_000.0).severity)
        }
    }

    private fun insertRows(daysAgo: List<Int>, perDay: Int, pci: Int, arfcn: Int) {
        val db = helper.writableDatabase
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        db.beginTransaction()
        try {
            daysAgo.forEach { ago ->
                repeat(perDay) { i ->
                    val atMs = System.currentTimeMillis() - ago * DAY_MS - i * 60_000L
                    val values = ContentValues().apply {
                        put(CellDbHelper.COLUMN_TIMESTAMP, format.format(Date(atMs)))
                        put(CellDbHelper.COLUMN_NET_TYPE, "4G LTE")
                        put(CellDbHelper.COLUMN_CID, CID)
                        put(CellDbHelper.COLUMN_MNC, MNC)
                        put(CellDbHelper.COLUMN_TAC, TAC)
                        put(CellDbHelper.COLUMN_MCC, MCC)
                        put(CellDbHelper.COLUMN_DBM, -90)
                        put(CellDbHelper.COLUMN_VERIFIED, "NOT_FOUND")
                        put(CellDbHelper.COLUMN_SCORE, 100)
                        put(CellDbHelper.COLUMN_FAILED_H, "OK")
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
        isRegistered = true, networkType = "4G LTE", cellId = CID, mnc = MNC, tac = TAC, dbm = -90, mcc = MCC,
        radioTech = RadioTech.LTE, pci = pci, arfcn = arfcn
    )

    private companion object {
        const val DATABASE_NAME = "icdetector_history.db"
        const val CID = "79362071"
        const val MNC = "07"
        const val TAC = "31601"
        const val MCC = "214"
        const val DAY_MS = 24L * 60 * 60 * 1_000
    }
}
