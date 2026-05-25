/*
 * ICdetection - IMSI-Catcher Detection Tool
 * Copyright (C) 2026 Alexis Gómez Rodríguez
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.miniic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// Database Helper for history logging
class CellDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        private const val DATABASE_NAME = "miniic_history.db"
        private const val DATABASE_VERSION = 3
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
                    "$COLUMN_VERIFIED TEXT)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_VERIFIED TEXT DEFAULT 'PENDING'")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_MCC TEXT DEFAULT 'N/A'")
        }
    }

    fun logConnection(netType: String, cid: String, mnc: String, tac: String, mcc: String, dbm: Int, verified: VerificationStatus = VerificationStatus.PENDING) {
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
                        }
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

data class HistoryRecord(
    val timestamp: String,
    val netType: String,
    val cid: String,
    val mnc: String,
    val tac: String,
    val mcc: String,
    val dbm: Int,
    val verified: VerificationStatus = VerificationStatus.PENDING
)

data class CellData(
    val isRegistered: Boolean,
    val networkType: String,
    val cellId: String,
    val mnc: String,
    val tac: String,
    val dbm: Int,
    val mcc: String = "N/A",
    val verified: VerificationStatus = VerificationStatus.PENDING,
    val isSuspicious: Boolean = false,
    val suspiciousReason: String? = null,
    val timingAdvance: Int? = null,
    val arfcn: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null
)

enum class VerificationStatus {
    PENDING, VERIFIED, NOT_FOUND, ERROR
}

// Background service doing 2-second polls
class MiniICService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    
    private val _cellFlow = MutableStateFlow<List<CellData>>(emptyList())
    val cellFlow: StateFlow<List<CellData>> = _cellFlow

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var dbHelper: CellDbHelper
    private lateinit var locationManager: LocationManager
    private var toneGenerator: ToneGenerator? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var displayInfoCallback: TelephonyCallback? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var lastDisplayInfo: TelephonyDisplayInfo? = null
    private var isScreenOn = true
    private var isServiceRunning = false
    private var lastAirplaneTriggerTime = 0L

    // API credentials
    var openCellIdKey: String = ""
    var wigleApiName: String = ""
    var wigleApiToken: String = ""
    private val client = OkHttpClient()
    private val verificationCache = ConcurrentHashMap<String, VerificationStatus>()

    // Options
    var alarmThreshold = -50f
    var isStrongSignalAlarmEnabled = true
    var is3gAirplaneModeEnabled = true
    var isProxyEnabled = false

    // Tracking states
    private var prevCid: String? = null
    private var prevNetType: String? = null
    private var prevDbm: Int? = null
    private var isCallbackWorking = false
    private var connectionRetryCount = 0
    private val cellChangeHistory = mutableListOf<Pair<String, Long>>()

    inner class LocalBinder : Binder() {
        fun getService(): MiniICService = this@MiniICService
    }

    fun forceRefresh() {
        _cellFlow.value = emptyList() // Clear UI momentarily
        requestFreshCellInfo()
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        dbHelper = CellDbHelper(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        
        // Load API Key and Proxy from preferences
        val prefs = getSharedPreferences("miniic_prefs", MODE_PRIVATE)
        openCellIdKey = prefs.getString("opencellid_key", "") ?: ""
        wigleApiName = prefs.getString("wigle_api_name", "") ?: ""
        wigleApiToken = prefs.getString("wigle_api_token", "") ?: ""
        isProxyEnabled = prefs.getBoolean("proxy_enabled", false)

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Sondeo activo"))

        registerTelephonyCallback()
        registerDisplayInfoCallback()
        registerScreenReceiver()

        // Adaptive polling: slower when screen is off to save battery
        scope.launch(Dispatchers.Default) {
            while (isActive && isServiceRunning) {
                val delayTime = if (isScreenOn) 2000L else 10000L
                
                // 1. Comprobamos si el usuario ha activado el Modo Avión
                val isAirplaneModeOn = Settings.Global.getInt(
                    contentResolver, 
                    Settings.Global.AIRPLANE_MODE_ON, 0
                ) != 0

                if (isAirplaneModeOn) {
                    // 2. Si está activo, forzamos la limpieza de la pantalla y la notificación
                    _cellFlow.value = emptyList()
                    updateNotificationText("Sin señal / Modo Avión")
                } else {
                    // 3. Si no está activo, solicitamos datos de antenas de forma normal
                    requestFreshCellInfo()
                }

                delay(delayTime)
            }
        }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                isScreenOn = intent?.action == Intent.ACTION_SCREEN_ON
            }
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun registerTelephonyCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val callback = if (Build.VERSION.SDK_INT >= 34) {
                        // Prepared for Android 14+ listeners
                        object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                                isCallbackWorking = true
                                processCellInfo(cellInfo)
                            }
                            
                            // Implementation note: CipheringStatusListener and CellularIdentifierDisclosureListener 
                            // are part of TelephonyCallback in API 34 but may require specific SDK configurations to resolve.
                        }
                    } else {
                        object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                                isCallbackWorking = true
                                processCellInfo(cellInfo)
                            }
                        }
                    }
                    telephonyCallback = callback
                    telephonyCallback?.let {
                        telephonyManager.registerTelephonyCallback(mainExecutor, it)
                    }
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun registerDisplayInfoCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    displayInfoCallback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                        override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                            isCallbackWorking = true
                            lastDisplayInfo = displayInfo
                            // Force refresh when network type or icon changes
                            requestFreshCellInfo()
                        }
                    }
                    displayInfoCallback?.let {
                        telephonyManager.registerTelephonyCallback(mainExecutor, it)
                    }
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun requestFreshCellInfo() {
        // En lugar de machacar los callbacks cada 2 segundos, esperamos 4 ciclos (8 segundos)
        // para dar tiempo a que los listeners se asienten y el sistema operativo procese los permisos
        if (!isCallbackWorking && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)) {
            connectionRetryCount++
            if (connectionRetryCount >= 4) {
                connectionRetryCount = 0
                Log.w("MiniIC", "Primer arranque o pérdida de señal detectada. Re-instanciando componentes de radio.")
                try {
                    // Forzamos la recarga del servicio del sistema para refrescar el token de permisos
                    telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

                    telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
                    displayInfoCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
                } catch (_: Exception) {}

                registerTelephonyCallback()
                registerDisplayInfoCallback()
            }
        }

        if (telephonyCallback == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerTelephonyCallback()
        }
        if (displayInfoCallback == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerDisplayInfoCallback()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                telephonyManager.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        processCellInfo(cellInfo)
                    }
                    override fun onError(errorCode: Int, detail: Throwable?) {
                        if (ContextCompat.checkSelfPermission(this@MiniICService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            try {
                                processCellInfo(telephonyManager.allCellInfo)
                            } catch (e: SecurityException) { e.printStackTrace() }
                        }
                    }
                })
            } catch (e: SecurityException) { e.printStackTrace() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        scope.cancel()
        toneGenerator?.release()
        screenReceiver?.let { unregisterReceiver(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
            displayInfoCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
        }
    }

    private fun processCellInfo(infoList: List<CellInfo>?) {
        try {
            // SI LLEGAN DATOS VÁLIDOS, EL CANAL FUNCIONA. Bloqueamos el bucle de reenganche.
            if (!infoList.isNullOrEmpty()) {
                isCallbackWorking = true
                connectionRetryCount = 0
            }

            val list = mutableListOf<CellData>()
            infoList?.forEach { info ->
                val parsed = parseCell(info)
                // Filter out invalid readings (Int.MAX_VALUE is Android's "unavailable" constant)
                if (parsed != null && parsed.dbm != Int.MAX_VALUE && parsed.dbm < 100) {
                    list.add(parsed)
                }
            }

            if (list.isEmpty()) {
                _cellFlow.value = emptyList()
                updateNotificationText("Sin señal / Modo Avión")
                return
            }

            // Identify neighbor cells (not registered)
            val neighbors = list.filter { !it.isRegistered }

            val analyzedList = list.map { cell ->
                analyzeThreats(cell, neighbors)
            }

            val sorted = analyzedList.sortedWith(
                compareByDescending<CellData> { it.isRegistered }
                    .thenByDescending { it.dbm }
            )

            // Trigger verification for the active cell if needed
            val active = sorted.firstOrNull { it.isRegistered }
            if (active != null) {
                verifyCell(active)
                
                // Update with cached verification status if available
                val cacheKey = "${active.mcc}-${active.mnc}-${active.tac}-${active.cellId}"
                val status = verificationCache[cacheKey] ?: VerificationStatus.PENDING
                
                _cellFlow.value = sorted.map { 
                    if (it.isRegistered) it.copy(verified = status) else it 
                }
                
                checkAlerts(active.copy(verified = status))
                updateNotification(active.copy(verified = status))
            } else {
                _cellFlow.value = emptyList() // Clear list if no active cell to avoid "static" values
                updateNotificationText("Buscando red...")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun analyzeThreats(active: CellData, neighbors: List<CellData>): CellData {
        var suspicious = false
        val reasons = mutableListOf<String>()

        // 1. Neighbor analysis (Empty list while strong signal)
        if (neighbors.isEmpty() && active.dbm >= -80) {
            suspicious = true
            reasons.add("Celda aislada (sin vecinos)")
        }

        // 2. Signal Gap analysis (Less sensitive: 35dB and must be strong active)
        val nextStrongest = neighbors.maxOfOrNull { it.dbm }
        if (nextStrongest != null && active.dbm >= -75 && active.dbm - nextStrongest > 35) {
            suspicious = true
            reasons.add("Salto de potencia anómalo (>35dB)")
        }

        // 3. MNC/MCC Inconsistency
        val differentMcc = neighbors.filter { it.mcc != "N/A" && it.mcc != active.mcc }
        if (differentMcc.isNotEmpty()) {
            suspicious = true
            reasons.add("Inconsistencia de MCC en el área")
        }
        
        // 4. Multiple MNCs in area (Suspicious for IMSI catchers spoofing multiple IDs)
        val uniqueMncs = (neighbors.map { it.mnc } + active.mnc).filter { it != "N/A" }.distinct()
        if (uniqueMncs.size > 3) {
            suspicious = true
            reasons.add("Demasiados MNCs detectados (>3)")
        }

        // 7. TAC Deviation Audit
        if (neighbors.isNotEmpty() && active.tac != "N/A") {
            val neighborTacs = neighbors.map { it.tac }.filter { it != "N/A" }
            if (neighborTacs.isNotEmpty() && !neighborTacs.contains(active.tac)) {
                suspicious = true
                reasons.add("Desviación de TAC regional")
            }
        }

        // 5. Timing Advance Audit (Enhanced: Discrepancy between TA distance and database location)
        active.timingAdvance?.let { ta ->
            // Si la red es 5G, el TA viene en microsegundos; si es LTE/GSM, viene en pasos de ~78m
            val taDistanceMeters = if (active.networkType.contains("5G")) {
                ta * 150 // Conversión de microsegundos de RTT a metros (c * RTT / 2)
            } else {
                ta * 78  // Estándar clásico para LTE
            }
            if (active.lat != null && active.lon != null) {
                getCurrentLocation()?.let { currentLoc ->
                    val results = FloatArray(1)
                    Location.distanceBetween(currentLoc.latitude, currentLoc.longitude, active.lat, active.lon, results)
                    val realDistanceMeters = results[0]
                    
                    // If DB says 3km away but TA says 100m away, it's a spoofed CID
                    if (realDistanceMeters > 2000 && taDistanceMeters < 500) {
                        suspicious = true
                        reasons.add("Suplantación de identidad (TA vs Distancia GPS)")
                    }
                }
            } else {
                // Heuristic fallback: Low TA with high power without verification
                if (!active.networkType.contains("5G") && ta <= 1 && active.dbm >= -60 && active.verified != VerificationStatus.VERIFIED) {
                    suspicious = true
                    reasons.add("Proximidad física anómala (TA=$ta)")
                }
            }
        }

        // 6. Neighboring Null Check (Ghost Cells)
        // If neighbors report ARFCNs but active cell dbm is high and neighbors are all N/A or extremely low
        if (active.dbm >= -70 && neighbors.isNotEmpty() && neighbors.all { it.dbm <= -120 }) {
            suspicious = true
            reasons.add("Lista de vecinos fantasma")
        }

        return active.copy(
            isSuspicious = suspicious,
            suspiciousReason = if (reasons.isNotEmpty()) reasons.joinToString(", ") else null
        )
    }

    private fun getCurrentLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) 
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { null }
    }

    private fun verifyCell(cell: CellData) {
        if (cell.cellId == "N/A" || cell.mnc == "N/A" || cell.mcc == "N/A") return
        
        val cacheKey = "${cell.mcc}-${cell.mnc}-${cell.tac}-${cell.cellId}"
        if (verificationCache.containsKey(cacheKey) && verificationCache[cacheKey] != VerificationStatus.PENDING) return
        
        // Persistent cache check
        if (dbHelper.isCellVerified(cell.mnc, cell.tac, cell.cellId)) {
            verificationCache[cacheKey] = VerificationStatus.VERIFIED
            return
        }

        if (openCellIdKey.isNotBlank() && !openCellIdKey.startsWith("pk.YOUR")) {
            verifyCellWithOpenCellId(cell)
        }
        
        if (wigleApiName.isNotBlank() && wigleApiToken.isNotBlank()) {
            verifyCellWithWigle(cell)
        }
    }

    private fun verifyCellWithOpenCellId(cell: CellData) {
        val cacheKey = "${cell.mcc}-${cell.mnc}-${cell.tac}-${cell.cellId}"
        
        // Lock the cache entry immediately
        verificationCache[cacheKey] = VerificationStatus.PENDING

        val currentClient = if (isProxyEnabled) {
            client.newBuilder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050)))
                .build()
        } else {
            client
        }

        val url = "https://opencellid.org/cell/get?key=$openCellIdKey&mcc=${cell.mcc}&mnc=${cell.mnc}&lac=${cell.tac}&cellid=${cell.cellId}&format=json"
        
        val request = Request.Builder().url(url).build()
        
        scope.launch(Dispatchers.IO) {
            try {
                currentClient.newCall(request).execute().use { response ->
                    handleApiResponse(response, cell, cacheKey)
                }
            } catch (e: IOException) {
                Log.e("MiniIC", "OpenCellID Request Failure: ${e.message}")
                verificationCache[cacheKey] = VerificationStatus.ERROR
            }
        }
    }

    private fun verifyCellWithWigle(cell: CellData) {
        val cacheKey = "${cell.mcc}-${cell.mnc}-${cell.tac}-${cell.cellId}"
        
        // If already verified or being verified, we might still want to try WiGLE if first failed
        if (verificationCache[cacheKey] == VerificationStatus.VERIFIED) return

        val currentClient = if (isProxyEnabled) {
            client.newBuilder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050)))
                .build()
        } else {
            client
        }

        // Basic Auth for WiGLE
        val credentials = okhttp3.Credentials.basic(wigleApiName, wigleApiToken)
        
        val url = "https://api.wigle.net/api/v2/cell/search?mcc=${cell.mcc}&mnc=${cell.mnc}&lac=${cell.tac}&cellid=${cell.cellId}"
        
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credentials)
            .build()
        
        scope.launch(Dispatchers.IO) {
            try {
                currentClient.newCall(request).execute().use { response ->
                    handleWigleResponse(response, cell, cacheKey)
                }
            } catch (e: IOException) {
                Log.e("MiniIC", "WiGLE Request Failure: ${e.message}")
                if (verificationCache[cacheKey] == VerificationStatus.PENDING) {
                    verificationCache[cacheKey] = VerificationStatus.ERROR
                }
            }
        }
    }

    private fun handleApiResponse(response: Response, cell: CellData, cacheKey: String) {
        val body = response.body?.string()
        if (response.isSuccessful && body != null) {
            val json = JSONObject(body)
            if (json.has("lat")) {
                processSuccessfulVerification(json.getDouble("lat"), json.getDouble("lon"), cell, cacheKey)
            } else {
                verificationCache[cacheKey] = VerificationStatus.NOT_FOUND
                dbHelper.updateVerificationStatus(cell.mnc, cell.tac, cell.cellId, VerificationStatus.NOT_FOUND)
            }
        } else {
            val status = if (response.code == 401 || response.code == 403) VerificationStatus.ERROR else VerificationStatus.NOT_FOUND
            verificationCache[cacheKey] = status
            dbHelper.updateVerificationStatus(cell.mnc, cell.tac, cell.cellId, status)
        }
    }

    private fun handleWigleResponse(response: Response, cell: CellData, cacheKey: String) {
        val body = response.body?.string()
        if (response.isSuccessful && body != null) {
            val json = JSONObject(body)
            if (json.has("success") && json.getBoolean("success") && json.has("results")) {
                val results = json.getJSONArray("results")
                if (results.length() > 0) {
                    val first = results.getJSONObject(0)
                    val lat = first.optDouble("trilat", first.optDouble("lat", 0.0))
                    val lon = first.optDouble("trilong", first.optDouble("lon", 0.0))
                    processSuccessfulVerification(lat, lon, cell, cacheKey)
                } else {
                    if (verificationCache[cacheKey] == VerificationStatus.PENDING) {
                        verificationCache[cacheKey] = VerificationStatus.NOT_FOUND
                        dbHelper.updateVerificationStatus(cell.mnc, cell.tac, cell.cellId, VerificationStatus.NOT_FOUND)
                    }
                }
            }
        } else {
             if (verificationCache[cacheKey] == VerificationStatus.PENDING) {
                val status = if (response.code == 401 || response.code == 403) VerificationStatus.ERROR else VerificationStatus.NOT_FOUND
                verificationCache[cacheKey] = status
                dbHelper.updateVerificationStatus(cell.mnc, cell.tac, cell.cellId, status)
             }
        }
    }

    private fun processSuccessfulVerification(lat: Double, lon: Double, cell: CellData, cacheKey: String) {
        verificationCache[cacheKey] = VerificationStatus.VERIFIED
        dbHelper.updateVerificationStatus(cell.mnc, cell.tac, cell.cellId, VerificationStatus.VERIFIED)
        
        // Re-trigger analysis with coordinates
        val updatedActive = cell.copy(verified = VerificationStatus.VERIFIED, lat = lat, lon = lon)
        val analyzedActive = analyzeThreats(updatedActive, neighbors = emptyList())
        
        // Update the UI flow
        scope.launch(Dispatchers.Main) {
            _cellFlow.value = _cellFlow.value.map { 
                if (it.cellId == cell.cellId) analyzedActive.copy(isRegistered = it.isRegistered) else it 
            }
            
            if (analyzedActive.isSuspicious && analyzedActive.isRegistered) {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 200)
                checkAlerts(analyzedActive)
            }
        }
    }

    private fun checkAlerts(cell: CellData) {
        val cid = cell.cellId
        val dbm = cell.dbm
        val net = cell.networkType

        // 1. Log Connection and Ping-Pong Detection
        if (cid != prevCid) {
            val currentTime = System.currentTimeMillis()
            cellChangeHistory.add(Pair(cid, currentTime))
            cellChangeHistory.removeAll { currentTime - it.second > 10000L }

            if (cellChangeHistory.size >= 3) {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_PIP, 300)
                Log.e("MiniIC", "ANOMALÍA: Efecto Ping-Pong detectado.")
            }

            if (prevCid != null) {
                scope.launch(Dispatchers.IO) {
                    dbHelper.logConnection(net, cid, cell.mnc, cell.tac, cell.mcc, dbm, cell.verified)
                }
            }
            prevCid = cid
        }

        // 2. High power signal alarm (often IMSI catcher range)
        if (isStrongSignalAlarmEnabled && dbm != -999 && dbm >= alarmThreshold) {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 150)
            Log.w("MiniIC", "Warning: High power signal detected: $dbm dBm")
        }

        // 3. Downgrade Attack Prevention (Advanced)
        // Detect sudden transition from 4G/5G with good signal to 2G/3G
        if (prevNetType != null && prevDbm != null) {
            val isPrevSecure = prevNetType!!.contains("4G") || prevNetType!!.contains("5G")
            val isCurrentInsecure = net.contains("2G") || net.contains("3G")
            if (isPrevSecure && isCurrentInsecure && prevDbm!! >= -85) {
                toneGenerator?.startTone(ToneGenerator.TONE_SUP_ERROR, 500)
                Log.e("MiniIC", "CRITICAL: Downgrade attack detected! Pre-transition signal: $prevDbm dBm")
                triggerAirplaneMode() // Automatic countermeasure
            }
        }

        // 5. Threat analysis alert
        if (cell.isSuspicious) {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 200)
            Log.e("MiniIC", "THREAT DETECTED: ${cell.suspiciousReason}")
        }

        // 4. Fallback to 3G/2G Airplane Mode trigger (generic check)
        if (is3gAirplaneModeEnabled && (net.contains("3G") || net.contains("2G"))) {
            triggerAirplaneMode()
        }

        prevNetType = net
        prevDbm = dbm
    }

    private fun triggerAirplaneMode() {
        val currentTime = System.currentTimeMillis()
        // 60-second cooldown to avoid "Death Loop" in 2G/3G areas
        if (currentTime - lastAirplaneTriggerTime < 60000L) return
        
        lastAirplaneTriggerTime = currentTime

        Log.e("MiniIC", "CRITICAL: 2G/3G network detected! Attempting Airplane Mode fallback.")
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
        
        try {
            // Write global secure settings (requires root or ADB 'WRITE_SECURE_SETTINGS' permission)
            val isAirplaneOn = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            if (!isAirplaneOn) {
                Settings.Global.putInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
                
                // Broadcast change
                val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                    putExtra("state", true)
                }
                sendBroadcast(intent)
                Log.i("MiniIC", "Airplane mode enabled successfully via Secure Settings.")
            }
        } catch (_: SecurityException) {
            // SecurityException indicates app doesn't have WRITE_SECURE_SETTINGS. Trigger fallback UI redirect.
            Log.w("MiniIC", "Could not set Airplane Mode automatically. Launching Settings fallback UI.")
            
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    private fun updateNotification(cell: CellData) {
        val vStatus = when(cell.verified) {
            VerificationStatus.VERIFIED -> "✅ VERIFICADA"
            VerificationStatus.NOT_FOUND -> "❌ NO REGISTRADA"
            VerificationStatus.ERROR -> "⚠️ ERROR API"
            VerificationStatus.PENDING -> "⏳ PENDIENTE"
        }
        val content = "$vStatus | ${cell.networkType} | CID: ${cell.cellId} | ${cell.dbm} dBm"
        updateNotificationText(content)
    }

    private fun updateNotificationText(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, MiniICService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ICdetection: Monitoreo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        val chan = NotificationChannel(CHANNEL_ID, "miniIC Channel", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(chan)
    }

    private fun parseCell(info: CellInfo): CellData? {
        val reg = info.isRegistered
        
        // Advanced 5G Type Detection
        val networkTypeString = if (reg && info is CellInfoLte) {
            getLteSpecificType()
        } else if (info is CellInfoLte) {
            "4G LTE"
        } else if (info is CellInfoNr) {
            "5G NR (SA)"
        } else if (info is CellInfoWcdma) {
            "3G WCDMA"
        } else if (info is CellInfoGsm) {
            "2G GSM"
        } else {
            "Unknown"
        }

        return when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
                val ta = info.cellSignalStrength.timingAdvance.let { if (it == Int.MAX_VALUE) null else it }
                val mcc = id.mccString ?: getNetworkOperatorMcc()
                val mnc = id.mncString ?: getNetworkOperatorMnc()
                CellData(reg, networkTypeString, id.ci.valOrNa(), mnc, id.tac.valOrNa(), dbm, mcc, timingAdvance = ta, arfcn = id.earfcn)
            }
            is CellInfoNr -> {
                val id = info.cellIdentity as CellIdentityNr
                val strength = info.cellSignalStrength as CellSignalStrengthNr
                val dbm = strength.ssRsrp
                
                // Forzamos el uso de timingAdvanceMicros (disponible en API 34+) para Pixel 9a
                val ta = if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        // Usamos reflexión para asegurar compatibilidad en el build
                        val method = strength.javaClass.getMethod("getTimingAdvanceMicros")
                        val res = method.invoke(strength) as Int
                        if (res == Int.MAX_VALUE || res == -1) null else res
                    } catch (_: Exception) { null }
                } else null

                val mcc = id.mccString ?: getNetworkOperatorMcc()
                val mnc = id.mncString ?: getNetworkOperatorMnc()
                CellData(reg, networkTypeString, id.nci.valOrNa(), mnc, id.tac.valOrNa(), dbm, mcc, timingAdvance = ta, arfcn = id.nrarfcn)
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
                val mcc = id.mccString ?: getNetworkOperatorMcc()
                val mnc = id.mncString ?: getNetworkOperatorMnc()
                CellData(reg, networkTypeString, id.cid.valOrNa(), mnc, id.lac.valOrNa(), dbm, mcc, arfcn = id.uarfcn)
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
                val ta = info.cellSignalStrength.timingAdvance.let { if (it == Int.MAX_VALUE) null else it }
                val mcc = id.mccString ?: getNetworkOperatorMcc()
                val mnc = id.mncString ?: getNetworkOperatorMnc()
                CellData(reg, networkTypeString, id.cid.valOrNa(), mnc, id.lac.valOrNa(), dbm, mcc, timingAdvance = ta, arfcn = id.arfcn)
            }
            else -> null
        }
    }

    private fun getNetworkOperatorMcc(): String {
        val operator = telephonyManager.networkOperator
        return if (operator != null && operator.length >= 3) {
            operator.substring(0, 3)
        } else {
            "N/A"
        }
    }

    private fun getNetworkOperatorMnc(): String {
        val operator = telephonyManager.networkOperator
        return if (operator != null && operator.length > 3) {
            operator.substring(3)
        } else {
            "N/A"
        }
    }

    private fun getLteSpecificType(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lastDisplayInfo?.let { info ->
                val override = info.overrideNetworkType
                if (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                    override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) {
                    return "5G NR (NSA)"
                }
            }
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    // Fallback to ServiceState string check (very reliable for NSA detection)
                    val ssStr = telephonyManager.serviceState?.toString() ?: ""
                    val isNsa = ssStr.contains("nrState=CONNECTED") || 
                               ssStr.contains("nrState=NOT_RESTRICTED") ||
                               ssStr.contains("nrState=NOT_RESTRICTED")
                    
                    return if (isNsa) "5G NR (NSA)" else "4G LTE"
                } catch (_: Exception) {}
            }
        }
        return "4G LTE"
    }

    private fun Int.valOrNa() = if (this == Int.MAX_VALUE || this == -1 || this == 0) "N/A" else this.toString()
    private fun Long.valOrNa() = if (this == Long.MAX_VALUE || this == -1L || this == 0L) "N/A" else this.toString()

    companion object {
        const val CHANNEL_ID = "miniic_channel"
        const val NOTIFICATION_ID = 202
        const val ACTION_STOP = "com.example.miniic.STOP"
    }
}

// Activity Class
class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<MiniICService?>(null)
    private var isBound = false
    private lateinit var dbHelper: CellDbHelper

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as MiniICService.LocalBinder
            service = b.getService()
            isBound = true
            service?.forceRefresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = CellDbHelper(this)
        
        // Check permissions before starting service to avoid crash
        val hasLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasPhone = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        
        if (hasLoc && hasPhone) {
            startAndBindService()
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFE0E0E0),      // Minimalist Light Gray
                    background = Color(0xFF0A0A0A),   // Deep Black
                    surface = Color(0xFF141414),      // Elevated Dark Gray
                    error = Color(0xFFCF6679)
                )
            ) {
                MainLayout(context = this, dbHelper = dbHelper, service = service)
            }
        }
    }

    fun startAndBindService() {
        val intent = Intent(this, MiniICService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}

@Composable
fun MainLayout(context: Context, dbHelper: CellDbHelper, service: MiniICService?) {
    var hasLoc by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasPhone by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotif by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLoc = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        hasPhone = permissions[Manifest.permission.READ_PHONE_STATE] ?: false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotif = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        }
        if (hasLoc && hasPhone) {
            (context as? MainActivity)?.startAndBindService()
        }
    }

    if (!hasLoc || !hasPhone || !hasNotif) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "ICdetection requiere permisos de localización, teléfono y notificaciones para funcionar.",
                    color = Color(0xFF888888),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
                Button(
                    onClick = {
                        val arr = mutableListOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.READ_PHONE_STATE
                        ).apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                        launcher.launch(arr)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                ) {
                    Text("Otorgar", color = Color.White)
                }
            }
        }
    } else {
        MainScreenContent(dbHelper = dbHelper, service = service)
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun MainScreenContent(dbHelper: CellDbHelper, service: MiniICService?) {
    val cellList by (service?.cellFlow ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val pullRefreshState = rememberPullRefreshState(
        refreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                service?.forceRefresh()
                delay(800)
                refreshing = false
            }
        }
    )

    val active = cellList.firstOrNull { it.isRegistered }
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(service) {
        if (service != null) {
            delay(500)
            service.forceRefresh()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ICdetection",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.White
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Row {
                IconButton(onClick = { showSettings = !showSettings; showHistory = false }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = if (showSettings) Color.White else Color(0xFF888888))
                }
                TextButton(
                    onClick = { showHistory = !showHistory; showSettings = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF888888))
                ) {
                    Text(if (showHistory) "MONITOR" else "HISTORIAL", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }

        if (showSettings) {
            SettingsPanel(service) { showSettings = false }
        } else if (showHistory) {
            HistoryPanel(dbHelper = dbHelper) { showHistory = false }
        } else {
            // Define visual security states
            val (statusText, statusColor) = when {
                active == null -> "BUSCANDO SEÑAL..." to Color(0xFF888888)
                active.isSuspicious -> "SISTEMA EN COMPROMISO" to Color(0xFFCF6679)
                active.verified == VerificationStatus.VERIFIED -> "ENTORNO SEGURO" to Color(0xFF4CAF50)
                else -> "MONITORIZANDO RED" to Color(0xFFFFA000)
            }

            // Radar Style Header
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.08f)),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("ESTADO DEL SECTOR", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        Text(statusText, color = statusColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp)
                    }
                    if (active?.isSuspicious == true) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(24.dp))
                    }
                }
            }

            // Monitor parameters
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pullRefresh(pullRefreshState)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Main Grid Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                        border = BorderStroke(0.5.dp, Color(0xFF222222)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Row 1: Tech and Power
                            Row(modifier = Modifier.fillMaxWidth()) {
                                DataBox(modifier = Modifier.weight(1f), label = "TECNOLOGÍA", value = active?.networkType ?: "N/A")
                                DataBox(modifier = Modifier.weight(1f), label = "POTENCIA", value = if (active == null) "N/A" else "${active.dbm} dBm")
                            }
                            
                            HorizontalDivider(color = Color(0xFF1A1A1A))

                            // Row 2: Cell ID and TAC
                            Row(modifier = Modifier.fillMaxWidth()) {
                                DataBox(modifier = Modifier.weight(1f), label = "CELL ID", value = active?.cellId ?: "N/A", highlight = true)
                                DataBox(modifier = Modifier.weight(1f), label = "TAC / LAC", value = active?.tac ?: "N/A")
                            }

                            HorizontalDivider(color = Color(0xFF1A1A1A))

                            // Row 3: MNC and Verification
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                DataBox(modifier = Modifier.weight(1f), label = "OPERADOR (MNC)", value = active?.mnc ?: "N/A")
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("VERIFICACIÓN", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    VerificationBadge(active?.verified ?: VerificationStatus.PENDING)
                                }
                            }

                            if (active != null) {
                                SignalVisualizer(active, cellList.filter { !it.isRegistered })
                            }

                            if (active?.isSuspicious == true) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF330000)),
                                    border = BorderStroke(1.dp, Color.Red),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(20.dp))
                                        Column {
                                            Text(
                                                text = "AMENAZA: ${active.suspiciousReason}",
                                                color = Color.Red,
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "RECOMENDADO: ACTIVAR MODO AVIÓN",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Controls
                    if (service != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                            border = BorderStroke(0.5.dp, Color(0xFF222222)),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("ALERTAS", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Alarma señal fuerte (>= -50 dBm)", color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                    Switch(
                                        checked = service.isStrongSignalAlarmEnabled,
                                        onCheckedChange = { service.isStrongSignalAlarmEnabled = it },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.Black,
                                            checkedTrackColor = Color(0xFF4CAF50),
                                            uncheckedThumbColor = Color(0xFF444444),
                                            uncheckedTrackColor = Color(0xFF141414),
                                            uncheckedBorderColor = Color(0xFF333333)
                                        )
                                    )
                                }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Modo avión auto en 3G/2G", color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                    Switch(
                                        checked = service.is3gAirplaneModeEnabled,
                                        onCheckedChange = { service.is3gAirplaneModeEnabled = it },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.Black,
                                            checkedTrackColor = Color(0xFF4CAF50),
                                            uncheckedThumbColor = Color(0xFF444444),
                                            uncheckedTrackColor = Color(0xFF141414),
                                            uncheckedBorderColor = Color(0xFF333333)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                
                PullRefreshIndicator(
                    refreshing = refreshing,
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    backgroundColor = Color(0xFF111111),
                    contentColor = Color(0xFF4CAF50)
                )
            }

            AuthorSignature()
        }
    }
}

@Composable
fun SettingsPanel(service: MiniICService?, onSave: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("miniic_prefs", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("opencellid_key", "") ?: "") }
    var wigleName by remember { mutableStateOf(prefs.getString("wigle_api_name", "") ?: "") }
    var wigleToken by remember { mutableStateOf(prefs.getString("wigle_api_token", "") ?: "") }
    var proxyEnabled by remember { mutableStateOf(prefs.getBoolean("proxy_enabled", false)) }

    val scrollState = rememberScrollState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("CONFIGURACIÓN", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            
            // OpenCellID Section
            Text("OpenCellID API Token", color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                placeholder = { Text("pk.xxxxxxxxxxxxxxxx", color = Color(0xFF444444), fontSize = 12.sp) },
                visualTransformation = PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF4CAF50),
                    unfocusedBorderColor = Color(0xFF333333),
                    cursorColor = Color.White
                )
            )

            HorizontalDivider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 4.dp))

            // WiGLE Section
            Text("WiGLE API Credentials", color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            
            Text("API Name", color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            OutlinedTextField(
                value = wigleName,
                onValueChange = { wigleName = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                placeholder = { Text("AIDxxxxxxxxxxxxxxxx", color = Color(0xFF444444), fontSize = 12.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF4CAF50),
                    unfocusedBorderColor = Color(0xFF333333),
                    cursorColor = Color.White
                )
            )

            Text("API Token", color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            OutlinedTextField(
                value = wigleToken,
                onValueChange = { wigleToken = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                placeholder = { Text("xxxxxxxxxxxxxxxx", color = Color(0xFF444444), fontSize = 12.sp) },
                visualTransformation = PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF4CAF50),
                    unfocusedBorderColor = Color(0xFF333333),
                    cursorColor = Color.White
                )
            )

            HorizontalDivider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 4.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Proxy Tor (Orbot)", color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Text("Enruta API por Tor (SOCKS5 9050)", color = Color(0xFF666666), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Switch(
                    checked = proxyEnabled,
                    onCheckedChange = { proxyEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF4CAF50)
                    )
                )
            }
            
            Button(
                onClick = {
                    prefs.edit {
                        putString("opencellid_key", token)
                        putString("wigle_api_name", wigleName)
                        putString("wigle_api_token", wigleToken)
                        putBoolean("proxy_enabled", proxyEnabled)
                    }
                    service?.openCellIdKey = token
                    service?.wigleApiName = wigleName
                    service?.wigleApiToken = wigleToken
                    service?.isProxyEnabled = proxyEnabled
                    onSave() // Volver a la pantalla principal
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text("GUARDAR AJUSTES", color = Color.White, fontFamily = FontFamily.Monospace)
            }
            
            Text(
                "Configura OpenCellID o WiGLE para habilitar la verificación de antenas. Los datos se consultan de forma segura.",
                color = Color(0xFF888888),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
fun AuthorSignature() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Botón de Donación / Apoyo
        val context = LocalContext.current
        TextButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, "https://buymeacoffee.com/alexisgomez".toUri())
                context.startActivity(intent)
            },
            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFDD00)), // Color característico de BuyMeACoffee
            modifier = Modifier
                .height(32.dp)
                .border(BorderStroke(0.5.dp, Color(0xFF333333)), RoundedCornerShape(16.dp))
                .padding(horizontal = 8.dp)
        ) {
            Icon(
                Icons.Default.Favorite, 
                contentDescription = null, 
                modifier = Modifier.size(14.dp),
                tint = Color(0xFFCF6679)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "APOYAR PROYECTO", 
                fontFamily = FontFamily.Monospace, 
                fontSize = 10.sp, 
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = "HECHO POR ALEXIS G. // OPEN SOURCE",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Color(0xFF333333),
            letterSpacing = 2.sp
        )
    }
}

@Composable
fun DataBox(modifier: Modifier, label: String, value: String, highlight: Boolean = false) {
    Column(modifier = modifier) {
        Text(label, color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            text = value, 
            color = if (highlight) Color(0xFFE0E0E0) else Color.White, 
            fontFamily = FontFamily.Monospace, 
            fontSize = 14.sp, 
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun SignalVisualizer(active: CellData, neighbors: List<CellData>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("COMPARATIVA DE SEÑAL", color = Color(0xFF444444), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        
        // Active Cell Bar
        SignalBarSegmented(
            label = "ACTIVA (${active.networkType})", 
            dbm = active.dbm, 
            isMain = true,
            isSuspicious = active.isSuspicious
        )
        
        // Neighbors (Top 3 strongest)
        neighbors.sortedByDescending { it.dbm }.take(3).forEach { neighbor ->
            SignalBarSegmented(
                label = "VECINA (${neighbor.cellId})", 
                dbm = neighbor.dbm, 
                isMain = false,
                isSuspicious = neighbor.isSuspicious
            )
        }
    }
}

@Composable
fun SignalBarSegmented(label: String, dbm: Int, isMain: Boolean, isSuspicious: Boolean) {
    // Normalizar la señal de 0 a 10 bloques
    val totalBlocks = 10
    val activeBlocks = ((dbm + 140).coerceIn(0, 100).toFloat() / 100f * totalBlocks).toInt()
    
    val blockColor = when {
        isSuspicious -> Color.Red
        isMain -> Color(0xFF4CAF50)
        else -> Color(0xFF888888)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFF666666), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text("$dbm dBm", color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        
        // Fila de bloques
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp) // Espaciado entre segmentos
        ) {
            for (i in 0 until totalBlocks) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(if (i < activeBlocks) blockColor else Color(0xFF1A1A1A), RoundedCornerShape(1.dp))
                )
            }
        }
    }
}

fun exportToCsv(context: Context, items: List<HistoryRecord>) {
    val fileName = "miniic_history_${System.currentTimeMillis()}.csv"
    val file = File(context.cacheDir, fileName)
    
    try {
        val writer = FileWriter(file)
        writer.append("Timestamp,NetType,CID,MNC,TAC,MCC,DBM,Verified\n")
        items.forEach { item ->
            writer.append("${item.timestamp},${item.netType},${item.cid},${item.mnc},${item.tac},${item.mcc},${item.dbm},${item.verified.name}\n")
        }
        writer.flush()
        writer.close()

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Exportar Historial"))

    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@Composable
fun VerificationBadge(status: VerificationStatus) {
    val (text, color) = when (status) {
        VerificationStatus.VERIFIED -> "REGISTRADA" to Color(0xFF4CAF50)
        VerificationStatus.NOT_FOUND -> "NO ENCONTRADA" to Color(0xFFF44336)
        VerificationStatus.PENDING -> "PENDIENTE" to Color(0xFF888888)
        VerificationStatus.ERROR -> "ERROR API" to Color(0xFFFFA000)
    }
    
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(2.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun HistoryPanel(dbHelper: CellDbHelper, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<HistoryRecord>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            items = dbHelper.getRecords()
        }
    }

    val groupedItems = remember(items) {
        val groups = mutableMapOf<String, MutableList<HistoryRecord>>()
        val orderedCids = mutableListOf<String>()
        items.forEach { record ->
            if (!groups.containsKey(record.cid)) {
                orderedCids.add(record.cid)
                groups[record.cid] = mutableListOf()
            }
            groups[record.cid]!!.add(record)
        }
        orderedCids.map { cid -> cid to groups[cid]!! }
    }

    var expandedCids by remember { mutableStateOf(setOf<String>()) }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text("HISTORIAL DE ANTENAS", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            TextButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        dbHelper.clear()
                        items = emptyList()
                    }
                },
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFCF6679))
            ) {
                Text("LIMPIAR", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("HISTORIAL VACÍO", color = Color(0xFF444444), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val context = LocalContext.current
                TextButton(
                    onClick = {
                        exportToCsv(context, items)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF4CAF50))
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("EXPORTAR CSV", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groupedItems) { (cid, records) ->
                    val isExpanded = expandedCids.contains(cid)
                    val first = records.first()
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                        shape = RoundedCornerShape(4.dp),
                        onClick = {
                            expandedCids = if (isExpanded) expandedCids - cid else expandedCids + cid
                        }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("CELL ID: $cid", color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    Text("CONEXIONES: ${records.size}", color = Color(0xFF888888), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    VerificationBadge(first.verified)
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = Color(0xFF666666)
                                    )
                                }
                            }

                            if (isExpanded) {
                                Spacer(Modifier.height(12.dp))
                                
                                // Botón de Ver en Mapa (Solo para verificadas)
                                if (first.verified == VerificationStatus.VERIFIED) {
                                    val context = LocalContext.current
                                    // Buscamos un MCC válido en el grupo por si el primero es "N/A"
                                    val validMcc = records.firstOrNull { it.mcc != "N/A" }?.mcc ?: first.mcc
                                    val validMnc = records.firstOrNull { it.mnc != "N/A" }?.mnc ?: first.mnc
                                    
                                    TextButton(
                                        onClick = {
                                            val prefs = context.getSharedPreferences("miniic_prefs", Context.MODE_PRIVATE)
                                            val token = prefs.getString("opencellid_key", "") ?: ""
                                            
                                            // Paso 1: Consultar la API para obtener las coordenadas exactas
                                            val apiUrl = "https://opencellid.org/cell/get?key=$token&mcc=$validMcc&mnc=$validMnc&lac=${first.tac}&cellid=${first.cid}&format=json"
                                            
                                            val client = OkHttpClient()
                                            val request = Request.Builder().url(apiUrl).build()
                                            
                                            client.newCall(request).enqueue(object : Callback {
                                                override fun onFailure(call: Call, e: IOException) {
                                                    // Si falla la API, abrimos la home como respaldo
                                                    val intent = Intent(Intent.ACTION_VIEW, "https://opencellid.org".toUri())
                                                    context.startActivity(intent)
                                                }

                                                override fun onResponse(call: Call, response: Response) {
                                                    val body = response.body?.string()
                                                    if (response.isSuccessful && body != null) {
                                                        val json = JSONObject(body)
                                                        if (json.has("lat") && json.has("lon")) {
                                                            val lat = json.getDouble("lat")
                                                            val lon = json.getDouble("lon")
                                                            // Paso 2: Abrir el MAPA real centrado en las coordenadas
                                                            val mapUrl = "https://opencellid.org/#zoom=16&lat=$lat&lon=$lon"
                                                            val intent = Intent(Intent.ACTION_VIEW, mapUrl.toUri())
                                                            context.startActivity(intent)
                                                            return
                                                        }
                                                    }
                                                    // Fallback si no hay coordenadas
                                                    val intent = Intent(Intent.ACTION_VIEW, "https://opencellid.org".toUri())
                                                    context.startActivity(intent)
                                                }
                                            })
                                        },
                                        modifier = Modifier.fillMaxWidth().height(32.dp),
                                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF4CAF50))
                                    ) {
                                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("VER EN MAPA (OPENCELLID)", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }

                                HorizontalDivider(color = Color(0xFF222222))
                                records.forEach { record ->
                                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(record.timestamp, color = Color(0xFF666666), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                            Text("${record.dbm} dBm (${record.netType})", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("MNC: ${record.mnc}", color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                            Text("TAC: ${record.tac}", color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                    if (record != records.last()) {
                                        HorizontalDivider(color = Color(0xFF1A1A1A), thickness = 0.5.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AuthorSignature()
        }
    }
}
