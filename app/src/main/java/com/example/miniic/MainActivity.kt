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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// Database Helper for history logging
class CellDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        private const val DATABASE_NAME = "miniic_history.db"
        private const val DATABASE_VERSION = 2
        const val TABLE_HISTORY = "history"
        const val COLUMN_ID = "id"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_NET_TYPE = "net_type"
        const val COLUMN_CID = "cid"
        const val COLUMN_MNC = "mnc"
        const val COLUMN_TAC = "tac"
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
                    "$COLUMN_DBM INTEGER, " +
                    "$COLUMN_VERIFIED TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_VERIFIED TEXT DEFAULT 'PENDING'")
        }
    }

    fun logConnection(netType: String, cid: String, mnc: String, tac: String, dbm: Int, verified: VerificationStatus = VerificationStatus.PENDING) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            put(COLUMN_TIMESTAMP, sdf.format(Date()))
            put(COLUMN_NET_TYPE, netType)
            put(COLUMN_CID, cid)
            put(COLUMN_MNC, mnc)
            put(COLUMN_TAC, tac)
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
    val arfcn: Int? = null
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
    private var toneGenerator: ToneGenerator? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var displayInfoCallback: TelephonyCallback? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var lastDisplayInfo: TelephonyDisplayInfo? = null
    private var isScreenOn = true
    private var isServiceRunning = false

    // OpenCellID API key (User should replace this)
    var openCellIdKey: String = ""
    private val client = OkHttpClient()
    private val verificationCache = ConcurrentHashMap<String, VerificationStatus>()

    // Options
    var alarmThreshold = -50f
    var isStrongSignalAlarmEnabled = true
    var is3gAirplaneModeEnabled = true

    // Tracking states
    private var prevCid: String? = null
    private var prevNetType: String? = null
    private var prevDbm: Int? = null

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
        
        // Load API Key from preferences
        val prefs = getSharedPreferences("miniic_prefs", MODE_PRIVATE)
        openCellIdKey = prefs.getString("opencellid_key", "") ?: ""

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
                requestFreshCellInfo()
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
                    telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                            processCellInfo(cellInfo)
                        }
                    }
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
                        // Fallback to allCellInfo
                        if (ContextCompat.checkSelfPermission(this@MiniICService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            try {
                                processCellInfo(telephonyManager.allCellInfo)
                            } catch (e: SecurityException) {
                                e.printStackTrace()
                            }
                        }
                    }
                })
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
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
                verifyCellWithOpenCellId(active)
                
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

        // 5. Timing Advance Audit (Heuristic: Low TA with high power vs database)
        // If TA is 0 or 1, antenna is physically < 150m away. 
        // If the signal is extremely strong but verification is not "VERIFIED", it's risky.
        active.timingAdvance?.let { ta ->
            if (ta <= 1 && active.dbm >= -60 && active.verified != VerificationStatus.VERIFIED) {
                suspicious = true
                reasons.add("Proximidad física anómala (TA=$ta)")
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

    private fun verifyCellWithOpenCellId(cell: CellData) {
        if (cell.cellId == "N/A" || cell.mnc == "N/A" || cell.mcc == "N/A" || openCellIdKey.isBlank() || openCellIdKey.startsWith("pk.YOUR")) return
        
        val cacheKey = "${cell.mcc}-${cell.mnc}-${cell.tac}-${cell.cellId}"
        if (verificationCache.containsKey(cacheKey)) return
        
        // Persistent cache check
        if (dbHelper.isCellVerified(cell.mnc, cell.tac, cell.cellId)) {
            verificationCache[cacheKey] = VerificationStatus.VERIFIED
            return
        }

        val url = "https://opencellid.org/cell/get?key=$openCellIdKey&mcc=${cell.mcc}&mnc=${cell.mnc}&lac=${cell.tac}&cellid=${cell.cellId}&format=json"
        
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("MiniIC", "API Request Failure: ${e.message}")
                verificationCache[cacheKey] = VerificationStatus.ERROR
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val json = JSONObject(body)
                    if (json.has("lat")) {
                        verificationCache[cacheKey] = VerificationStatus.VERIFIED
                        dbHelper.updateVerificationStatus(cell.mnc, cell.tac, cell.cellId, VerificationStatus.VERIFIED)
                    } else {
                        verificationCache[cacheKey] = VerificationStatus.NOT_FOUND
                        dbHelper.updateVerificationStatus(cell.mnc, cell.tac, cell.cellId, VerificationStatus.NOT_FOUND)
                    }
                } else if (response.code == 401 || response.code == 403) {
                    Log.e("MiniIC", "API Token Error: ${response.code}")
                    verificationCache[cacheKey] = VerificationStatus.ERROR
                    dbHelper.updateVerificationStatus(cell.mnc, cell.tac, cell.cellId, VerificationStatus.ERROR)
                } else {
                    verificationCache[cacheKey] = VerificationStatus.NOT_FOUND
                    dbHelper.updateVerificationStatus(cell.mnc, cell.tac, cell.cellId, VerificationStatus.NOT_FOUND)
                }
            }
        })
    }

    private fun checkAlerts(cell: CellData) {
        val cid = cell.cellId
        val dbm = cell.dbm
        val net = cell.networkType

        // 1. Log Connection on Cell ID change
        if (cid != prevCid) {
            if (prevCid != null) {
                scope.launch(Dispatchers.IO) {
                    dbHelper.logConnection(net, cid, cell.mnc, cell.tac, dbm, cell.verified)
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
                CellData(reg, networkTypeString, id.ci.valOrNa(), id.mncString ?: "N/A", id.tac.valOrNa(), dbm, id.mccString ?: "N/A", timingAdvance = ta, arfcn = id.earfcn)
            }
            is CellInfoNr -> {
                val id = info.cellIdentity as CellIdentityNr
                val dbm = (info.cellSignalStrength as CellSignalStrengthNr).ssRsrp
                // NR TA is not directly available in standard API, but we keep the structure
                CellData(reg, networkTypeString, id.nci.valOrNa(), id.mncString ?: "N/A", id.tac.valOrNa(), dbm, id.mccString ?: "N/A", arfcn = id.nrarfcn)
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
                CellData(reg, networkTypeString, id.cid.valOrNa(), id.mncString ?: "N/A", id.lac.valOrNa(), dbm, id.mccString ?: "N/A", arfcn = id.uarfcn)
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
                val ta = info.cellSignalStrength.timingAdvance.let { if (it == Int.MAX_VALUE) null else it }
                CellData(reg, networkTypeString, id.cid.valOrNa(), id.mncString ?: "N/A", id.lac.valOrNa(), dbm, id.mccString ?: "N/A", timingAdvance = ta, arfcn = id.arfcn)
            }
            else -> null
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
    private var service: MiniICService? = null
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
            Text(
                "ICdetection //",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
            
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
                    // Main reading
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("RED", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                Text(active?.networkType ?: "BUSCANDO...", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("CELL ID", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                Text(active?.cellId ?: "N/A", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("TAC", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                Text(active?.tac ?: "N/A", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("MNC", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                Text(active?.mnc ?: "N/A", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("POTENCIA", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                Text(if (active == null) "N/A" else "${active.dbm} dBm", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                            
                            HorizontalDivider(color = Color(0xFF222222), thickness = 1.dp)

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("VERIFICACIÓN", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                VerificationBadge(active?.verified ?: VerificationStatus.PENDING)
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
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFF4CAF50),
                                            uncheckedThumbColor = Color(0xFF888888),
                                            uncheckedTrackColor = Color(0xFF333333)
                                        )
                                    )
                                }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Modo avión auto en 3G/2G", color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                    Switch(
                                        checked = service.is3gAirplaneModeEnabled,
                                        onCheckedChange = { service.is3gAirplaneModeEnabled = it },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFF4CAF50),
                                            uncheckedThumbColor = Color(0xFF888888),
                                            uncheckedTrackColor = Color(0xFF333333)
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

            // Firma del autor
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
    }
}

@Composable
fun SettingsPanel(service: MiniICService?, onSave: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("miniic_prefs", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("opencellid_key", "") ?: "") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("CONFIGURACIÓN", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            
            Text("OpenCellID API Token", color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                placeholder = { Text("pk.xxxxxxxxxxxxxxxx", color = Color(0xFF444444), fontSize = 12.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF4CAF50),
                    unfocusedBorderColor = Color(0xFF333333),
                    cursorColor = Color.White
                )
            )
            
                Button(
                    onClick = {
                        prefs.edit {
                            putString("opencellid_key", token)
                        }
                        service?.openCellIdKey = token
                        onSave() // Volver a la pantalla principal
                    },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text("GUARDAR TOKEN", color = Color.White, fontFamily = FontFamily.Monospace)
            }
            
            Text(
                "Consigue tu token gratuito en opencellid.org para habilitar la verificación de antenas.",
                color = Color(0xFF888888),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp
            )
        }
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
        SignalBar(
            label = "ACTIVA (${active.networkType})", 
            dbm = active.dbm, 
            isMain = true,
            isSuspicious = active.isSuspicious
        )
        
        // Neighbors (Top 3 strongest)
        neighbors.sortedByDescending { it.dbm }.take(3).forEach { neighbor ->
            SignalBar(
                label = "VECINA (${neighbor.cellId})", 
                dbm = neighbor.dbm, 
                isMain = false,
                isSuspicious = neighbor.isSuspicious
            )
        }
    }
}

@Composable
fun SignalBar(label: String, dbm: Int, isMain: Boolean, isSuspicious: Boolean) {
    val progress = ((dbm + 140).coerceIn(0, 100).toFloat() / 100f)
    
    val barColor = when {
        isSuspicious -> Color.Red
        isMain -> Color(0xFF4CAF50)
        else -> Color(0xFF888888)
    }
    
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFF888888), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text("$dbm dBm", color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color(0xFF222222))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(barColor)
            )
        }
    }
}

fun exportToCsv(context: Context, items: List<HistoryRecord>) {
    val fileName = "miniic_history_${System.currentTimeMillis()}.csv"
    val file = File(context.cacheDir, fileName)
    
    try {
        val writer = FileWriter(file)
        writer.append("Timestamp,NetType,CID,MNC,TAC,DBM,Verified\n")
        items.forEach { item ->
            writer.append("${item.timestamp},${item.netType},${item.cid},${item.mnc},${item.tac},${item.dbm},${item.verified.name}\n")
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
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                        shape = RoundedCornerShape(2.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(item.timestamp, color = Color(0xFF666666), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                VerificationBadge(item.verified)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${item.dbm} dBm (${item.netType})", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Text("MNC: ${item.mnc}", color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("CID: ${item.cid}", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text("TAC: ${item.tac}", color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            // Firma del autor
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
    }
}
