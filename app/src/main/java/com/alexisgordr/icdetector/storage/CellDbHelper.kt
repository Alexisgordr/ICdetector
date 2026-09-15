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
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import com.alexisgordr.icdetector.models.VerificationStatus
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CellDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        private const val DATABASE_NAME = "icdetector_history.db"
        private const val DATABASE_VERSION = 12
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
        // Confianza de anomalía en el momento de la observación (0..95).
        //
        // El nombre FÍSICO de la columna se queda en "threat_prob" a propósito. v2.1 renombra
        // el concepto en todo el código y en el CSV, pero renombrar la columna en disco exigiría
        // reconstruir la tabla: `ALTER TABLE ... RENAME COLUMN` necesita SQLite 3.25+, y minSdk es
        // 29 (Android 10), que trae 3.22. Una migración destructiva para cambiar una etiqueta
        // interna que nadie ve sería un riesgo sin contrapartida. El identificador Kotlin y la
        // cabecera del export —lo que sí se lee— dicen lo correcto.
        const val COLUMN_ANOMALY_CONFIDENCE = "threat_prob"
        // v2.1 — Coordenada de la ANTENA devuelta por la API (WiGLE/OpenCellID). Separada de
        // lat/lon a propósito: lat/lon es SIEMPRE la posición GPS del dispositivo. Antes la
        // coordenada de la API se escribía encima de lat/lon y contaminaba el historial
        // geográfico (H11/H13) con posiciones a cientos de km.
        const val COLUMN_API_LAT = "api_lat"
        const val COLUMN_API_LON = "api_lon"
        // v2.1 — Timing Advance crudo y la unidad en la que vino. Ver HistoryRecord.
        const val COLUMN_TA = "ta"
        const val COLUMN_TA_UNIT = "ta_unit"
        // v2.1 — Tecnología de radio según la CLASE de CellInfo (LTE/NR/UMTS/GSM). NO confundir con
        // net_type, que guarda la cadena de TelephonyDisplayInfo: esa describe el icono de la barra
        // de estado y en el historial de campo hay 54 celdas que alternan entre "4G" y "5G" sin
        // cambiar de identidad. Para analizar los datos hace falta el dato firme, no la etiqueta.
        const val COLUMN_RADIO = "radio"

        /**
         * Cuánto vale una verificación antes de volver a preguntar — v2.1.
         *
         * No es una fecha de caducidad de la evidencia: las filas verificadas siguen en el
         * historial para siempre y el análisis posterior las verá. Es la distancia entre dos
         * afirmaciones que conviene no confundir — "esta celda estuvo verificada" y "tenemos una
         * confirmación reciente de esta celda" —, porque las bases públicas cambian y los
         * identificadores celulares se reconfiguran y se reutilizan.
         *
         * 30 días: suficiente para no gastar consultas a diario con las celdas de casa y del
         * trabajo, y corto frente al ritmo al que una operadora reorganiza su red.
         */
        const val VERIFIED_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** Re-intento de una negativa firme: 1 h. Una antena recién desplegada puede aparecer. */
        const val NOT_FOUND_TTL_MS = 60L * 60 * 1000

        /**
         * Distancia máxima creíble entre tú y la antena a la que estás conectado.
         *
         * Una sola cifra para las dos preguntas que antes tenían dos: si se acepta la coordenada
         * que devuelve la API (isValidCoordinate) y si una verificación guardada sigue valiendo
         * donde estás ahora (getKnownStatus). Con 50 km y 5 km respectivamente, una antena aceptada
         * a 12 km se reconsultaba después por "demasiado lejos", indefinidamente.
         *
         * 50 km es generoso a propósito: las macroceldas rurales llegan lejos y el objetivo de esta
         * barrera no es la precisión, sino descartar lo imposible — la coordenada a 1.100 km que
         * devolvía una búsqueda mal formulada.
         */
        const val MAX_PLAUSIBLE_ANTENNA_DISTANCE_M = 50_000f
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
                    "$COLUMN_ANOMALY_CONFIDENCE REAL DEFAULT 0, " +
                    "$COLUMN_API_LAT REAL, " +
                    "$COLUMN_API_LON REAL, " +
                    "$COLUMN_TA INTEGER, " +
                    "$COLUMN_TA_UNIT TEXT, " +
                    "$COLUMN_RADIO TEXT)",
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
            // Migración a esquema 9, aditiva y NO destructiva: tres columnas nuevas. Las filas
            // existentes quedan intactas (threat_prob = 0, api_lat/api_lon = NULL). Cada ALTER
            // en su propio try/catch por si una columna ya existiera en una instalación parcial.
            //
            // NOTA sobre el histórico anterior a v2.1: las filas viejas pueden tener en
            // lat/lon la coordenada de la ANTENA (bug corregido en esta versión) en lugar de la
            // posición GPS. No se tocan aquí — borrarlas o moverlas automáticamente sería
            // adivinar. Se recomienda partir de un historial limpio (Ajustes > borrar historial)
            // para que el baseline geográfico se construya solo con datos correctos.
            try { db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_ANOMALY_CONFIDENCE REAL DEFAULT 0") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_API_LAT REAL") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_API_LON REAL") } catch (_: Exception) {}
        }
        if (oldVersion < 10) {
            // Migración a esquema 10, aditiva y NO destructiva: se registra el Timing Advance
            // crudo con su unidad. Las filas anteriores quedan con ambas columnas a NULL, que es lo
            // honesto: en aquel momento el dato no se guardaba, y eso no es lo mismo que "no había".
            try { db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_TA INTEGER") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_TA_UNIT TEXT") } catch (_: Exception) {}
        }
        if (oldVersion < 11) {
            // Migración a esquema 11, aditiva y NO destructiva: una columna para la tecnología de
            // radio. Las filas anteriores quedan con NULL, y las consultas de identidad lo tratan
            // como "no consta" en vez de como un desacuerdo — un historial antiguo sigue siendo
            // válido, sencillamente no sabe de qué tecnología era cada observación.
            try { db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN $COLUMN_RADIO TEXT") } catch (_: Exception) {}
        }
        if (oldVersion < 12) {
            // La identidad histórica completa incluye la tecnología. El índice anterior se
            // conserva para compatibilidad, y este evita mezclar o ralentizar LTE/NR/UMTS/GSM.
            createRadioIdentityIndex(db)
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
        createRadioIdentityIndex(db)
        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_timestamp ON $TABLE_HISTORY ($COLUMN_TIMESTAMP)")
        } catch (_: Exception) {}
    }

    private fun createRadioIdentityIndex(db: SQLiteDatabase) {
        try {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_cell_identity_radio ON $TABLE_HISTORY " +
                    "($COLUMN_CID, $COLUMN_MNC, $COLUMN_TAC, $COLUMN_MCC, $COLUMN_RADIO)"
            )
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
        anomalyConfidence: Float = 0f,
        timingAdvance: Int? = null,
        timingAdvanceUnit: TimingAdvanceUnit = TimingAdvanceUnit.UNKNOWN,
        radio: RadioTech = RadioTech.UNKNOWN
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
            put(COLUMN_RADIO, radio.name)
            put(COLUMN_SCORE, score)
            put(COLUMN_FAILED_H, failedHeuristics)
            if (lat != null) put(COLUMN_LAT, lat)
            if (lon != null) put(COLUMN_LON, lon)
            if (pci != null) put(COLUMN_PCI, pci)
            if (arfcn != null) put(COLUMN_ARFCN, arfcn)
            if (rsrq != null) put(COLUMN_RSRQ, rsrq)
            if (sinr != null) put(COLUMN_SINR, sinr)
            put(COLUMN_ANOMALY_CONFIDENCE, anomalyConfidence)
            // El TA solo se guarda si el módem lo entregó. Una columna a NULL significa "este
            // teléfono no reportó TA en esta observación", que es justo lo que hay que poder medir.
            if (timingAdvance != null) {
                put(COLUMN_TA, timingAdvance)
                put(COLUMN_TA_UNIT, timingAdvanceUnit.name)
            }
            // api_lat / api_lon NO se escriben aquí: son un dato de verificación, no de la
            // observación. Los rellena updateVerificationStatus cuando la API responde.
        }
        return db.insert(TABLE_HISTORY, null, values)
    }

    /**
     * Edad en milisegundos de un registro a partir de su timestamp, o null si no se puede leer.
     * Devolver null significa "no sé cuándo fue", y quien pregunta trata ese caso como "no ha
     * caducado": inventar una edad sería peor que no tenerla.
     */
    private fun edadDeRegistro(timestamp: String?): Long? {
        if (timestamp.isNullOrBlank()) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val fecha = sdf.parse(timestamp) ?: return null
            (Date().time - fecha.time).coerceAtLeast(0L)
        } catch (_: Exception) {
            null
        }
    }

    fun getKnownStatus(
        mnc: String,
        tac: String,
        cid: String,
        mcc: String,
        currentLat: Double? = null,
        currentLon: Double? = null,
        // Sin valor por defecto, a propósito. Con el WHERE estricto en `radio`, una llamada que se
        // olvidara de pasarlo no fallaría: buscaría filas de tecnología UNKNOWN y devolvería
        // PENDING para siempre, en silencio. Que lo exija el compilador cuesta nada y cierra esa
        // puerta. Mismo criterio que updateVerificationStatus.
        radio: RadioTech
    ): VerificationStatus {
        val db = this.readableDatabase
        // Solo se reutiliza una verificación que conserve coordenadas de API. Las observaciones
        // periódicas copian el estado visual pero no escriben api_lat/api_lon; usar su timestamp
        // renovaba artificialmente el TTL. NOT_FOUND tampoco se reutiliza desde la DB: no existe
        // aún una columna con la fecha de la consulta API y una muestra nueva lo haría eterno.
        // La caché de sesión ya limita sus reintentos a una hora.
        // v2.1: la comprobación de cercanía usa api_lat/api_lon (posición de la ANTENA según la
        // API), que es lo que esta función siempre quiso comparar. Antes leía lat/lon, donde la
        // coordenada de la API acababa mezclada con la del GPS: la semántica era ambigua y
        // dependía de por qué camino se hubiera escrito esa fila.
        // La tecnología forma parte de la identidad de verificación. Las filas antiguas con
        // `radio` NULL se conservan como evidencia histórica, pero no pueden confirmar hoy una
        // tecnología que nunca registraron. Deben volver a consultarse.
        val query = "SELECT $COLUMN_VERIFIED, $COLUMN_API_LAT, $COLUMN_API_LON, $COLUMN_TIMESTAMP FROM $TABLE_HISTORY " +
                    "WHERE $COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_MCC=? " +
                    "AND $COLUMN_RADIO=? " +
                    "AND $COLUMN_VERIFIED='VERIFIED' " +
                    "AND $COLUMN_API_LAT IS NOT NULL AND $COLUMN_API_LON IS NOT NULL " +
                    "ORDER BY $COLUMN_ID DESC LIMIT 1"

        val cursor = db.rawQuery(query, arrayOf(cid, mnc, tac, mcc, radio.name))
        var status = VerificationStatus.PENDING
        try {
        
        if (cursor.moveToFirst()) {
            val savedStatusStr = cursor.getString(0)
            val savedLat = if (cursor.isNull(1)) null else cursor.getDouble(1)
            val savedLon = if (cursor.isNull(2)) null else cursor.getDouble(2)
            val savedTimeStr = cursor.getString(3)
            
            val savedStatus = try { VerificationStatus.valueOf(savedStatusStr) } catch(_: Exception) { VerificationStatus.PENDING }
            
            if (savedStatus == VerificationStatus.VERIFIED) {
                // TTL de la verificación — v2.1.
                //
                // "Estuvo verificada una vez" y "tenemos una confirmación reciente" no son lo mismo,
                // y hasta aquí eran indistinguibles: una verificación valía para siempre. Las bases
                // públicas cambian, y los identificadores celulares se reconfiguran y se reutilizan;
                // una confirmación de hace medio año no dice nada del presente. Pasado el TTL se
                // vuelve a preguntar. El hecho histórico NO se borra: las filas verificadas siguen
                // en el historial, que es lo que se analizará luego.
                val edad = edadDeRegistro(savedTimeStr)
                if (edad != null && edad > VERIFIED_TTL_MS) {
                    status = VerificationStatus.PENDING
                } else if (currentLat == null || currentLon == null || savedLat == null || savedLon == null) {
                    status = VerificationStatus.VERIFIED
                } else {
                    // Misma vara de medir que isValidCoordinate() al aceptar la coordenada. Antes
                    // esto exigía 5 km mientras la aceptación permitía 50: una antena aceptada a
                    // 12 km quedaba luego marcada como "demasiado lejos" y se reconsultaba sin
                    // motivo, en bucle. Dos políticas para la misma pregunta es una de más.
                    val results = FloatArray(1)
                    Location.distanceBetween(currentLat, currentLon, savedLat, savedLon, results)
                    status = if (results[0] < MAX_PLAUSIBLE_ANTENNA_DISTANCE_M) VerificationStatus.VERIFIED
                             else VerificationStatus.PENDING
                }
            }
        }
        } finally {
            cursor.close()
        }
        return status
    }

    /**
     * Marca como [status] las observaciones aún PENDING de esta celda y tecnología y, si la API
     * devolvió la posición de la antena, la guarda en [COLUMN_API_LAT]/[COLUMN_API_LON].
     *
     * v2.1 — CAMBIO IMPORTANTE: antes escribía esa coordenada en lat/lon, es decir, ENCIMA de la
     * posición GPS del dispositivo (o rellenando las filas que se habían guardado sin GPS). Ese
     * era el origen real de las coordenadas imposibles en el historial: no venían del GPS —
     * nunca pasaban por isPlausibleFix() — sino de la respuesta de WiGLE/OpenCellID. Y contaminaba
     * el baseline geográfico de H11/H13, que interpreta lat/lon como "dónde estaba yo".
     *
     * Ahora lat/lon es intocable: solo la escribe el GPS. Las dos magnitudes viven separadas.
     * [COLUMN_RADIO] también forma parte del WHERE: una verificación LTE nunca actualiza una fila
     * GSM/UMTS/NR que comparta por casualidad los identificadores numéricos.
     */
    fun updateVerificationStatus(
        mnc: String,
        tac: String,
        cid: String,
        status: VerificationStatus,
        lat: Double? = null,
        lon: Double? = null,
        mcc: String? = null,
        radio: RadioTech
    ) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_VERIFIED, status.name)
            if (lat != null) put(COLUMN_API_LAT, lat)
            if (lon != null) put(COLUMN_API_LON, lon)
        }
        val where = if (mcc != null) "$COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_MCC=? AND $COLUMN_RADIO=? AND $COLUMN_VERIFIED='PENDING'"
                    else "$COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_RADIO=? AND $COLUMN_VERIFIED='PENDING'"
        val args = if (mcc != null) arrayOf(cid, mnc, tac, mcc, radio.name) else arrayOf(cid, mnc, tac, radio.name)
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
                val tpIdx = cursor.getColumnIndex(COLUMN_ANOMALY_CONFIDENCE)
                val threatProb = if (tpIdx >= 0 && !cursor.isNull(tpIdx)) cursor.getFloat(tpIdx) else 0f
                val apiLatIdx = cursor.getColumnIndex(COLUMN_API_LAT)
                val apiLat = if (apiLatIdx >= 0 && !cursor.isNull(apiLatIdx)) cursor.getDouble(apiLatIdx) else null
                val apiLonIdx = cursor.getColumnIndex(COLUMN_API_LON)
                val apiLon = if (apiLonIdx >= 0 && !cursor.isNull(apiLonIdx)) cursor.getDouble(apiLonIdx) else null
                val taIdx = cursor.getColumnIndex(COLUMN_TA)
                val ta = if (taIdx >= 0 && !cursor.isNull(taIdx)) cursor.getInt(taIdx) else null
                val taUnitIdx = cursor.getColumnIndex(COLUMN_TA_UNIT)
                val taUnit = if (taUnitIdx >= 0 && !cursor.isNull(taUnitIdx)) {
                    runCatching { TimingAdvanceUnit.valueOf(cursor.getString(taUnitIdx)) }
                        .getOrDefault(TimingAdvanceUnit.UNKNOWN)
                } else TimingAdvanceUnit.UNKNOWN
                val radioIdx = cursor.getColumnIndex(COLUMN_RADIO)
                val radio = if (radioIdx >= 0 && !cursor.isNull(radioIdx)) {
                    runCatching { RadioTech.valueOf(cursor.getString(radioIdx)) }
                        .getOrDefault(RadioTech.UNKNOWN)
                } else RadioTech.UNKNOWN

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
                        anomalyConfidence = threatProb,
                        apiLat = apiLat,
                        apiLon = apiLon,
                        timingAdvance = ta,
                        timingAdvanceUnit = taUnit,
                        radio = radio
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
        radio: RadioTech,
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
                    "WHERE $COLUMN_CID = ? AND $COLUMN_MNC = ? AND $COLUMN_TAC = ? AND $COLUMN_MCC = ? " +
                    "AND $COLUMN_RADIO = ?) " +
                    "AND $COLUMN_LAT IS NULL AND $COLUMN_LON IS NULL",
                arrayOf(cellId, mnc, tac, mcc, radio.name)
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
        radio: RadioTech,
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
              AND $COLUMN_RADIO = ?
              AND $COLUMN_TIMESTAMP < ?
              AND $COLUMN_TIMESTAMP > ?
            ORDER BY $COLUMN_ID DESC
            LIMIT 300
        """.trimIndent()

        val maxUsableRecords = 20
        val cursor = db.rawQuery(query, arrayOf(cellId, mnc, tac, mcc, radio.name, recentThreshold, oldThreshold))
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
        radio: RadioTech,
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
              AND $COLUMN_RADIO = ?
              AND $COLUMN_TIMESTAMP > ?
            ORDER BY $COLUMN_ID DESC
            LIMIT 200
        """.trimIndent()

        val cursor = db.rawQuery(query, arrayOf(cellId, mnc, tac, mcc, radio.name, oldThreshold))
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
    fun getCellReputation(cellId: String, mnc: String, tac: String, mcc: String, radio: RadioTech): CellReputation {
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
              AND $COLUMN_RADIO = ?
              AND $COLUMN_TIMESTAMP > ?
            ORDER BY $COLUMN_ID DESC
            LIMIT 500
        """.trimIndent()

        var total = 0
        var clean = 0
        val days = HashSet<String>()
        db.rawQuery(query, arrayOf(cellId, mnc, tac, mcc, radio.name, threshold)).use { cursor ->
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
        radio: RadioTech,
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
              AND $COLUMN_RADIO = ?
              AND $COLUMN_RSRQ IS NOT NULL
              AND $COLUMN_SINR IS NOT NULL
              AND $COLUMN_TIMESTAMP > ?
            ORDER BY $COLUMN_ID DESC
            LIMIT 200
        """.trimIndent()

        val rsrqs = mutableListOf<Int>()
        val sinrs = mutableListOf<Int>()
        db.rawQuery(query, arrayOf(cellId, mnc, tac, mcc, radio.name, threshold)).use { cursor ->
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
     * ¿Cuántas ÁREAS DE SEGUIMIENTO distintas (MCC-MNC-TAC) tienen ya registrada esta misma
     * coordenada de API, sin contar la del propio interesado?
     *
     * v2.1 — detector de coordenadas "centinela". Los datos de campo mostraron una coordenada
     * devuelta idéntica hasta el sexto decimal para 46 celdas distintas: eso no es la posición de
     * ninguna antena, es un valor por defecto de la API.
     *
     * **Por qué cuenta áreas y no celdas.** La primera versión contaba Cell IDs distintas, y eso
     * castigaba justo lo que es normal: un mástil real aloja muchas celdas —varios sectores, varias
     * bandas, LTE y NR— y las bases públicas les atribuyen prácticamente la misma coordenada. Al
     * tercer vecino de su propio emplazamiento, una antena perfectamente legítima quedaba
     * descartada, y el problema EMPEORABA con el tiempo: cuantas más celdas verificadas guardaba el
     * historial, más coordenadas cruzaban el umbral. Celdas verificadas la semana pasada empezaban
     * a salir "no registradas" esta. Un centinela de verdad no se parece a eso: aparece en áreas de
     * seguimiento distintas y a cientos de kilómetros, no en los tres sectores del mismo poste.
     *
     * Por eso se excluye además el área del propio consultante: verificar de nuevo una celda no
     * puede convertir su propia coordenada, ya guardada, en prueba contra ella misma.
     *
     * Tolerancia de ~11 m (0,0001°) para absorber redondeos entre respuestas.
     */
    fun countDistinctAreasWithApiCoordinate(
        lat: Double,
        lon: Double,
        excludeMcc: String,
        excludeMnc: String,
        excludeTac: String,
        toleranceDeg: Double = 0.0001
    ): Int {
        return try {
            val db = this.readableDatabase
            val query = """
                SELECT COUNT(DISTINCT $COLUMN_MCC || '-' || $COLUMN_MNC || '-' || $COLUMN_TAC)
                FROM $TABLE_HISTORY
                WHERE $COLUMN_API_LAT IS NOT NULL
                  AND $COLUMN_API_LON IS NOT NULL
                  AND ABS($COLUMN_API_LAT - ?) <= ?
                  AND ABS($COLUMN_API_LON - ?) <= ?
                  AND NOT ($COLUMN_MCC=? AND $COLUMN_MNC=? AND $COLUMN_TAC=?)
            """.trimIndent()
            db.rawQuery(
                query,
                arrayOf(
                    lat.toString(), toleranceDeg.toString(),
                    lon.toString(), toleranceDeg.toString(),
                    excludeMcc, excludeMnc, excludeTac
                )
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * ¿Consta esta celda como VERIFICADA **recientemente** en el historial? — v2.1
     *
     * Se usa para una regla simple: una consulta que falla, o que vuelve vacía, **no borra** una
     * verificación reciente. WiGLE y OpenCellID no dan de baja antenas; si una celda estuvo en sus
     * bases, lo normal es que siga estándolo, y cuando una reconsulta dice "no encontrada" lo que
     * suele haber cambiado es la cuota diaria, el permiso de la cuenta o la cobertura.
     *
     * Con el matiz de "reciente" ([VERIFIED_TTL_MS]): pasado el TTL, una negativa explícita SÍ
     * puede cambiar el estado. Una confirmación de hace medio año no debe blindar a una celda
     * indefinidamente, porque los identificadores se reconfiguran y se reutilizan.
     */
    fun hasRecentVerifiedRecord(
        cid: String,
        mnc: String,
        tac: String,
        mcc: String,
        /** Obligatorio por el mismo motivo que en [getKnownStatus]. */
        radio: RadioTech
    ): Boolean {
        return try {
            val db = this.readableDatabase
            db.rawQuery(
                "SELECT $COLUMN_TIMESTAMP FROM $TABLE_HISTORY WHERE $COLUMN_CID=? AND $COLUMN_MNC=? " +
                    "AND $COLUMN_TAC=? AND $COLUMN_MCC=? " +
                    "AND $COLUMN_RADIO=? " +
                    "AND $COLUMN_VERIFIED='VERIFIED' " +
                    "AND $COLUMN_API_LAT IS NOT NULL AND $COLUMN_API_LON IS NOT NULL " +
                    "ORDER BY $COLUMN_ID DESC LIMIT 1",
                arrayOf(cid, mnc, tac, mcc, radio.name)
            ).use { c ->
                if (!c.moveToFirst()) return@use false
                val edad = edadDeRegistro(c.getString(0))
                // Sin fecha legible se conserva la verificación: ante la duda, no se destruye una
                // confirmación previa por no saber cuándo se hizo.
                edad == null || edad <= VERIFIED_TTL_MS
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Última posición conocida de la ANTENA para esta celda, según WiGLE/OpenCellID — v2.1.
     *
     * Solo lectura de las columnas api_lat/api_lon, que la verificación ya rellenaba pero que
     * nadie volvía a consultar después: `CellData.lat/lon` solo lleva la coordenada durante el
     * ciclo inmediatamente posterior a la verificación, y en los miles de ciclos siguientes está
     * vacía. Esto la recupera para poder mostrar la distancia a la antena de forma continua.
     *
     * Devuelve null si esa celda nunca se verificó con coordenada.
     */
    fun getCellApiLocation(cellId: String, mnc: String, tac: String, mcc: String, radio: RadioTech): Pair<Double, Double>? {
        return try {
            val db = this.readableDatabase
            val query = """
                SELECT $COLUMN_API_LAT, $COLUMN_API_LON
                FROM $TABLE_HISTORY
                WHERE $COLUMN_CID = ? AND $COLUMN_MNC = ? AND $COLUMN_TAC = ? AND $COLUMN_MCC = ?
                  AND $COLUMN_RADIO = ?
                  AND $COLUMN_API_LAT IS NOT NULL AND $COLUMN_API_LON IS NOT NULL
                ORDER BY $COLUMN_ID DESC
                LIMIT 1
            """.trimIndent()
            db.rawQuery(query, arrayOf(cellId, mnc, tac, mcc, radio.name)).use { c ->
                if (c.moveToFirst()) c.getDouble(0) to c.getDouble(1) else null
            }
        } catch (_: Exception) {
            null
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
    fun getCellRfStability(cellId: String, mnc: String, tac: String, mcc: String, radio: RadioTech): CellRfStability {
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
              AND $COLUMN_RADIO = ?
              AND $COLUMN_TIMESTAMP > ?
        """.trimIndent()

        val cursor = db.rawQuery(query, arrayOf(cellId, mnc, tac, mcc, radio.name, oldThreshold))
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
