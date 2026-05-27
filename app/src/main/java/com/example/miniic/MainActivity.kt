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
import androidx.compose.animation.AnimatedVisibility
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
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
import okhttp3.OkHttpClient
import okhttp3.Request
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
        private const val DATABASE_VERSION = 4 // Subimos a la versión 4
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
        const val COLUMN_SCORE = "score" // NUEVO
        const val COLUMN_FAILED_H = "failed_heuristics" // NUEVO
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
                        failedHeuristics = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_FAILED_H)) ?: ""
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
    val verified: VerificationStatus = VerificationStatus.PENDING,
    val score: Int = 100, // NUEVO
    val failedHeuristics: String = "" // NUEVO
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
    val lon: Double? = null,
    val heuristicReport: HeuristicReport = HeuristicReport(),
    val securityScore: Int = 100
)

data class HeuristicReport(
    val isolatedCellPassed: Boolean = true,
    val powerJumpPassed: Boolean = true,
    val mccConsistencyPassed: Boolean = true,
    val mncCountPassed: Boolean = true,
    val tacDeviationPassed: Boolean = true,
    val taDistancePassed: Boolean = true,
    val ghostNeighborsPassed: Boolean = true,
    val hardwareCipheringPassed: Boolean = true,
    val pingPongPassed: Boolean = true // <--- ¡LA 9ª HEURÍSTICA!
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

    private val _liveLogs = MutableStateFlow<List<String>>(listOf("[SYS] ICdetection Engine v1.0 Iniciado...", "[SYS] Esperando hooks del módem..."))
    val liveLogs: StateFlow<List<String>> = _liveLogs

    // Variables para la Gráfica de Telemetría
    private val _dbmHistory = MutableStateFlow<List<Int>>(emptyList())
    val dbmHistory: StateFlow<List<Int>> = _dbmHistory

    private val _geoHistory = MutableStateFlow<List<Float>>(emptyList())
    val geoHistory: StateFlow<List<Float>> = _geoHistory

    // Estado para la UI del escáner
    private val _auditStatus = MutableStateFlow<String>("")
    val auditStatus: StateFlow<String> = _auditStatus
    
    // Guardamos la última auditoría completa
    private var lastFullReport: HeuristicReport? = null
    private var lastAuditTime = 0L

    // Función para inyectar texto en la consola
    private fun appendLog(type: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val newLog = "[$timestamp] $type $message"
        _liveLogs.value = (_liveLogs.value + newLog).takeLast(40) // Guardamos solo las últimas 40 líneas para no consumir RAM
    }

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
    
    // Variable global para rastrear el cifrado
    private var isHardwareCipheringActive = true

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
                    appendLog("[SYS]", "⚠️ Modo Avión activo. Suspendiendo escaneo.")
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
                    val callback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                            isCallbackWorking = true
                            processCellInfo(cellInfo)
                        }
                    }
                    telephonyCallback = callback
                    telephonyCallback?.let {
                        telephonyManager.registerTelephonyCallback(mainExecutor, it)
                    }

                    // Registramos los detectores avanzados para Android 14/15+
                    if (Build.VERSION.SDK_INT >= 34) {
                        registerAdvancedSecurityCallback()
                    }
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun registerAdvancedSecurityCallback() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val securityCallback = ImsiCatcherSecurityCallback()
                
                // En Android 14/15+, registerTelephonyCallback usa reflexión interna para detectar
                // si el objeto implementa las interfaces de escucha, incluso si no están en el classpath de compilación.
                telephonyManager.registerTelephonyCallback(mainExecutor, securityCallback)
                appendLog("[SYS]", "Callback de seguridad de hardware L3 registrado.")
            }
        } catch (e: Exception) {
            Log.e("MiniIC", "Aviso: No se pudo registrar el callback de seguridad avanzado: ${e.message}")
            appendLog("[SYS]", "Error al registrar callback L3: ${e.message}")
        }
    }

    private fun triggerSecurityAlert(message: String) {
        Log.e("MiniIC_Security", message)
        appendLog("[SEC]", "🚨 ALERTA: $message")
        toneGenerator?.startTone(ToneGenerator.TONE_SUP_ERROR, 500)
        updateNotificationText("⚠️ $message")
        
        // Registrar la alerta en el historial
        scope.launch(Dispatchers.IO) {
            dbHelper.logConnection(
                netType = "ALERTA",
                cid = "SECURITY",
                mnc = "N/A",
                tac = "N/A",
                mcc = "N/A",
                dbm = -999,
                verified = VerificationStatus.ERROR
            )
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

            var list = mutableListOf<CellData>()
            infoList?.forEach { info ->
                val parsed = parseCell(info)
                // Filter out invalid readings
                if (parsed != null && parsed.dbm != Int.MAX_VALUE && parsed.dbm < 100) {
                    list.add(parsed)
                }
            }

            if (list.isEmpty()) {
                _cellFlow.value = emptyList()
                updateNotificationText("Sin señal / Modo Avión")
                return
            }

            // --- INICIO PARCHE PIXEL CORREGIDO ---
            // Solo robamos si el TA es mayor o igual que 0. No toques celdas que no tienen info.
            val bestTa = list.firstOrNull { it.timingAdvance != null && it.timingAdvance >= 0 }?.timingAdvance
            
            if (bestTa != null) {
                list = list.map { cell -> 
                    // Solo asignamos si la celda es la activa y no tiene dato
                    if (cell.isRegistered && cell.timingAdvance == null) {
                        cell.copy(timingAdvance = bestTa)
                    } else cell
                }.toMutableList()
            }
            // --- FIN PARCHE ---

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
        val reasons = mutableListOf<String>()
        var score = 100

        // Inicializamos todas las heurísticas en verde (true)
        var hIsolated = true
        var hPowerJump = true
        var hMcc = true
        var hMncCount = true
        var hTac = true
        var hTa = true
        var hGhost = true
        var hPingPong = true // NUEVO

        // 1. Neighbor analysis
        if (neighbors.isEmpty() && active.dbm >= -80) {
            hIsolated = false
            reasons.add("Celda aislada")
            score -= 15
        }

        // 2. Signal Gap analysis
        val nextStrongest = neighbors.maxOfOrNull { it.dbm }
        if (nextStrongest != null && active.dbm >= -75 && active.dbm - nextStrongest > 35) {
            hPowerJump = false
            reasons.add("Salto potencia (>35dB)")
            score -= 20
        }

        // 3. MNC/MCC Inconsistency
        val differentMcc = neighbors.filter { it.mcc != "N/A" && it.mcc != active.mcc }
        if (differentMcc.isNotEmpty()) {
            hMcc = false
            reasons.add("Inconsistencia MCC")
            score -= 30
        }
        
        // 4. Multiple MNCs in area
        val uniqueMncs = (neighbors.map { it.mnc } + active.mnc).filter { it != "N/A" }.distinct()
        if (uniqueMncs.size > 3) {
            hMncCount = false
            reasons.add("Multitud de MNCs")
            score -= 15
        }

        // 5. TAC Deviation Audit
        if (neighbors.isNotEmpty() && active.tac != "N/A") {
            val neighborTacs = neighbors.map { it.tac }.filter { it != "N/A" }
            if (neighborTacs.isNotEmpty() && !neighborTacs.contains(active.tac)) {
                hTac = false
                reasons.add("Desviación TAC")
                score -= 20
            }
        }

        // 6. Timing Advance Audit
        active.timingAdvance?.let { ta ->
            val taDistanceMeters = if (active.networkType.contains("5G")) ta * 150 else ta * 78
            if (active.lat != null && active.lon != null) {
                getCurrentLocation()?.let { currentLoc ->
                    val results = FloatArray(1)
                    Location.distanceBetween(currentLoc.latitude, currentLoc.longitude, active.lat, active.lon, results)
                    if (results[0] > 2000 && taDistanceMeters < 500) {
                        hTa = false
                        reasons.add("Suplantación TA")
                        score -= 40
                    }
                }
            } else {
                if (!active.networkType.contains("5G") && ta <= 1 && active.dbm >= -60 && active.verified != VerificationStatus.VERIFIED) {
                    hTa = false
                    reasons.add("Proximidad anómala (TA)")
                    score -= 15
                }
            }
        }

        // 7. Ghost Cells Check
        if (active.dbm >= -70 && neighbors.isNotEmpty() && neighbors.all { it.dbm <= -120 }) {
            hGhost = false
            reasons.add("Vecinos fantasma")
            score -= 25
        }

        // 8. Hardware Ciphering Check (Android 14+)
        val hCiphering = isHardwareCipheringActive
        if (!hCiphering) {
            reasons.add("Cifrado de red anulado (A5/0)")
            score -= 50 // Castigo masivo porque esto es un ataque confirmado
        }

        // 9. Ping-Pong Effect (Cambio rápido de celda forzado)
        if (cellChangeHistory.size >= 3) {
            hPingPong = false
            reasons.add("Efecto Ping-Pong")
            score -= 25 // Castigo duro, es un comportamiento muy agresivo
        }

        // Bonificadores y penalizadores por Base de Datos
        if (active.verified == VerificationStatus.VERIFIED) {
            score += 15 // La base de datos da confianza
        } else if (active.verified == VerificationStatus.NOT_FOUND) {
            score -= 10
        }

        // Cap del score entre 0 y 100
        val finalScore = score.coerceIn(0, 100)
        
        // Si el score baja de 70, la consideramos sospechosa automáticamente
        val isSuspicious = finalScore < 70

        val report = HeuristicReport(
            isolatedCellPassed = hIsolated,
            powerJumpPassed = hPowerJump,
            mccConsistencyPassed = hMcc,
            mncCountPassed = hMncCount,
            tacDeviationPassed = hTac,
            taDistancePassed = hTa,
            ghostNeighborsPassed = hGhost,
            hardwareCipheringPassed = hCiphering,
            pingPongPassed = hPingPong
        )

        return active.copy(
            isSuspicious = isSuspicious,
            suspiciousReason = if (reasons.isNotEmpty()) reasons.joinToString(" | ") else null,
            heuristicReport = report,
            securityScore = finalScore
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
        
        // 🛡️ CORTAFUEGOS 1: Si la celda ya existe en la caché, se corta la ejecución.
        if (verificationCache.containsKey(cacheKey)) {
            return 
        }
        
        // 🛡️ CORTAFUEGOS 2: Comprobación previa en la persistencia local SQLite
        if (dbHelper.isCellVerified(cell.mnc, cell.tac, cell.cellId)) {
            verificationCache[cacheKey] = VerificationStatus.VERIFIED
            return
        }

        // Marcamos como PENDING inmediatamente para bloquear ráfagas
        verificationCache[cacheKey] = VerificationStatus.PENDING

        appendLog("[API]", "Verificando firmas geográficas para CID: ${cell.cellId}...")
        
        // 🛡️ MOTOR DE SINCRONIZACIÓN: Ejecución secuencial en hilo de fondo
        scope.launch(Dispatchers.IO) {
            var found = false
            
            // 1. Intentar con WiGLE primero (Prioridad 1)
            if (wigleApiName.isNotBlank() && wigleApiToken.isNotBlank()) {
                found = tryWigleSync(cell, cacheKey)
            }
            
            // 2. Si no se encontró en WiGLE, intentamos con OpenCellId (Prioridad 2)
            if (!found && openCellIdKey.isNotBlank() && !openCellIdKey.startsWith("pk.YOUR")) {
                found = tryOpenCellIdSync(cell, cacheKey)
            }
            
            // 3. Si ninguno encontró nada, marcamos como NOT_FOUND definitivamente
            if (!found) {
                verificationCache[cacheKey] = VerificationStatus.NOT_FOUND
                dbHelper.updateVerificationStatus(cell.mnc, cell.tac, cell.cellId, VerificationStatus.NOT_FOUND)
            }
        }
    }

    private fun tryOpenCellIdSync(cell: CellData, cacheKey: String): Boolean {
        val currentClient = if (isProxyEnabled) {
            client.newBuilder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050)))
                .build()
        } else {
            client
        }

        val url = "https://opencellid.org/cell/get?key=$openCellIdKey&mcc=${cell.mcc}&mnc=${cell.mnc}&lac=${cell.tac}&cellid=${cell.cellId}&format=json"
        appendLog("[API]", "Consultando OpenCellId para CID: ${cell.cellId}...")
        val request = Request.Builder().url(url).build()
        
        return try {
            currentClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    if (json.has("lat")) {
                        processSuccessfulVerification(json.getDouble("lat"), json.getDouble("lon"), cell, cacheKey)
                        true
                    } else false
                } else false
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun tryWigleSync(cell: CellData, cacheKey: String): Boolean {
        val currentClient = if (isProxyEnabled) {
            client.newBuilder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050)))
                .build()
        } else {
            client
        }

        val credentials = okhttp3.Credentials.basic(wigleApiName, wigleApiToken)
        
        // Parámetros limpios (WiGLE prefiere números sin ceros a la izquierda a veces)
        val cleanMcc = cell.mcc.toIntOrNull() ?: cell.mcc
        val cleanMnc = cell.mnc.toIntOrNull() ?: cell.mnc
        
        val url = "https://api.wigle.net/api/v2/cell/search?cell_net=$cleanMcc&cell_op=$cleanMnc&cell_lac=${cell.tac}&cell_id=${cell.cellId}"
        
        appendLog("[API]", "Consultando WiGLE para CID: ${cell.cellId}...")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credentials)
            .header("User-Agent", "ICdetection/1.0 (Android)")
            .build()
        
        return try {
            currentClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    if (json.has("success") && json.getBoolean("success") && json.has("results")) {
                        val results = json.getJSONArray("results")
                        if (results.length() > 0) {
                            val first = results.getJSONObject(0)
                            val lat = first.optDouble("trilat", first.optDouble("lat", 0.0))
                            val lon = first.optDouble("trilong", first.optDouble("lon", 0.0))
                            processSuccessfulVerification(lat, lon, cell, cacheKey)
                            true
                        } else false
                    } else false
                } else {
                    if (response.code == 412) {
                        appendLog("[API]", "⚠️ WiGLE Error 412: Acepta los términos en su web.")
                    }
                    false
                }
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun processSuccessfulVerification(lat: Double, lon: Double, cell: CellData, cacheKey: String) {
        appendLog("[API]", "Validación de base de datos OK. Lat/Lon obtenidas.")
        verificationCache[cacheKey] = VerificationStatus.VERIFIED
        dbHelper.updateVerificationStatus(
            cell.mnc,
            cell.tac,
            cell.cellId,
            VerificationStatus.VERIFIED)
        
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
            _dbmHistory.value = emptyList() // <-- Borramos la gráfica al cambiar de antena
            appendLog("[RADIO]", "Handover celular completado -> Nueva celda CID: $cid ($net)")
            
            // --- DISPARADOR DEL REPORTE PROFESIONAL ---
            generateAuditLog(cell) 
            // ------------------------------------------

            val currentTime = System.currentTimeMillis()
            cellChangeHistory.add(Pair(cid, currentTime))
            cellChangeHistory.removeAll { currentTime - it.second > 10000L }

            if (cellChangeHistory.size >= 3) {
                // --- NUEVO: FILTRO DE MOVIMIENTO ---
                val currentLocation = getCurrentLocation()
                val speedMps = currentLocation?.speed ?: 0f // speed está en m/s
                val isMovingFast = speedMps > 8f // 8 m/s son aprox 28.8 km/h (Umbral de coche)

                if (!isMovingFast) {
                    // Solo disparamos la alarma si estamos parados o caminando lento
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_PIP, 300)
                    appendLog("[SEC]", "🚨 ¡ALERTA! Efecto Ping-Pong detectado en parado.")
                    Log.e("MiniIC", "ANOMALÍA: Efecto Ping-Pong detectado en parado.")
                } else {
                    // Si vamos en coche, solo lo dejamos en el log como un evento de red, no como amenaza
                    appendLog("[RADIO]", "Ping-Pong detectado a ${String.format("%.1f", speedMps * 3.6f)} km/h. Ignorando alerta por movimiento.")
                }
                // ------------------------------------
            }

            if (prevCid != null) {
                scope.launch(Dispatchers.IO) {
                    dbHelper.logConnection(
                        netType = net, 
                        cid = cid, 
                        mnc = cell.mnc, 
                        tac = cell.tac, 
                        mcc = cell.mcc, 
                        dbm = dbm, 
                        verified = cell.verified,
                        score = cell.securityScore,
                        failedHeuristics = cell.suspiciousReason ?: "OK"
                    )
                }
            }
            prevCid = cid
        }

        // 2. Actualizamos la gráfica de telemetría (mantenemos los últimos 50 puntos)
        if (dbm != -999 && dbm != Int.MAX_VALUE) {
            val currentHistory = _dbmHistory.value.toMutableList()
            currentHistory.add(dbm)
            if (currentHistory.size > 50) currentHistory.removeAt(0)
            _dbmHistory.value = currentHistory

            // Actualizamos la gráfica geométrica (TA) de forma segura
            val currentTa = cell.timingAdvance
            if (currentTa != null && currentTa != Int.MAX_VALUE) {
                val currentGeo = _geoHistory.value.toMutableList()
                currentGeo.add(currentTa.toFloat())
                if (currentGeo.size > 50) currentGeo.removeAt(0)
                _geoHistory.value = currentGeo
            }
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
                // EXTRAEMOS TA DE 4G:
                var ta = info.cellSignalStrength.timingAdvance.let { if (it == Int.MAX_VALUE) null else it }
                // HACK: Si la API oficial falla, intentamos leer la cadena de texto cruda del módem
                if (ta == null || ta == Int.MAX_VALUE) {
                    try {
                        val rawString = info.cellSignalStrength.toString()
                        val taMatch = Regex("ta=([0-9]+)").find(rawString)
                        if (taMatch != null) {
                            val extracted = taMatch.groupValues[1].toInt()
                            if (extracted != Int.MAX_VALUE) ta = extracted
                        }
                    } catch (_: Exception) {}
                }

                val mcc = id.mccString ?: getNetworkOperatorMcc()
                val mnc = id.mncString ?: getNetworkOperatorMnc()
                CellData(reg, networkTypeString, id.ci.valOrNa(), mnc, id.tac.valOrNa(), dbm, mcc, timingAdvance = ta, arfcn = id.earfcn)
            }
            is CellInfoNr -> {
                val id = info.cellIdentity as CellIdentityNr
                val strength = info.cellSignalStrength as CellSignalStrengthNr
                val dbm = strength.ssRsrp
                
                // EXTRAEMOS TA DE 5G:
                var ta: Int? = null
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        val method = strength.javaClass.getMethod("getTimingAdvanceMicros")
                        val res = method.invoke(strength) as Int
                        if (res != Int.MAX_VALUE && res != -1) ta = res
                    } catch (_: Exception) { }
                }
                
                // HACK PARA 5G TENSOR: A veces el TA viene oculto en el toString() de la identidad, no en el strength
                if (ta == null || ta == Int.MAX_VALUE) {
                    try {
                        val rawString = info.toString()
                        // Buscamos patrones comunes en módems modernos
                        val taMatch = Regex("ta=([0-9]+)").find(rawString) ?: Regex("timingAdvance=([0-9]+)").find(rawString)
                        if (taMatch != null) {
                            val extracted = taMatch.groupValues[1].toInt()
                            if (extracted != Int.MAX_VALUE) ta = extracted
                        }
                    } catch (_: Exception) {}
                }

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

    private fun generateAuditLog(cell: CellData) {
        _auditStatus.value = "Auditoría en curso..."
        appendLog("[AUDIT]", "--- INICIANDO CICLO DE AUDITORÍA (9 REGLAS) ---")
        val report = cell.heuristicReport
        val results = mapOf(
            "1. Celda Aislada" to report.isolatedCellPassed,
            "2. Estabilidad Potencia" to report.powerJumpPassed,
            "3. Consistencia MCC" to report.mccConsistencyPassed,
            "4. Límite MNC" to report.mncCountPassed,
            "5. Validación Regional TAC" to report.tacDeviationPassed,
            "6. Geometría (TA)" to report.taDistancePassed,
            "7. Espectro Fantasma" to report.ghostNeighborsPassed,
            "8. Cifrado Hardware" to report.hardwareCipheringPassed,
            "9. Anti Ping-Pong" to report.pingPongPassed
        )

        results.forEach { (regla, pasado) ->
            val status = if (pasado) "PASSED" else "FAILED"
            appendLog("[HEUR]", "$regla: $status")
        }

        appendLog("[AUDIT]", "Resultado Global: ${cell.securityScore}% de seguridad.")
        if (cell.isSuspicious) {
            appendLog("[SEC]", "🚨 CRÍTICO: Antena sospechosa detectada: ${cell.suspiciousReason}")
        } else {
            appendLog("[SYS]", "✅ Entorno validado como SEGURO.")
        }
        _auditStatus.value = "Auditoría completada ✅"
    }

    /**
     * Implementación de los detectores de seguridad para interceptar IMSI-Catchers.
     * Usa reflexión para mantener compatibilidad y permitir compilación sin APIs de sistema.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    inner class ImsiCatcherSecurityCallback : TelephonyCallback(), TelephonyCallback.CellInfoListener {

        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
            // No hacer nada, es solo un listener de relleno para que Android acepte el registro.
        }
        
        @Suppress("unused")
        fun onCipheringStatusChanged(params: Any) {
            appendLog("[MODEM]", "SYS: Evento de cambio en estado de cifrado detectado.")
            try {
                val getStatusMethod = params::class.java.getMethod("getCipheringStatus")
                val status = getStatusMethod.invoke(params) as Int
                if (status == 0) { // 0: CIPHERING_STATUS_NON_CIPHERED
                    isHardwareCipheringActive = false
                    appendLog("[MODEM]", "¡ALERTA L1! Protocolo de cifrado anulado (A5/0)")
                    triggerSecurityAlert("¡PELIGRO! Conexión celular NO CIFRADA (Cifrado nulo detectado)")
                } else {
                    isHardwareCipheringActive = true
                    appendLog("[MODEM]", "SYS: Cifrado de hardware validado como ACTIVO.")
                }
            } catch (_: Exception) {
                // Intento alternativo por nombre de campo si el método falla
                try {
                    val statusField = params::class.java.getField("cipheringStatus")
                    val status = statusField.get(params) as Int
                    if (status == 0) {
                        isHardwareCipheringActive = false
                        appendLog("[MODEM]", "¡ALERTA L1! Protocolo de cifrado anulado (A5/0)")
                        triggerSecurityAlert("¡PELIGRO! Conexión celular NO CIFRADA (Cifrado nulo detectado)")
                    } else {
                        isHardwareCipheringActive = true
                        appendLog("[MODEM]", "SYS: Cifrado de hardware validado como ACTIVO.")
                    }
                } catch (_: Exception) {
                    triggerSecurityAlert("¡AVISO! Cambio de seguridad en el cifrado detectado")
                }
            }
        }

        // Detección de extracción de identidad
        @Suppress("unused", "UNUSED_PARAMETER")
        fun onCellularIdentifierDisclosure(params: Any) {
            appendLog("[MODEM]", "🚨 CRÍTICO: Detección de Disclosure de Identificador Celular!")
            triggerSecurityAlert("¡ALERTA CRÍTICA! Intento de extracción de IMSI detectado por la red")
        }
    }

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
        Box(modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(32.dp), contentAlignment = Alignment.Center) {
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
    val dbmHistory by (service?.dbmHistory ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    val geoHistory by (service?.geoHistory ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pullRefreshState = rememberPullRefreshState(refreshing = refreshing, onRefresh = {
        scope.launch { refreshing = true; service?.forceRefresh(); delay(800); refreshing = false }
    })

    val active = cellList.firstOrNull { it.isRegistered }
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(service) {
        if (service != null) {
            delay(500)
            service.forceRefresh()
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            Box(modifier = Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
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
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
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

                    // Main Grid Card (DATOS SIEMPRE VISIBLES)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                        border = BorderStroke(0.5.dp, Color(0xFF222222)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                DataBox(modifier = Modifier.weight(1f), label = "TECNOLOGÍA", value = active?.networkType ?: "N/A")
                                DataBox(modifier = Modifier.weight(1f), label = "POTENCIA", value = if (active == null) "N/A" else "${active.dbm} dBm")
                            }
                            
                            HorizontalDivider(color = Color(0xFF1A1A1A))

                            Row(modifier = Modifier.fillMaxWidth()) {
                                DataBox(modifier = Modifier.weight(1f), label = "CELL ID", value = active?.cellId ?: "N/A", highlight = true)
                                DataBox(modifier = Modifier.weight(1f), label = "TAC / LAC", value = active?.tac ?: "N/A")
                                DataBox(modifier = Modifier.weight(1f), label = "MNC", value = active?.mnc ?: "N/A")
                            }
                        }
                    }

                    if (active != null) {
                        SecurityScorePanel(active, dbmHistory, geoHistory, service)
                    }

                    if (active != null) {
                        SignalVisualizer(active, cellList.filter { !it.isRegistered })
                    }

                    if (service != null) {
                        val terminalLogs by service.liveLogs.collectAsState()
                        LiveTerminalPanel(logs = terminalLogs)
                    }

                    AuthorSignature()
                }
                
                PullRefreshIndicator(
                    refreshing = refreshing,
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    backgroundColor = Color(0xFF111111),
                    contentColor = Color(0xFF4CAF50)
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
            
            Text("APIS", color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            
            // OpenCellID Section
            Text("OpenCellID Token", color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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

            HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 4.dp))

            // WiGLE Section
            Text("WiGLE API Name", color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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

            Text("WiGLE API Token", color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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
                "Configura tus APIS para habilitar la verificación de antenas. Los datos se consultan de forma segura.",
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
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
                        .background(
                            if (i < activeBlocks) blockColor else Color(0xFF1A1A1A),
                            RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}

@Composable
fun SecurityScorePanel(active: CellData, dbmHistory: List<Int>, geoHistory: List<Float>, service: MiniICService?) {
    var viewMode by remember { mutableStateOf("NONE") } // NONE, GRAPH, GEO, HEUR
    val auditStatus by (service?.auditStatus ?: MutableStateFlow("Iniciando...")).collectAsState()
    
    val scoreColor = if (active.securityScore >= 90) Color(0xFF4CAF50) else if (active.securityScore >= 70) Color(0xFFFFA000) else Color.Red

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)), border = BorderStroke(0.5.dp, Color(0xFF222222)), shape = RoundedCornerShape(4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Fila de Puntuación + Botones
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("AUDITORÍA DE SEGURIDAD", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (auditStatus.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(auditStatus, color = Color(0xFF00FF00), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${active.securityScore}%", color = scoreColor, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            // Contenido dinámico según el botón
            AnimatedVisibility(visible = viewMode != "NONE") {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    when(viewMode) {
                        "GRAPH" -> TelemetryGraph(dbmHistory)
                        "GEO" -> GeoGraph(active, geoHistory)
                        "HEUR" -> {
                            Text("AUDITORÍA DE PARÁMETROS", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            HeuristicItem("Análisis de Celda Aislada", active.heuristicReport.isolatedCellPassed)
                            HeuristicItem("Estabilidad de Potencia (Anti-Gap)", active.heuristicReport.powerJumpPassed)
                            HeuristicItem("Consistencia de MCC", active.heuristicReport.mccConsistencyPassed)
                            HeuristicItem("Límite de Redes MNC", active.heuristicReport.mncCountPassed)
                            HeuristicItem("Validación Regional TAC", active.heuristicReport.tacDeviationPassed)
                            HeuristicItem("Coherencia Geométrica (TA)", active.heuristicReport.taDistancePassed)
                            HeuristicItem("Espectro de Vecinos (Anti-Ghost)", active.heuristicReport.ghostNeighborsPassed)
                            HeuristicItem("Cifrado de Enlace (Hardware)", active.heuristicReport.hardwareCipheringPassed)
                            HeuristicItem("Estabilidad de Conexión (Anti Ping-Pong)", active.heuristicReport.pingPongPassed)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Fila de Botones Tácticos (ABAJO)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { viewMode = if(viewMode == "GRAPH") "NONE" else "GRAPH" }, 
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if(viewMode=="GRAPH") Color(0xFF444444) else Color(0xFF222222),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Text("SEÑAL", fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = { viewMode = if(viewMode == "GEO") "NONE" else "GEO" }, 
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if(viewMode=="GEO") Color(0xFF444444) else Color(0xFF222222),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Text("GEOM", fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = { viewMode = if(viewMode == "HEUR") "NONE" else "HEUR" }, 
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if(viewMode=="HEUR") Color(0xFF444444) else Color(0xFF222222),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Text("HEUR", fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun HeuristicItem(label: String, passed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFFCCCCCC), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        if (passed) {
            Icon(Icons.Default.CheckCircle, contentDescription = "Pass", tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp))
        } else {
            Icon(Icons.Default.Cancel, contentDescription = "Fail", tint = Color(0xFFCF6679), modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
fun TelemetryGraph(dbmHistory: List<Int>) {
    val umbralPeligro = -70 // Umbral ajustable: a partir de -70 dBm, la señal es sospechosamente potente
    
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        HorizontalDivider(color = Color(0xFF1A1A1A))
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("TELEMETRÍA RSRP [UMBRAL: $umbralPeligro dBm]", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("Límite: -40 dBm", color = Color(0xFF444444), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(100.dp).padding(vertical = 8.dp)) {
            val maxPoints = 50
            val width = size.width
            val height = size.height
            
            // 1. Dibujar línea fantasma del UMBRAL DE PELIGRO
            val normalizedUmbral = 1f - ((umbralPeligro - (-140f)) / 100f)
            val umbralY = height * normalizedUmbral
            drawLine(
                color = Color.Red.copy(alpha = 0.5f),
                start = Offset(0f, umbralY),
                end = Offset(width, umbralY),
                strokeWidth = 2f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            )

            if (dbmHistory.isEmpty()) return@Canvas
            
            val minDbm = -140f
            val maxDbm = -40f
            val range = maxDbm - minDbm
            val stepX = width / (maxPoints - 1)
            val startX = width - ((dbmHistory.size - 1) * stepX)
            
            // Dibujar cada segmento de la línea
            for (i in 0 until dbmHistory.size - 1) {
                val dbm1 = dbmHistory[i]
                val dbm2 = dbmHistory[i + 1]
                
                val x1 = startX + (i * stepX)
                val y1 = height * (1f - ((dbm1 - minDbm) / range).coerceIn(0f, 1f))
                val x2 = startX + ((i + 1) * stepX)
                val y2 = height * (1f - ((dbm2 - minDbm) / range).coerceIn(0f, 1f))
                
                // Color dinámico: Rojo si supera el umbral, Cian si es seguro
                val color = if (dbm1 >= umbralPeligro || dbm2 >= umbralPeligro) Color.Red else Color(0xFF00FFCC)
                
                drawLine(
                    color = color,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 4f
                )
            }
        }
        Text("ROJO = ZONA DE ALTA POTENCIA (POSIBLE IMSI-CATCHER)", color = Color.Red, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GeoGraph(active: CellData, geoHistory: List<Float>) {
    // Si es null, es que el módem no nos da el dato.
    val isTaAvailable = active.timingAdvance != null && active.timingAdvance != Int.MAX_VALUE
    val taValue = active.timingAdvance ?: -1 
    
    val is5g = active.networkType.contains("5G")
    val multiplier = if (is5g) 150 else 78
    val distanceMeters = taValue * multiplier
    
    val distanceText = when {
        !isTaAvailable || taValue < 0 -> "NO DISPONIBLE"
        taValue == 0 -> "< $multiplier m"
        distanceMeters >= 1000 -> String.format("%.2f km", distanceMeters / 1000f)
        else -> "$distanceMeters m"
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        HorizontalDivider(color = Color(0xFF1A1A1A))
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column {
                Text("TELEMETRÍA GEOMÉTRICA (TA)", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(if (isTaAvailable && taValue >= 0) "Timing Advance: $taValue" else "Timing Advance: BLOQUEADO", color = Color(0xFFFFA000), fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("DISTANCIA FÍSICA APROX.", color = Color(0xFF444444), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text(distanceText, color = if (isTaAvailable && taValue >= 0) Color.White else Color(0xFF888888), fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(100.dp).padding(vertical = 8.dp)) {
            // Si no tenemos datos, dibujamos un mensaje visual en el Canvas
            if (geoHistory.isEmpty() || !isTaAvailable || taValue < 0) {
                 drawContext.canvas.nativeCanvas.drawText("SENSOR DE TA BLOQUEADO POR HARDWARE", 20f, 50f, android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY; textSize = 30f })
                 return@Canvas
            }
            
            val maxPoints = 50
            val width = size.width
            val height = size.height
            val maxTa = (geoHistory.maxOrNull() ?: 10f).coerceAtLeast(10f) * 1.5f 
            val stepX = width / (maxPoints - 1)
            val startX = width - ((geoHistory.size - 1) * stepX)
            
            val path = Path()
            geoHistory.forEachIndexed { index, ta ->
                val normalizedY = 1f - (ta / maxTa).coerceIn(0f, 1f)
                val y = (height * normalizedY).coerceAtMost(height - 2f)
                val x = startX + (index * stepX)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = Color(0xFFFFA000), style = Stroke(width = 3f))
        }
        
        if (!isTaAvailable || taValue < 0) {
             Text("⚠️ NOTA: El chipset Tensor de Google restringe el acceso al TA en ciertas celdas para ahorrar energía. Esto no es un fallo de tu app, es una limitación de seguridad del hardware.", color = Color(0xFFCF6679), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        } else {
             Text("NARANJA = VARIACIÓN DE DISTANCIA (TA)", color = Color(0xFFFFA000), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun LiveTerminalPanel(logs: List<String>) {
    val scrollState = rememberScrollState()

    // Auto-scroll al final cuando llegan nuevos logs
    LaunchedEffect(logs.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp), // Altura fija para no comerse toda la pantalla
        colors = CardDefaults.cardColors(containerColor = Color(0xFF050505)), // Negro puro
        border = BorderStroke(1.dp, Color(0xFF222222)),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                "RAW TERMINAL OUTPUT", 
                color = Color(0xFF444444), 
                fontFamily = FontFamily.Monospace, 
                fontSize = 8.sp, 
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                logs.forEach { logLine ->
                    Text(
                        text = logLine,
                        color = if (logLine.contains("PELIGRO") || logLine.contains("⚠️") || logLine.contains("🚨") || logLine.contains("ALERTA")) Color(0xFFCF6679) 
                                else if (logLine.contains("✅") || logLine.contains("VERIFICADA") || logLine.contains("SYS:")) Color(0xFF4CAF50)
                                else Color(0xFF00FF00), // Verde terminal clásico
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        lineHeight = 12.sp
                    )
                }
            }
        }
    }
}

fun exportToCsv(context: Context, items: List<HistoryRecord>, uri: android.net.Uri) {
    try {
        val resolver = context.contentResolver
        resolver.openOutputStream(uri).use { outputStream ->
            if (outputStream != null) {
                OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.append("Timestamp,NetType,CID,MNC,TAC,MCC,DBM,Verified,SecurityScore,FailedHeuristics\n")
                    items.forEach { item ->
                        writer.append("${item.timestamp},${item.netType},${item.cid},${item.mnc},${item.tac},${item.mcc},${item.dbm},${item.verified.name},${item.score},\"${item.failedHeuristics}\"\n")
                    }
                    writer.flush()
                }
                Toast.makeText(context, "✅ CSV exportado con éxito", Toast.LENGTH_LONG).show()
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "❌ Error al exportar: ${e.message}", Toast.LENGTH_SHORT).show()
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
            Box(modifier = Modifier
                .fillMaxWidth()
                .weight(1f), contentAlignment = Alignment.Center) {
                Text("HISTORIAL VACÍO", color = Color(0xFF444444), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val context = LocalContext.current
                val exportLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/csv")
                ) { uri ->
                    uri?.let { exportToCsv(context, items, it) }
                }

                TextButton(
                    onClick = {
                        val fileName = "miniic_history_${System.currentTimeMillis()}.csv"
                        exportLauncher.launch(fileName)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF4CAF50))
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("EXPORTAR CSV", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            LazyColumn(modifier = Modifier
                .fillMaxWidth()
                .weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groupedItems, key = { it.first }) { (cid, records) ->
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
                                
                                HorizontalDivider(color = Color(0xFF222222))
                                records.forEach { record ->
                                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                        // Línea de Fecha y Potencia
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(record.timestamp, color = Color(0xFF666666), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                            Text("${record.dbm} dBm (${record.netType})", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        }
                                        
                                        // Línea de MNC / TAC y SCORE DE SEGURIDAD
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text("MNC: ${record.mnc} | TAC: ${record.tac}", color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                            
                                            // Coloreamos el Score según la gravedad
                                            val scoreColor = if (record.score >= 90) Color(0xFF4CAF50) else if (record.score >= 70) Color(0xFFFFA000) else Color(0xFFCF6679)
                                            Text("🛡️ ${record.score}%", color = scoreColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                        }
                                        
                                        // Línea condicional: Solo aparece si hay heurísticas fallidas
                                        if (record.failedHeuristics.isNotBlank() && record.failedHeuristics != "OK") {
                                            Spacer(Modifier.height(4.dp))
                                            Text("⚠️ Fallo: ${record.failedHeuristics}", color = Color(0xFFCF6679), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
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
