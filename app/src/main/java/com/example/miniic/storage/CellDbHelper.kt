package com.example.miniic.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.miniic.models.HistoryRecord
import com.example.miniic.models.VerificationStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CellDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        private const val DATABASE_NAME = "miniic_history.db"
        private const val DATABASE_VERSION = 4
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
                    "$COLUMN_FAILED_H TEXT)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_VERIFIED TEXT DEFAULT 'PENDING'")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_MCC TEXT DEFAULT 'N/A'")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_SCORE INTEGER DEFAULT 100")
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_FAILED_H TEXT DEFAULT ''")
        }
    }

    fun logConnection(netType: String, cid: String, mnc: String, tac: String, mcc: String, dbm: Int, verified: VerificationStatus = VerificationStatus.PENDING, score: Int = 100, failedHeuristics: String = "") {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
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
        }
        db.insert(TABLE_HISTORY, null, values)
    }

    fun updateVerificationStatus(mnc: String, tac: String, cid: String, status: VerificationStatus) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_VERIFIED, status.name)
        }
        db.update(TABLE_HISTORY, values, "$COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_VERIFIED='PENDING'", arrayOf(cid, mnc, tac))
    }

    fun getRecords(): List<HistoryRecord> {
        val list = mutableListOf<HistoryRecord>()
        val db = this.readableDatabase
        val cursor: Cursor = db.rawQuery("SELECT * FROM $TABLE_HISTORY ORDER BY $COLUMN_ID DESC", null)
        if (cursor.moveToFirst()) {
            do {
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
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun clear() {
        val db = this.writableDatabase
        db.execSQL("DELETE FROM $TABLE_HISTORY")
    }

    fun isCellVerified(mnc: String, tac: String, cid: String): Boolean {
        val db = this.readableDatabase
        val cursor = db.rawQuery(
            "SELECT 1 FROM $TABLE_HISTORY WHERE $COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_VERIFIED='VERIFIED' LIMIT 1",
            arrayOf(cid, mnc, tac)
        )
        val exists = cursor.count > 0
        cursor.close()
        return exists
    }
}
