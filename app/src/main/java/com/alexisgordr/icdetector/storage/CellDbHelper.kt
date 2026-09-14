package com.alexisgordr.icdetector.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.models.SignalBaseline
import com.alexisgordr.icdetector.models.CellRfStability
import com.alexisgordr.icdetector.models.CellReputation
import com.alexisgordr.icdetector.models.CellRfFingerprint
import com.alexisgordr.icdetector.models.VerificationStatus
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CellDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        private const val DATABASE_NAME = "icdetector_history.db"
        private const val DATABASE_VERSION = 9
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
        const val COLUMN_RSRQ = "rsrq"
        const val COLUMN_SINR = "sinr"
        // v2.1 — Probabilidad bayesiana en el momento de la observación (0..95).
        const val COLUMN_THREAT_PROB = "threat_prob"
        // v2.1 — Coordenada de la ANTENA devuelta por la API (WiGLE/OpenCellID). Separada de
        // lat/lon a propósito: lat/lon es SIEMPRE la posición GPS del dispositivo. Antes la
        // coordenada de la API se escribía encima de lat/lon y contaminaba el historial
        // geográfico (H11/H13) con posiciones a cientos de km.
        const val COLUMN_API_LAT = "api_lat"
        const val COLUMN_API_LON = "api_lon"
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
                    "$COLUMN_ARFCN INTEGER, " +
                    "$COLUMN_RSRQ INTEGER, " +
                    "$COLUMN_SINR INTEGER, " +
                    "$COLUMN_THREAT_PROB REAL DEFAULT 0, " +
                    "$COLUMN_API_LAT REAL, " +
                    "$COLUMN_API_LON REAL)",
        )
        createIndexes(db)
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
        if (oldVersion < 7) {
            // Migración aditiva y NO destructiva: solo añade columnas para el fingerprint RF
            // (RSRQ/SINR). Las filas existentes quedan intactas con estas columnas a NULL.
            // Cada ALTER va en su propio try/catch para que, en el caso raro de que una columna
            // ya existiera (instalación parcial), no aborte la migración ni la app.
            try { db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_RSRQ INTEGER") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_SINR INTEGER") } catch (_: Exception) {}
        }
        if (oldVersion < 8) {
            // Índices de rendimiento. NO tocan ni una sola fila de datos: solo crean estructuras
            // de búsqueda para acelerar las consultas por identidad de celda y por tiempo, que se
            // ejecutan en cada ciclo de análisis. Importante de cara a la fase de recolección,
            // cuando la tabla crecerá a decenas de miles de filas. Imposible que pierdan datos.
            createIndexes(db)
        }
        if (oldVersion < 9) {
            // Migración v2.1, aditiva y NO destructiva: tres columnas nuevas. Las filas
            // existentes quedan intactas (threat_prob = 0, api_lat/api_lon = NULL). Cada ALTER
            // en su propio try/catch por si una columna ya existiera en una instalación parcial.
            //
            // NOTA sobre el histórico anterior a v2.1: las filas viejas pueden tener en
            // lat/lon la coordenada de la ANTENA (bug corregido en esta versión) en lugar de la
            // posición GPS. No se tocan aquí — borrarlas o moverlas automáticamente sería
            // adivinar. Se recomienda partir de un historial limpio (Ajustes > borrar historial)
            // para que el baseline geográfico se construya solo con datos correctos.
            try { db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_THREAT_PROB REAL DEFAULT 0") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_API_LAT REAL") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_API_LON REAL") } catch (_: Exception) {}
        }
    }

    /**
     * Crea los índices de la tabla de histórico. Usa CREATE INDEX IF NOT EXISTS, así que es
     * idempotente y seguro: si un índice ya existe, no hace nada (no lanza error). Cada uno va
     * en su try/catch por máxima robustez. NO es una operación destructiva — un índice es una
     * estructura auxiliar de búsqueda; no modifica, mueve ni borra ninguna fila.
     *
     * - idx_cell_identity: acelera las búsquedas por (cid, mnc, tac, mcc) — H11, baseline,
     *   reputación y fingerprint filtran por esta combinación en cada ciclo.
     * - idx_timestamp: acelera el ordenado/filtrado temporal (historial reciente, podas).
     */
    private fun createIndexes(db: SQLiteDatabase) {
        try {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_cell_identity ON $TABLE_HISTORY " +
                    "($COLUMN_CID, $COLUMN_MNC, $COLUMN_TAC, $COLUMN_MCC)"
            )
        } catch (_: Exception) {}
        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_timestamp ON $TABLE_HISTORY ($COLUMN_TIMESTAMP)")
        } catch (_: Exception) {}
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
        arfcn: Int? = null,
        rsrq: Int? = null,
        sinr: Int? = null,
        threatProbability: Float = 0f
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
            if (rsrq != null) put(COLUMN_RSRQ, rsrq)
            if (sinr != null) put(COLUMN_SINR, sinr)
            put(COLUMN_THREAT_PROB, threatProbability)
            // api_lat / api_lon NO se escriben aquí: son un dato de verificación, no de la
            // observación. Los rellena updateVerificationStatus cuando la API responde.
        }
        return db.insert(TABLE_HISTORY, null, values)
    }

    fun getKnownStatus(mnc: String, tac: String, cid: String, mcc: String, currentLat: Double? = null, currentLon: Double? = null): VerificationStatus {
        val db = this.readableDatabase
        // Buscamos cualquier registro previo de esta antena que no sea PENDING o ERROR.
        // v2.1: la comprobación de cercanía usa api_lat/api_lon (posición de la ANTENA según la
        // API), que es lo que esta función siempre quiso comparar. Antes leía lat/lon, donde la
        // coordenada de la API acababa mezclada con la del GPS: la semántica era ambigua y
        // dependía de por qué camino se hubiera escrito esa fila.
        val query = "SELECT $COLUMN_VERIFIED, $COLUMN_API_LAT, $COLUMN_API_LON, $COLUMN_TIMESTAMP FROM $TABLE_HISTORY " +
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

    /**
     * Marca como [status] las observaciones aún PENDING de esta celda y, si la API devolvió la
     * posición de la antena, la guarda en [COLUMN_API_LAT]/[COLUMN_API_LON].
     *
     * v2.1 — CAMBIO IMPORTANTE: antes escribía esa coordenada en lat/lon, es decir, ENCIMA de la
     * posición GPS del dispositivo (o rellenando las filas que se habían guardado sin GPS). Ese
     * era el origen real de las coordenadas imposibles en el historial: no venían del GPS —
     * nunca pasaban por isPlausibleFix() — sino de la respuesta de WiGLE/OpenCellID. Y contaminaba
     * el baseline geográfico de H11/H13, que interpreta lat/lon como "dónde estaba yo".
     *
     * Ahora lat/lon es intocable: solo la escribe el GPS. Las dos magnitudes viven separadas.
     */
    fun updateVerificationStatus(mnc: String, tac: String, cid: String, status: VerificationStatus, lat: Double? = null, lon: Double? = null, mcc: String? = null) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_VERIFIED, status.name)
            if (lat != null) put(COLUMN_API_LAT, lat)
            if (lon != null) put(COLUMN_API_LON, lon)
        }
        val where = if (mcc != null) "$COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_MCC=? AND $COLUMN_VERIFIED='PENDING'"
                    else "$COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_VERIFIED='PENDING'"
        val args = if (mcc != null) arrayOf(cid, mnc, tac, mcc) else arrayOf(cid, mnc, tac)
        db.update(TABLE_HISTORY, values, where, args)
    }

    fun getRecords(): List<HistoryRecord> {
        val list = mutableListOf<HistoryRecord>()
        try {
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
                val rsrq = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_RSRQ))) null
                          else cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_RSRQ))
                val sinr = if (cursor.isNull(cursor.getColumnIndexOrThrow(COLUMN_SINR))) null
                          else cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_SINR))
                // Columnas v2.1. getColumnIndex (sin OrThrow) para que un export nunca falle si
                // la migración no se hubiera aplicado por cualquier motivo: -1 -> valor por defecto.
                val tpIdx = cursor.getColumnIndex(COLUMN_THREAT_PROB)
                val threatProb = if (tpIdx >= 0 && !cursor.isNull(tpIdx)) cursor.getFloat(tpIdx) else 0f
                val apiLatIdx = cursor.getColumnIndex(COLUMN_API_LAT)
                val apiLat = if (apiLatIdx >= 0 && !cursor.isNull(apiLatIdx)) cursor.getDouble(apiLatIdx) else null
                val apiLonIdx = cursor.getColumnIndex(COLUMN_API_LON)
                val apiLon = if (apiLonIdx >= 0 && !cursor.isNull(apiLonIdx)) cursor.getDouble(apiLonIdx) else null

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
                        arfcn = arfcn,
                        rsrq = rsrq,
                        sinr = sinr,
                        threatProbability = threatProb,
                        apiLat = apiLat,
                        apiLon = apiLon
                    )
                )
            } while (cursor.moveToNext())
        }
        } finally {
            cursor.close()
        }
        } catch (_: Exception) {
            // Si la BD fallara o una fila viniera malformada, devolvemos lo recogido hasta ahora
            // (export parcial) en lugar de crashear. Leer el historial nunca debe tumbar la app.
        }
        return list
    }

    fun clear() {
        val db = this.writableDatabase
        db.execSQL("DELETE FROM $TABLE_HISTORY")
    }

    /**
     * Poda de registros antiguos: borra del historial las filas más viejas que [daysToKeep]
     * días. Las consultas de detección solo miran los últimos 30 días, así que mantener un
     * margen (60 por defecto) garantiza que NO se borra nada que las heurísticas puedan usar:
     * esto solo evita que la tabla crezca sin límite registrando 24/7. No toca el esquema ni
     * los datos recientes. Devuelve el número de filas borradas.
     */
    fun pruneOldRecords(daysToKeep: Int = 60): Int {
        return try {
            val cutoff = System.currentTimeMillis() - (daysToKeep.toLong() * 24 * 60 * 60 * 1000)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val threshold = sdf.format(Date(cutoff))
            val db = this.writableDatabase
            db.delete(TABLE_HISTORY, "$COLUMN_TIMESTAMP < ?", arrayOf(threshold))
        } catch (_: Exception) {
            0
        }
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
            // Rellena SOLO la observación actual, y solo si de verdad le faltan coordenadas:
            // toma la fila MÁS RECIENTE de la celda actual (el id más alto, la que sea) y la
            // rellena únicamente si esa fila no tiene lat/lon. Esta función se llama justo cuando
            // llega un fix fresco mientras se esperaban coordenadas, así que la última fila es la
            // de "ahora mismo" -> se le estampa la posición que SÍ acabas de medir.
            //
            // Importante (más seguro que mirar "la última NULL"): si ya existiera una observación
            // MÁS NUEVA de esta celda con coordenadas, no se toca nada — no se rebusca hacia atrás
            // para rellenar una fila antigua que quedó sin GPS. Y deliberadamente NO se rellena en
            // masa el historial: estampar la posición actual sobre observaciones antiguas sería
            // inventar precisión no medida y contaminaría el historial geográfico (H11/H13). Una
            // fila que quedó sin GPS en su momento se queda sin coordenadas (dato "desconocido"),
            // que es lo honesto; las observaciones siguientes ya se guardan con coords por el
            // camino normal mientras haya GPS.
            db.update(
                TABLE_HISTORY,
                values,
                "$COLUMN_ID = (SELECT MAX($COLUMN_ID) FROM $TABLE_HISTORY " +
                    "WHERE $COLUMN_CID = ? AND $COLUMN_MNC = ? AND $COLUMN_TAC = ? AND $COLUMN_MCC = ?) " +
                    "AND $COLUMN_LAT IS NULL AND $COLUMN_LON IS NULL",
                arrayOf(cellId, mnc, tac, mcc)
            )
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Obtiene registros previos de una misma Cell ID (excluyendo la ubicación actual)
     * Limitado a los últimos 30 días para evitar datos obsoletos.
     *
     * v2.1: el LIMIT de SQL sube de 20 a 300 y el recorte a 20 pasa a hacerse DESPUÉS del filtro
     * de distancia (>50 m). Motivo: con el muestreo periódico introducido en v2.1 hay muchas más
     * observaciones desde el MISMO sitio, y todas ellas caen por el filtro de 50 m. Con el LIMIT
     * antiguo, esas 20 filas recientes del mismo sitio se llevaban todo el cupo y H11 se quedaba
     * sin registros útiles — es decir, más datos habrían DEGRADADO la heurística. Ahora se leen
     * hasta 300 filas y se conservan las 20 primeras que de verdad aportan (otro emplazamiento),
     * que es exactamente lo que H11 espera recibir.
     */
    fun getPreviousCellHistory(
        cellId: String,
        mnc: String,
        tac: String,
        mcc: String,
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
              AND $COLUMN_MCC = ?
              AND $COLUMN_TIMESTAMP < ?
              AND $COLUMN_TIMESTAMP > ?
            ORDER BY $COLUMN_ID DESC
            LIMIT 300
        """.trimIndent()

        val maxUsableRecords = 20
        val cursor = db.rawQuery(query, arrayOf(cellId, mnc, tac, mcc, recentThreshold, oldThreshold))
        try {

        if (cursor.moveToFirst()) {
            do {
                if (history.size >= maxUsableRecords) break
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
        mcc: String,
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
              AND $COLUMN_MCC = ?
              AND $COLUMN_TIMESTAMP > ?
            ORDER BY $COLUMN_ID DESC
            LIMIT 200
        """.trimIndent()

        val cursor = db.rawQuery(query, arrayOf(cellId, mnc, tac, mcc, oldThreshold))
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

        // Percentiles calculados sobre las muestras YA cargadas en memoria (sin coste de BD).
        // Robustos ante distribuciones no normales / outliers. 0 = sentinela "no fiable".
        val sorted = samples.sorted()
        fun percentile(p: Double): Int {
            val idx = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }
        val p95 = percentile(95.0)
        val p99 = percentile(99.0)

        return SignalBaseline(
            sampleCount = samples.size,
            meanDbm = mean,
            stdDevDbm = stdDev,
            minDbm = samples.minOrNull()!!,
            maxDbm = samples.maxOrNull()!!,
            p95Dbm = p95,
            p99Dbm = p99
        )
    }

    /**
     * Reputación de una celda derivada del historial propio (solo lectura, sin esquema nuevo).
     * Lee la columna 'score' ya almacenada: una celda vista muchas veces, en varios días, y
     * siempre con puntuación limpia, gana confianza. Esa confianza se usa SOLO para amortiguar
     * heurísticas débiles sobre celdas probadas (ver CellReputation). Devuelve trustScore = -1
     * (desconocida) si no hay historial suficiente para juzgar — en ese caso no se amortigua nada.
     */
    fun getCellReputation(cellId: String, mnc: String, tac: String, mcc: String): CellReputation {
        val db = this.readableDatabase
        val ninetyDaysAgo = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val threshold = dateFormat.format(Date(ninetyDaysAgo))

        val query = """
            SELECT $COLUMN_SCORE, $COLUMN_TIMESTAMP
            FROM $TABLE_HISTORY
            WHERE $COLUMN_CID = ?
              AND $COLUMN_MNC = ?
              AND $COLUMN_TAC = ?
              AND $COLUMN_MCC = ?
              AND $COLUMN_TIMESTAMP > ?
            ORDER BY $COLUMN_ID DESC
            LIMIT 500
        """.trimIndent()

        var total = 0
        var clean = 0
        val days = HashSet<String>()
        db.rawQuery(query, arrayOf(cellId, mnc, tac, mcc, threshold)).use { cursor ->
            if (cursor.moveToFirst()) {
                do {
                    val score = cursor.getInt(0)
                    val ts = cursor.getString(1) ?: ""
                    total++
                    if (score >= 85) clean++                 // observación "limpia"
                    if (ts.length >= 10) days.add(ts.substring(0, 10))  // día calendario distinto
                } while (cursor.moveToNext())
            }
        }

        val distinctDays = days.size
        val cleanRatio = if (total > 0) clean.toDouble() / total else 0.0

        // Confianza: solo se gana con VOLUMEN (muchas observaciones) repartido en VARIOS días.
        // Sin historial suficiente -> trustScore = -1 (desconocida) -> no se amortigua nada.
        val trustScore = if (total < 10 || distinctDays < 2) {
            -1
        } else {
            val volumeConf = minOf(1.0, total / 50.0)        // ~50 obs para confianza plena
            val spreadConf = minOf(1.0, distinctDays / 5.0)  // repartidas en ~5 días
            (cleanRatio * 100.0 * volumeConf * spreadConf).toInt().coerceIn(0, 100)
        }

        return CellReputation(
            observations = total,
            distinctDays = distinctDays,
            cleanRatio = cleanRatio,
            trustScore = trustScore
        )
    }

    /**
     * Huella RF (RSRQ/SINR) de una celda, derivada del historial propio (solo lectura). Lee solo
     * las filas donde rsrq/sinr no son NULL (datos a partir de la migración v7). Devuelve null si
     * no hay muestras suficientes — la firma "duerme" hasta acumular datos, evitando falsos
     * positivos tempranos. Conservadora por diseño (RSRQ/SINR son métricas ruidosas).
     */
    fun getCellRfFingerprint(
        cellId: String,
        mnc: String,
        tac: String,
        mcc: String,
        minSamples: Int = 30,
        nearLocation: Location? = null,
        radiusMeters: Float = 1000f
    ): CellRfFingerprint? {
        val db = this.readableDatabase
        val ninetyDaysAgo = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val threshold = dateFormat.format(Date(ninetyDaysAgo))

        val query = """
            SELECT $COLUMN_RSRQ, $COLUMN_SINR, $COLUMN_LAT, $COLUMN_LON
            FROM $TABLE_HISTORY
            WHERE $COLUMN_CID = ?
              AND $COLUMN_MNC = ?
              AND $COLUMN_TAC = ?
              AND $COLUMN_MCC = ?
              AND $COLUMN_RSRQ IS NOT NULL
              AND $COLUMN_SINR IS NOT NULL
              AND $COLUMN_TIMESTAMP > ?
            ORDER BY $COLUMN_ID DESC
            LIMIT 200
        """.trimIndent()

        val rsrqs = mutableListOf<Int>()
        val sinrs = mutableListOf<Int>()
        db.rawQuery(query, arrayOf(cellId, mnc, tac, mcc, threshold)).use { cursor ->
            if (cursor.moveToFirst()) {
                do {
                    val rsrq = cursor.getInt(0)
                    val sinr = cursor.getInt(1)
                    // Rangos físicos sensatos (descarta valores basura / no disponibles).
                    if (rsrq in -30..-1 && sinr in -20..40 && isSampleInZone(cursor, 2, 3, nearLocation, radiusMeters)) {
                        rsrqs.add(rsrq)
                        sinrs.add(sinr)
                    }
                } while (cursor.moveToNext())
            }
        }

        if (rsrqs.size < minSamples) return null

        fun meanStd(xs: List<Int>): Pair<Double, Double> {
            val m = xs.average()
            val v = xs.sumOf { (it - m) * (it - m) } / xs.size
            return m to sqrt(v)
        }
        val (rMean, rStd) = meanStd(rsrqs)
        val (sMean, sStd) = meanStd(sinrs)

        return CellRfFingerprint(
            sampleCount = rsrqs.size,
            rsrqMean = rMean,
            rsrqStd = rStd,
            sinrMean = sMean,
            sinrStd = sStd
        )
    }

    /**
     * ¿Esta muestra del historial pertenece a la "zona" actual? Se usa para acotar la huella RF
     * a un mismo emplazamiento (idea opcional del roadmap #5), necesario a partir de v2.1 porque
     * el muestreo periódico acumula muchísimas muestras del sitio donde más tiempo pasas y, sin
     * acotar, la media se desplazaría hacia ese sitio y la misma celda vista desde otro punto
     * podría parecer "incoherente" sin motivo.
     *
     * Reglas: sin ubicación de referencia -> se aceptan todas (comportamiento clásico). Una fila
     * SIN coordenadas se acepta (no se puede afirmar que sea de otra zona; excluirlas dejaría
     * fuera casi todas las muestras de interior, que son justo las que dan volumen). Una fila CON
     * coordenadas solo se acepta si está dentro del radio.
     */
    private fun isSampleInZone(
        cursor: Cursor,
        latIdx: Int,
        lonIdx: Int,
        nearLocation: Location?,
        radiusMeters: Float
    ): Boolean {
        if (nearLocation == null) return true
        if (cursor.isNull(latIdx) || cursor.isNull(lonIdx)) return true
        val results = FloatArray(1)
        Location.distanceBetween(
            nearLocation.latitude, nearLocation.longitude,
            cursor.getDouble(latIdx), cursor.getDouble(lonIdx), results
        )
        return results[0] <= radiusMeters
    }

    /**
     * ¿Cuántas Cell ID DISTINTAS tienen ya registrada esta misma coordenada de API?
     *
     * v2.1 — detector de coordenadas "centinela". Los datos de campo mostraron una coordenada
     * devuelta idéntica hasta el sexto decimal para 46 celdas distintas: eso no es la posición de
     * ninguna antena, es un valor por defecto de la API. Una coordenada legítima pertenece a UNA
     * antena; si aparece para varias, no verifica nada y no debe aceptarse.
     *
     * Tolerancia de ~11 m (0,0001°) para absorber redondeos entre respuestas.
     */
    fun countDistinctCellsWithApiCoordinate(lat: Double, lon: Double, toleranceDeg: Double = 0.0001): Int {
        return try {
            val db = this.readableDatabase
            val query = """
                SELECT COUNT(DISTINCT $COLUMN_CID || '-' || $COLUMN_MNC || '-' || $COLUMN_TAC || '-' || $COLUMN_MCC)
                FROM $TABLE_HISTORY
                WHERE $COLUMN_API_LAT IS NOT NULL
                  AND $COLUMN_API_LON IS NOT NULL
                  AND ABS($COLUMN_API_LAT - ?) <= ?
                  AND ABS($COLUMN_API_LON - ?) <= ?
            """.trimIndent()
            db.rawQuery(
                query,
                arrayOf(lat.toString(), toleranceDeg.toString(), lon.toString(), toleranceDeg.toString())
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * H15 (lifecycle): estabilidad de identidad RF de una Cell ID. Cuenta cuántos valores
     * DISTINTOS de PCI y de ARFCN (no nulos) se han observado para esta identidad de celda
     * (CID+MNC+TAC+MCC) en los últimos 30 días, con el nº de apariciones de cada uno y el
     * total de observaciones. Una antena legítima mantiene PCI/ARFCN fijos; varios valores
     * para una misma Cell ID sugieren un clon reconfigurándose.
     *
     * A diferencia de getPreviousCellHistory, NO filtra por ubicación ni por recencia: aquí
     * interesa all el historial de la celda (incluidas reapariciones recientes y en el sitio
     * actual). Solo lectura; no toca esquema ni escritura.
     */
    fun getCellRfStability(cellId: String, mnc: String, tac: String, mcc: String): CellRfStability {
        val pciCounts = HashMap<Int, Int>()
        val arfcnCounts = HashMap<Int, Int>()
        val recentPciCounts = HashMap<Int, Int>()
        val recentArfcnCounts = HashMap<Int, Int>()
        // v2.1: mismo recuento de PCI, pero desglosado por portadora (ARFCN). Ver CellRfStability.
        val pciByArfcn = HashMap<Int, HashMap<Int, Int>>()
        val recentPciByArfcn = HashMap<Int, HashMap<Int, Int>>()
        var total = 0
        val db = this.readableDatabase
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - (30L * 24 * 60 * 60 * 1000)
        val recentWindow = now - (48L * 60 * 60 * 1000)   // últimas 48 h
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val oldThreshold = dateFormat.format(Date(thirtyDaysAgo))
        val recentThreshold = dateFormat.format(Date(recentWindow))

        val query = """
            SELECT $COLUMN_PCI, $COLUMN_ARFCN, $COLUMN_TIMESTAMP
            FROM $TABLE_HISTORY
            WHERE $COLUMN_CID = ?
              AND $COLUMN_MNC = ?
              AND $COLUMN_TAC = ?
              AND $COLUMN_MCC = ?
              AND $COLUMN_TIMESTAMP > ?
        """.trimIndent()

        val cursor = db.rawQuery(query, arrayOf(cellId, mnc, tac, mcc, oldThreshold))
        try {
            if (cursor.moveToFirst()) {
                val pciIdx = cursor.getColumnIndexOrThrow(COLUMN_PCI)
                val arfcnIdx = cursor.getColumnIndexOrThrow(COLUMN_ARFCN)
                val tsIdx = cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)
                do {
                    total++
                    val ts = cursor.getString(tsIdx) ?: ""
                    val isRecent = ts > recentThreshold   // formato "yyyy-MM-dd HH:mm:ss" ordena lexicográficamente

                    // PCI válido LTE/NR: 0..1007. Ignorar valores fuera de rango (lecturas basura).
                    val pci = if (!cursor.isNull(pciIdx)) cursor.getInt(pciIdx).takeIf { it in 0..1007 } else null
                    val arfcn = if (!cursor.isNull(arfcnIdx)) cursor.getInt(arfcnIdx).takeIf { it > 0 } else null

                    if (pci != null) {
                        pciCounts[pci] = (pciCounts[pci] ?: 0) + 1
                        if (isRecent) recentPciCounts[pci] = (recentPciCounts[pci] ?: 0) + 1

                        // Desglose por portadora. Las filas sin ARFCN (histórico anterior a la
                        // migración v6) se agrupan bajo UNKNOWN_ARFCN, así que siguen contando
                        // entre ellas y no se mezclan con las que sí tienen portadora conocida.
                        val carrier = arfcn ?: CellRfStability.UNKNOWN_ARFCN
                        pciByArfcn.getOrPut(carrier) { HashMap() }
                            .let { it[pci] = (it[pci] ?: 0) + 1 }
                        if (isRecent) {
                            recentPciByArfcn.getOrPut(carrier) { HashMap() }
                                .let { it[pci] = (it[pci] ?: 0) + 1 }
                        }
                    }

                    if (arfcn != null) {
                        arfcnCounts[arfcn] = (arfcnCounts[arfcn] ?: 0) + 1
                        if (isRecent) recentArfcnCounts[arfcn] = (recentArfcnCounts[arfcn] ?: 0) + 1
                    }
                } while (cursor.moveToNext())
            }
        } catch (_: Exception) {
            // Lectura best-effort: ante cualquier problema, devolver lo acumulado.
        } finally {
            cursor.close()
        }

        return CellRfStability(
            totalObservations = total,
            distinctPci = pciCounts.map { it.key to it.value },
            distinctArfcn = arfcnCounts.map { it.key to it.value },
            recentDistinctPci = recentPciCounts.map { it.key to it.value },
            recentDistinctArfcn = recentArfcnCounts.map { it.key to it.value },
            pciByArfcn = pciByArfcn.mapValues { (_, counts) -> counts.map { it.key to it.value } },
            recentPciByArfcn = recentPciByArfcn.mapValues { (_, counts) -> counts.map { it.key to it.value } }
        )
    }
}
