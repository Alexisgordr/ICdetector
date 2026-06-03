package com.alexisgordr.icdetector.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.models.SignalBaseline
import com.alexisgordr.icdetector.models.VerificationStatus
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CellDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        private const val DATABASE_NAME = "icdetector_history.db"
        private const val DATABASE_VERSION = 6
        const val TABLE_HISTORY = "history"
        const val COLUMN_ID = "id"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_NET_TYPE = "net_type"
        const val COLUMN_CID = "cid"
        const val COLUMN_MNC = "mnc"
        const val COLUMN_TAC = "tac"
        const val COLUMN_MCC = "mcc"
        const val COLUMN_DBM = "dbm"
        const val COLUMN_VERIFIED = "verified"
        const val COLUMN_SCORE = "score"
        const val COLUMN_FAILED_H = "failed_heuristics"
        const val COLUMN_LAT = "lat"
        const val COLUMN_LON = "lon"
        const val COLUMN_PCI = "pci"
        const val COLUMN_ARFCN = "arfcn"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_HISTORY (" +
                    "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COLUMN_TIMESTAMP TEXT, " +
                    "$COLUMN_NET_TYPE TEXT, " +
                    "$COLUMN_CID TEXT, " +
                    "$COLUMN_MNC TEXT, " +
                    "$COLUMN_TAC TEXT, " +
                    "$COLUMN_MCC TEXT, " +
                    "$COLUMN_DBM INTEGER, " +
                    "$COLUMN_VERIFIED TEXT, " +
                    "$COLUMN_SCORE INTEGER DEFAULT 100, " +
                    "$COLUMN_FAILED_H TEXT, " +
                    "$COLUMN_LAT REAL, " +
                    "$COLUMN_LON REAL, " +
                    "$COLUMN_PCI INTEGER, " +
                    "$COLUMN_ARFCN INTEGER)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_VERIFIED TEXT DEFAULT 'PENDING'")
        if (oldVersion < 3) db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_MCC TEXT DEFAULT 'N/A'")
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_SCORE INTEGER DEFAULT 100")
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_FAILED_H TEXT DEFAULT ''")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_LAT REAL")
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_LON REAL")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_PCI INTEGER")
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_ARFCN INTEGER")
        }
    }

    fun logConnection(
        netType: String,
        cid: String,
        mnc: String,
        tac: String,
        mcc: String,
        dbm: Int,
        verified: VerificationStatus = VerificationStatus.PENDING,
        score: Int = 100,
        failedHeuristics: String = "",
        lat: Double? = null,
        lon: Double? = null,
        pci: Int? = null,
        arfcn: Int? = null
    ): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            put(COLUMN_TIMESTAMP, sdf.format(Date()))
            put(COLUMN_NET_TYPE, netType)
            put(COLUMN_CID, cid)
            put(COLUMN_MNC, mnc)
            put(COLUMN_TAC, tac)
            put(COLUMN_MCC, mcc)
            put(COLUMN_DBM, dbm)
            put(COLUMN_VERIFIED, verified.name)
            put(COLUMN_SCORE, score)
            put(COLUMN_FAILED_H, failedHeuristics)
            if (lat != null) put(COLUMN_LAT, lat)
            if (lon != null) put(COLUMN_LON, lon)
            if (pci != null) put(COLUMN_PCI, pci)
            if (arfcn != null) put(COLUMN_ARFCN, arfcn)
        }
        return db.insert(TABLE_HISTORY, null, values)
    }

    fun getKnownStatus(mnc: String, tac: String, cid: String, mcc: String, currentLat: Double? = null, currentLon: Double? = null): VerificationStatus {
        val db = this.readableDatabase
        // Buscamos cualquier registro previo de esta antena que no sea PENDING o ERROR
        val query = "SELECT $COLUMN_VERIFIED, $COLUMN_LAT, $COLUMN_LON, $COLUMN_TIMESTAMP FROM $TABLE_HISTORY " +
                    "WHERE $COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_MCC=? " +
                    "AND $COLUMN_VERIFIED IN ('VERIFIED', 'NOT_FOUND') " +
                    "ORDER BY CASE WHEN $COLUMN_VERIFIED='VERIFIED' THEN 1 ELSE 2 END ASC, $COLUMN_ID DESC LIMIT 1"
        
        val cursor = db.rawQuery(query, arrayOf(cid, mnc, tac, mcc))
        var status = VerificationStatus.PENDING
        try {
        
        if (cursor.moveToFirst()) {
            val savedStatusStr = cursor.getString(0)
            val savedLat = if (cursor.isNull(1)) null else cursor.getDouble(1)
            val savedLon = if (cursor.isNull(2)) null else cursor.getDouble(2)
            val savedTimeStr = cursor.getString(3)
            
            val savedStatus = try { VerificationStatus.valueOf(savedStatusStr) } catch(_: Exception) { VerificationStatus.PENDING }
            
            if (savedStatus == VerificationStatus.VERIFIED) {
                if (currentLat == null || currentLon == null || savedLat == null || savedLon == null) {
                    status = VerificationStatus.VERIFIED
                } else {
                    val results = FloatArray(1)
                    Location.distanceBetween(currentLat, currentLon, savedLat, savedLon, results)
                    status = if (results[0] < 5000) VerificationStatus.VERIFIED else VerificationStatus.PENDING
                }
            } else if (savedStatus == VerificationStatus.NOT_FOUND) {
                // Heurística de re-intento: si pasó más de 1 hora, volvemos a intentar
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    val savedDate = sdf.parse(savedTimeStr)
                    val now = Date()
                    if (savedDate != null && (now.time - savedDate.time) > 3600000) { // 1 hora
                        status = VerificationStatus.PENDING
                    } else {
                        status = VerificationStatus.NOT_FOUND
                    }
                } catch (_: Exception) {
                    status = VerificationStatus.NOT_FOUND
                }
            }
        }
        } finally {
            cursor.close()
        }
        return status
    }

    fun updateVerificationStatus(mnc: String, tac: String, cid: String, status: VerificationStatus, lat: Double? = null, lon: Double? = null, mcc: String? = null) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_VERIFIED, status.name)
            if (lat != null) put(COLUMN_LAT, lat)
            if (lon != null) put(COLUMN_LON, lon)
        }
        val where = if (mcc != null) "$COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_MCC=? AND $COLUMN_VERIFIED='PENDING'"
                    else "$COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_VERIFIED='PENDING'"
        val args = if (mcc != null) arrayOf(cid, mnc, tac, mcc) else arrayOf(cid, mnc, tac)
        db.update(TABLE_HISTORY, values, where, args)
    }

    fun getRecords(): List<HistoryRecord> {
        val list = mutableListOf<HistoryRecord>()
        val db = this.readableDatabase
        val cursor: Cursor = db.rawQuery("SELECT * FROM $TABLE_HISTORY ORDER BY $COLUMN_ID DESC", null)
        try {
        if (cursor.moveToFirst()) {
            do {
                // Leer coordenadas (pueden ser NULL)
                val lat = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LAT))) null 
                          else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LAT))
                val lon = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_LON))) null 
                          else cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LON))
                val pci = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_PCI))) null
                          else cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_PCI))
                val arfcn = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_ARFCN))) null
                          else cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ARFCN))
                
                list.add(
                    HistoryRecord(
                        timestamp = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                        netType = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NET_TYPE)),
                        cid = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CID)),
                        mnc = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MNC)),
                        tac = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TAC)),
                        mcc = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MCC)),
                        dbm = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_DBM)),
                        verified = try { 
                            VerificationStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_VERIFIED))) 
                        } catch(_: Exception) { 
                            VerificationStatus.PENDING 
                        },
                        score = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_SCORE)),
                        failedHeuristics = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_FAILED_H)) ?: "",
                        lat = lat,
                        lon = lon,
                        pci = pci,
                        arfcn = arfcn
                    )
                )
            } while (cursor.moveToNext())
        }
        } finally {
            cursor.close()
        }
        return list
    }

    fun clear() {
        val db = this.writableDatabase
        db.execSQL("DELETE FROM $TABLE_HISTORY")
    }

    fun updateNullCoordinates(
        cellId: String, mnc: String, tac: String, mcc: String,
        lat: Double, lon: Double
    ): Int {
        val db = this.writableDatabase
        return try {
            val values = ContentValues().apply {
                put(COLUMN_LAT, lat)
                put(COLUMN_LON, lon)
            }
            db.update(
                TABLE_HISTORY,
                values,
                "$COLUMN_CID = ? AND $COLUMN_MNC = ? AND $COLUMN_TAC = ? AND $COLUMN_MCC = ? AND $COLUMN_LAT IS NULL",
                arrayOf(cellId, mnc, tac, mcc)
            )
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Obtiene registros previos de una misma Cell ID (excluyendo la ubicación actual)
     * Limitado a los últimos 30 días para evitar datos obsoletos.
     */
    fun getPreviousCellHistory(
        cellId: String,
        mnc: String,
        tac: String,
        excludeCurrentLocation: Location
    ): List<HistoryRecord> {
        val history = mutableListOf<HistoryRecord>()
        val db = this.readableDatabase
        
        // Excluir registros muy recientes (últimos 5 minutos) para evitar duplicados de la misma sesión
        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
        // Decaimiento temporal: Solo considerar registros de los últimos 30 días
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val recentThreshold = dateFormat.format(Date(fiveMinutesAgo))
        val oldThreshold = dateFormat.format(Date(thirtyDaysAgo))
        
        val query = """
            SELECT * 
            FROM $TABLE_HISTORY 
            WHERE $COLUMN_CID = ? 
              AND $COLUMN_MNC = ? 
              AND $COLUMN_TAC = ?
              AND $COLUMN_LAT IS NOT NULL 
              AND $COLUMN_LON IS NOT NULL
              AND $COLUMN_TIMESTAMP < ?
              AND $COLUMN_TIMESTAMP > ?
            ORDER BY $COLUMN_ID DESC
            LIMIT 20
        """.trimIndent()
        
        val cursor = db.rawQuery(query, arrayOf(cellId, mnc, tac, recentThreshold, oldThreshold))
        try {
        
        if (cursor.moveToFirst()) {
            do {
                val lat = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LAT))
                val lon = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LON))
                
                // Evitar incluir la ubicación actual si está muy cerca (misma sesión)
                val results = FloatArray(1)
                Location.distanceBetween(
                    excludeCurrentLocation.latitude, excludeCurrentLocation.longitude,
                    lat, lon, results
                )
                
                // Solo añadir si está a más de 50m de la ubicación actual
                if (results[0] > 50) {
                    val pci = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_PCI))) null
                              else cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_PCI))
                    val arfcn = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_ARFCN))) null
                              else cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ARFCN))

                    history.add(
                        HistoryRecord(
                            timestamp = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                            netType = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NET_TYPE)),
                            cid = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CID)),
                            mnc = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MNC)),
                            tac = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TAC)),
                            mcc = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MCC)),
                            dbm = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_DBM)),
                            verified = try { 
                                VerificationStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_VERIFIED))) 
                            } catch(_: Exception) { 
                                VerificationStatus.PENDING 
                            },
                            score = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_SCORE)),
                            failedHeuristics = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_FAILED_H)) ?: "",
                            lat = lat,
                            lon = lon,
                            pci = pci,
                            arfcn = arfcn
                        )
                    )
                }
            } while (cursor.moveToNext())
        }
        } finally {
            cursor.close()
        }
        
        return history
    }

    /**
     * Línea base de potencia (dBm) de una celda a partir de observaciones cercanas
     * a la ubicación actual (mismo sitio), últimos 30 días. null si no hay muestras
     * suficientes (rodaje). No modifica el esquema; solo lee columnas existentes.
     */
    fun getCellSignalBaseline(
        cellId: String,
        mnc: String,
        tac: String,
        nearLocation: Location,
        radiusMeters: Float = 500f,
        minSamples: Int = 5
    ): SignalBaseline? {
        val db = this.readableDatabase
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val oldThreshold = dateFormat.format(Date(thirtyDaysAgo))

        val query = """
            SELECT $COLUMN_DBM, $COLUMN_LAT, $COLUMN_LON
            FROM $TABLE_HISTORY
            WHERE $COLUMN_CID = ?
              AND $COLUMN_MNC = ?
              AND $COLUMN_TAC = ?
              AND $COLUMN_LAT IS NOT NULL
              AND $COLUMN_LON IS NOT NULL
              AND $COLUMN_TIMESTAMP > ?
            ORDER BY $COLUMN_ID DESC
            LIMIT 200
        """.trimIndent()

        val cursor = db.rawQuery(query, arrayOf(cellId, mnc, tac, oldThreshold))
        val samples = mutableListOf<Int>()
        try {
        if (cursor.moveToFirst()) {
            do {
                val dbm = cursor.getInt(0)
                val lat = cursor.getDouble(1)
                val lon = cursor.getDouble(2)
                val results = FloatArray(1)
                Location.distanceBetween(
                    nearLocation.latitude, nearLocation.longitude, lat, lon, results
                )
                if (results[0] <= radiusMeters && dbm in -140..-30) {
                    samples.add(dbm)
                }
            } while (cursor.moveToNext())
        }
        } finally {
            cursor.close()
        }

        if (samples.size < minSamples) return null

        val mean = samples.average()
        val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
        val stdDev = sqrt(variance)

        return SignalBaseline(
            sampleCount = samples.size,
            meanDbm = mean,
            stdDevDbm = stdDev,
            minDbm = samples.minOrNull()!!,
            maxDbm = samples.maxOrNull()!!
        )
    }
}
