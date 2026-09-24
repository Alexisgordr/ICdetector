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
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.IncidentRecord
import com.alexisgordr.icdetector.models.IncidentState
import com.alexisgordr.icdetector.models.ForensicCase
import com.alexisgordr.icdetector.models.ForensicCaseState
import com.alexisgordr.icdetector.models.ForensicCaseOrigin
import com.alexisgordr.icdetector.models.ForensicPruneResult
import com.alexisgordr.icdetector.core.ForensicRetentionPolicy
import com.alexisgordr.icdetector.forensics.ForensicStore
import com.alexisgordr.icdetector.models.ForensicSample
import com.alexisgordr.icdetector.models.identityKey
import com.alexisgordr.icdetector.models.CellLocationSample
import com.alexisgordr.icdetector.models.TransitionCoherenceResult
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.CellTransitionSummary
import com.alexisgordr.icdetector.models.LocalCellTrustEvidence
import com.alexisgordr.icdetector.models.LocalRfReconfiguration
import com.alexisgordr.icdetector.models.LOCAL_TRUST_RECONFIGURATION_HISTORY_LIKE
import com.alexisgordr.icdetector.models.MobilityGeometrySnapshot
import com.alexisgordr.icdetector.models.MobilityTripSummary
import com.alexisgordr.icdetector.models.MobilityGeometryProjection
import com.alexisgordr.icdetector.core.*
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CellDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION), ForensicStore, MobilityFamiliarityStore {
    companion object {
        private const val DATABASE_NAME = "icdetector_history.db"
        private const val DATABASE_VERSION = 18
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
        const val TABLE_INCIDENTS = "incidents"
        const val TABLE_FORENSIC_CASES = "forensic_cases"
        const val TABLE_FORENSIC_SAMPLES = "forensic_samples"
        const val TABLE_CELL_TRANSITIONS = "cell_transitions"
        const val TABLE_SITE_CELLS = "site_cell_evidence"
        const val TABLE_SITE_DAYS = "site_cell_days"
        const val TABLE_SITE_EVENTS = "site_cell_events"
        const val TABLE_SITE_MOTION_DAYS = "site_motion_days"
        const val TABLE_SITE_HOLDS = "site_holds"
        const val TABLE_SITE_SHADOW_TRIGGERS = "site_shadow_triggers"
        const val TABLE_SITE_RF_NEIGHBOURS = "site_rf_neighbours"
        const val TABLE_SITE_RF_NEIGHBOUR_DAYS = "site_rf_neighbour_days"
        const val TABLE_MOBILITY_TRIPS = "mobility_trips"
        const val TABLE_MOBILITY_TRIP_CELLS = "mobility_trip_cells"
        const val TABLE_MOBILITY_TRIP_EDGES = "mobility_trip_edges"

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
         * Días de historial que se conservan.
         *
         * v2.3.3 — Eran 60, elegidos contra la ventana de análisis de 30 días de las heurísticas.
         * Nadie los comparó con la duración de la CAMPAÑA: con una recolección de 90 días, la
         * poda que corre en cada arranque del servicio borraba el primer mes de datos —incluido
         * el arranque que ocurre al abrir la app para exportar—. 120 días cubren la campaña
         * completa con margen y siguen acotando el crecimiento de la tabla.
         */
        const val DEFAULT_RETENTION_DAYS = 120

        /**
         * Tope duro de muestras forenses conservadas. Cada muestra ronda los 6-10 KB (lleva el
         * terminal y los diagnósticos), así que sin un límite por número de filas —y no solo por
         * antigüedad— una racha de casos podía llenar el disco, y un disco lleno apaga la
         * recolección en silencio.
         */
        const val MAX_FORENSIC_SAMPLES = 20_000

        /**
         * Una fila solo puede entrenar los baselines si el motor la consideró limpia. Además, la
         * identidad completa debe haber acumulado varias filas limpias en días distintos: hasta
         * entonces sus observaciones se conservan, pero permanecen en cuarentena.
         */
        const val TRUSTED_BASELINE_MIN_SCORE = 85
        const val TRUSTED_BASELINE_MIN_SAMPLES = 5
        const val TRUSTED_BASELINE_MIN_DAYS = 2

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

    /**
     * Activa las claves ajenas — v2.8.0.
     *
     * `forensic_samples` declara `FOREIGN KEY(case_id) REFERENCES forensic_cases(id) ON DELETE
     * CASCADE` desde el esquema 14, pero SQLite ignora esa cláusula salvo que se active por
     * conexión: `PRAGMA foreign_keys` vale OFF por defecto. O sea que la garantía estaba escrita
     * en la tabla y no se aplicaba, y una muestra podía sobrevivir a su caso.
     *
     * [onConfigure] es el único sitio donde se puede activar: corre antes de `onCreate`/`onUpgrade`
     * y fuera de toda transacción (`setForeignKeyConstraintsEnabled` lanza si hay una abierta).
     *
     * El barrido de huérfanos va después, y en su propio try/catch: en la primera instalación las
     * tablas todavía no existen —`onCreate` corre a continuación— y la sentencia falla sin que eso
     * sea un error. Activar las claves ajenas NO valida las filas ya escritas, solo las futuras;
     * por eso hay que barrer a mano lo que el esquema anterior pudo dejar suelto.
     */
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        try {
            // La comprobación previa evita abrir una transacción de escritura en cada arranque
            // solo para borrar cero filas, que es el caso normal.
            val hasOrphans = db.rawQuery(
                "SELECT 1 FROM $TABLE_FORENSIC_SAMPLES WHERE case_id NOT IN " +
                    "(SELECT id FROM $TABLE_FORENSIC_CASES) LIMIT 1",
                null
            ).use { it.moveToFirst() }
            if (hasOrphans) {
                db.execSQL(
                    "DELETE FROM $TABLE_FORENSIC_SAMPLES WHERE case_id NOT IN " +
                        "(SELECT id FROM $TABLE_FORENSIC_CASES)"
                )
            }
        } catch (_: Exception) {
            // Primera instalación (las tablas aún no existen) o base en un estado del que no se
            // puede barrer. En ninguno de los dos casos hay nada que salvar, y fallar aquí
            // impediría abrir la base entera.
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // v2.10.1 was still local when RAT-specific validation was hardened. If a development
        // install already persisted an impossible sentinel/range, remove only that RF context;
        // serving history, full neighbour identities and every valid fingerprint remain intact.
        val exists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(TABLE_SITE_RF_NEIGHBOURS)
        ).use { it.moveToFirst() }
        if (!exists) return
        val valid = "((radio='LTE' AND arfcn BETWEEN 0 AND 262143 AND pci BETWEEN 0 AND 503) OR " +
            "(radio='NR' AND arfcn BETWEEN 0 AND 3279165 AND pci BETWEEN 0 AND 1007) OR " +
            "(radio='UMTS' AND arfcn BETWEEN 0 AND 16383 AND pci BETWEEN 0 AND 511)) AND " +
            "fingerprint=('RFCTX:v1:'||radio||':'||arfcn||':'||pci)"
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM $TABLE_SITE_RF_NEIGHBOUR_DAYS WHERE EXISTS (SELECT 1 FROM $TABLE_SITE_RF_NEIGHBOURS r WHERE r.site_key=$TABLE_SITE_RF_NEIGHBOUR_DAYS.site_key AND r.fingerprint=$TABLE_SITE_RF_NEIGHBOUR_DAYS.fingerprint AND NOT ($valid))")
            db.execSQL("DELETE FROM $TABLE_SITE_RF_NEIGHBOURS WHERE NOT ($valid)")
            db.execSQL("DELETE FROM $TABLE_SITE_RF_NEIGHBOUR_DAYS WHERE NOT EXISTS (SELECT 1 FROM $TABLE_SITE_RF_NEIGHBOURS r WHERE r.site_key=$TABLE_SITE_RF_NEIGHBOUR_DAYS.site_key AND r.fingerprint=$TABLE_SITE_RF_NEIGHBOUR_DAYS.fingerprint)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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
        createIncidentTable(db)
        createForensicTables(db)
        createTransitionTable(db)
        createStableSiteTables(db)
        createMobilityTables(db)
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
        if (oldVersion < 13) createIncidentTable(db)
        if (oldVersion < 14) createForensicTables(db)
        if (oldVersion < 15) createTransitionTable(db)
        if (oldVersion < 16) createStableSiteTables(db)
        if (oldVersion < 17) {
            try { db.execSQL("ALTER TABLE $TABLE_CELL_TRANSITIONS ADD COLUMN trip_count INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE_CELL_TRANSITIONS ADD COLUMN last_trip_id TEXT") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE_CELL_TRANSITIONS ADD COLUMN mobility_first_seen_ms INTEGER") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE_CELL_TRANSITIONS ADD COLUMN mobility_last_seen_ms INTEGER") } catch (_: Exception) {}
            createMobilityTables(db)
        }
        if (oldVersion < 18) createStableSiteRfNeighbourTables(db)
    }

    private fun createStableSiteTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_SITE_CELLS (site_key TEXT NOT NULL, cell_identity TEXT NOT NULL, role TEXT NOT NULL, first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, observations INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(site_key,cell_identity,role))")
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_SITE_DAYS (site_key TEXT NOT NULL, cell_identity TEXT NOT NULL, role TEXT NOT NULL, day TEXT NOT NULL, PRIMARY KEY(site_key,cell_identity,role,day))")
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_SITE_EVENTS (event_key TEXT NOT NULL, site_key TEXT NOT NULL, cell_identity TEXT NOT NULL, role TEXT NOT NULL, seen_ms INTEGER NOT NULL, PRIMARY KEY(event_key,cell_identity,role))")
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_SITE_MOTION_DAYS (site_key TEXT NOT NULL, day TEXT NOT NULL, state TEXT NOT NULL, accuracy_band_m INTEGER, duration_s INTEGER NOT NULL, displacement_band_m INTEGER, PRIMARY KEY(site_key,day,state))")
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_SITE_HOLDS (episode_id TEXT PRIMARY KEY NOT NULL, site_key TEXT NOT NULL, cell_identity TEXT NOT NULL, first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1, forensic_opened_ms INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_SITE_SHADOW_TRIGGERS (episode_id TEXT PRIMARY KEY NOT NULL, site_key TEXT, previous_identity TEXT, candidate_identity TEXT NOT NULL, first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, evaluated_ms INTEGER, closed_ms INTEGER, shadow_recorded INTEGER NOT NULL DEFAULT 0, reason TEXT, motion_state TEXT, feature_state TEXT, globally_known INTEGER, known_at_site INTEGER, seen_as_neighbour INTEGER, serving_days INTEGER, neighbour_days INTEGER, corroborated INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_site_events_seen ON $TABLE_SITE_EVENTS(seen_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_site_cells_seen ON $TABLE_SITE_CELLS(last_seen_ms)")
        createStableSiteRfNeighbourTables(db)
    }

    private fun createStableSiteRfNeighbourTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_SITE_RF_NEIGHBOURS (site_key TEXT NOT NULL, fingerprint TEXT NOT NULL, radio TEXT NOT NULL, arfcn INTEGER NOT NULL, pci INTEGER NOT NULL, mcc TEXT, mnc TEXT, tac TEXT, first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, observations INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(site_key,fingerprint))")
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_SITE_RF_NEIGHBOUR_DAYS (site_key TEXT NOT NULL, fingerprint TEXT NOT NULL, day TEXT NOT NULL, PRIMARY KEY(site_key,fingerprint,day))")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_site_rf_neighbours_seen ON $TABLE_SITE_RF_NEIGHBOURS(last_seen_ms)")
    }

    private fun createTransitionTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_CELL_TRANSITIONS (" +
                "from_identity TEXT NOT NULL, to_identity TEXT NOT NULL, " +
                "observations INTEGER NOT NULL DEFAULT 0, trusted_observations INTEGER NOT NULL DEFAULT 0, " +
                "last_status TEXT NOT NULL, last_seen_ms INTEGER NOT NULL, " +
                "trip_count INTEGER NOT NULL DEFAULT 0, last_trip_id TEXT, " +
                "mobility_first_seen_ms INTEGER, mobility_last_seen_ms INTEGER, " +
                "PRIMARY KEY(from_identity, to_identity))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_cell_transitions_seen ON " +
                "$TABLE_CELL_TRANSITIONS(last_seen_ms)"
        )
    }

    private fun createMobilityTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_MOBILITY_TRIPS (" +
                "trip_id TEXT PRIMARY KEY NOT NULL, started_at_ms INTEGER NOT NULL, " +
                "last_seen_ms INTEGER NOT NULL, has_moving INTEGER NOT NULL DEFAULT 0, " +
                "last_serving TEXT, same_serving_since_ms INTEGER NOT NULL, static_since_ms INTEGER, " +
                "state TEXT NOT NULL, close_reason TEXT, closed_at_ms INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_MOBILITY_TRIP_CELLS (" +
                "trip_id TEXT NOT NULL, cell_identity TEXT NOT NULL, " +
                "PRIMARY KEY(trip_id,cell_identity), FOREIGN KEY(trip_id) REFERENCES " +
                "$TABLE_MOBILITY_TRIPS(trip_id) ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_MOBILITY_TRIP_EDGES (" +
                "trip_id TEXT NOT NULL, from_identity TEXT NOT NULL, to_identity TEXT NOT NULL, " +
                "PRIMARY KEY(trip_id,from_identity,to_identity), FOREIGN KEY(trip_id) REFERENCES " +
                "$TABLE_MOBILITY_TRIPS(trip_id) ON DELETE CASCADE)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_one_open_mobility_trip ON $TABLE_MOBILITY_TRIPS(state) WHERE state='OPEN'")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mobility_trips_closed ON $TABLE_MOBILITY_TRIPS(state,closed_at_ms)")
    }

    /** Últimas posiciones GPS válidas donde este dispositivo observó la identidad indicada. */
    fun getCellLocationSamples(cell: CellData, limit: Int = 40): List<CellLocationSample> {
        if (cell.cellId == "N/A" || cell.radioTech == RadioTech.UNKNOWN) return emptyList()
        val cutoff = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(
            Date(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        )
        val out = mutableListOf<CellLocationSample>()
        if (!hasMatureTrustedBaseline(cell.cellId, cell.mnc, cell.tac, cell.mcc, cell.radioTech, cutoff)) {
            return emptyList()
        }
        readableDatabase.rawQuery(
            "SELECT $COLUMN_LAT,$COLUMN_LON FROM $TABLE_HISTORY " +
                "WHERE $COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_MCC=? " +
                "AND $COLUMN_RADIO=? AND $COLUMN_LAT IS NOT NULL AND $COLUMN_LON IS NOT NULL " +
                "AND $COLUMN_SCORE>=? AND ($COLUMN_FAILED_H IS NULL OR TRIM($COLUMN_FAILED_H)='' OR $COLUMN_FAILED_H='OK') " +
                "AND $COLUMN_TIMESTAMP>=? ORDER BY $COLUMN_ID DESC LIMIT ?",
            arrayOf(
                cell.cellId, cell.mnc, cell.tac, cell.mcc, cell.radioTech.name,
                TRUSTED_BASELINE_MIN_SCORE.toString(), cutoff, limit.toString()
            )
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val lat = cursor.getDouble(0)
                val lon = cursor.getDouble(1)
                if (lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) {
                    out += CellLocationSample(lat, lon)
                }
            }
        }
        return out
    }

    /**
     * Puerta de promoción de la cuarentena. No crea otra tabla ni modifica filas: el historial
     * completo sigue siendo auditable/exportable y solo cambia qué subconjunto aprende el motor.
     */
    private fun hasMatureTrustedBaseline(
        cellId: String,
        mnc: String,
        tac: String,
        mcc: String,
        radio: RadioTech,
        since: String
    ): Boolean = readableDatabase.rawQuery(
        "SELECT COUNT(*),COUNT(DISTINCT substr($COLUMN_TIMESTAMP,1,10)) FROM $TABLE_HISTORY " +
            "WHERE $COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_MCC=? " +
            "AND $COLUMN_RADIO=? AND $COLUMN_TIMESTAMP>=? AND $COLUMN_SCORE>=? " +
            "AND ($COLUMN_FAILED_H IS NULL OR TRIM($COLUMN_FAILED_H)='' OR $COLUMN_FAILED_H='OK')",
        arrayOf(
            cellId, mnc, tac, mcc, radio.name, since,
            TRUSTED_BASELINE_MIN_SCORE.toString()
        )
    ).use { cursor ->
        cursor.moveToFirst() && cursor.getInt(0) >= TRUSTED_BASELINE_MIN_SAMPLES &&
            cursor.getInt(1) >= TRUSTED_BASELINE_MIN_DAYS
    }

    /**
     * Igual que [getCellLocationSamples] pero para TODAS las celdas en una sola pasada, indexado
     * por identidad (`MCC-MNC-TAC-CID-RADIO`).
     *
     * Existe por una razón de coste, no de comodidad: la pestaña de geometría necesita el perfil
     * de varias decenas de celdas a la vez, y hacerlo con [getCellLocationSamples] serían tantas
     * consultas como celdas. Aquí se recorre el historial una vez, en orden descendente, y se
     * queda con las [perCell] muestras más recientes de cada identidad — mismo criterio, mismo
     * saneamiento de coordenadas y misma ventana de 30 días que la consulta por celda, para que
     * los números que se dibujan sean exactamente los que usa H16.
     *
     * Es de solo lectura y no toca ni el baseline ni la detección.
     */
    fun getAllCellLocationSamples(
        perCell: Int = 40,
        days: Int = 30
    ): Map<String, List<CellLocationSample>> {
        val cutoff = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(
            Date(System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000)
        )
        val out = LinkedHashMap<String, MutableList<CellLocationSample>>()
        readableDatabase.rawQuery(
            "SELECT $COLUMN_MCC,$COLUMN_MNC,$COLUMN_TAC,$COLUMN_CID,$COLUMN_RADIO," +
                "$COLUMN_LAT,$COLUMN_LON FROM $TABLE_HISTORY " +
                "WHERE $COLUMN_LAT IS NOT NULL AND $COLUMN_LON IS NOT NULL " +
                "AND $COLUMN_SCORE>=? " +
                "AND ($COLUMN_FAILED_H IS NULL OR TRIM($COLUMN_FAILED_H)='' OR $COLUMN_FAILED_H='OK') " +
                "AND $COLUMN_TIMESTAMP>=? ORDER BY $COLUMN_ID DESC",
            arrayOf(TRUSTED_BASELINE_MIN_SCORE.toString(), cutoff)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val cid = cursor.getString(3) ?: continue
                if (cid == "N/A") continue
                val radio = cursor.getString(4) ?: continue
                if (radio == RadioTech.UNKNOWN.name) continue
                val lat = cursor.getDouble(5)
                val lon = cursor.getDouble(6)
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                if (lat == 0.0 && lon == 0.0) continue
                val identity = "${cursor.getString(0)}-${cursor.getString(1)}-" +
                    "${cursor.getString(2)}-$cid-$radio"
                val bucket = out.getOrPut(identity) { mutableListOf() }
                if (bucket.size < perCell) bucket += CellLocationSample(lat, lon)
            }
        }
        return out
    }

    fun getTrustedTransitionCount(fromIdentity: String, toIdentity: String): Int =
        readableDatabase.rawQuery(
            "SELECT trusted_observations FROM $TABLE_CELL_TRANSITIONS " +
                "WHERE from_identity=? AND to_identity=?",
            arrayOf(fromIdentity, toIdentity)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    /** Vista agregada para el explorador técnico; no modifica el baseline ni la detección. */
    fun getCellTransitions(limit: Int = 250): List<CellTransitionSummary> {
        val out = mutableListOf<CellTransitionSummary>()
        readableDatabase.rawQuery(
            "SELECT from_identity,to_identity,observations,trusted_observations,last_status,last_seen_ms," +
                "trip_count,last_trip_id,mobility_first_seen_ms,mobility_last_seen_ms " +
                "FROM $TABLE_CELL_TRANSITIONS ORDER BY last_seen_ms DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 1_000).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += CellTransitionSummary(
                    fromIdentity = cursor.getString(0),
                    toIdentity = cursor.getString(1),
                    observations = cursor.getInt(2),
                    trustedObservations = cursor.getInt(3),
                    lastStatus = runCatching { HeuristicStatus.valueOf(cursor.getString(4)) }
                        .getOrDefault(HeuristicStatus.NOT_EVALUATED),
                    lastSeenMs = cursor.getLong(5),
                    tripCount = cursor.getInt(6), lastTripId = if (cursor.isNull(7)) null else cursor.getString(7),
                    mobilityFirstSeenMs = if (cursor.isNull(8)) null else cursor.getLong(8),
                    mobilityLastSeenMs = if (cursor.isNull(9)) null else cursor.getLong(9)
                )
            }
        }
        return out
    }

    /** One bounded, read-only snapshot for Geometry/export. It never mutates an open trip. */
    fun getMobilityGeometrySnapshot(limit: Int = 1_000): MobilityGeometrySnapshot {
        val transitions = getCellTransitions(limit)
        val trips = mutableListOf<MobilityTripSummary>()
        readableDatabase.rawQuery(
            "SELECT t.trip_id,t.started_at_ms,t.closed_at_ms,t.state,t.close_reason,t.has_moving," +
                "COUNT(DISTINCT c.cell_identity),COUNT(DISTINCT e.from_identity||char(0)||e.to_identity),t.last_serving " +
                "FROM $TABLE_MOBILITY_TRIPS t LEFT JOIN $TABLE_MOBILITY_TRIP_CELLS c ON c.trip_id=t.trip_id " +
                "LEFT JOIN $TABLE_MOBILITY_TRIP_EDGES e ON e.trip_id=t.trip_id GROUP BY t.trip_id " +
                "ORDER BY t.started_at_ms DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 5_000).toString())
        ).use { c -> while (c.moveToNext()) trips += MobilityTripSummary(
            c.getString(0), c.getLong(1), if(c.isNull(2))null else c.getLong(2), c.getString(3),
            if(c.isNull(4))null else c.getString(4), c.getInt(5)!=0, c.getInt(6), c.getInt(7),
            if(c.isNull(8))null else c.getString(8)
        ) }
        val openId = trips.firstOrNull { it.state == "OPEN" }?.tripId
        val pending = if (openId == null) emptySet() else tripEdges(openId)
        return MobilityGeometryProjection.build(transitions, trips, pending)
    }

    /** PASSED incrementa el baseline fiable; FAILED/N/A solo quedan auditados como observación. */
    fun recordCellTransition(result: TransitionCoherenceResult) {
        val from = result.fromIdentity ?: return
        val to = result.toIdentity ?: return
        val trustedIncrement = if (result.status == HeuristicStatus.PASSED && result.eligibleForLearning) 1 else 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            val changed = db.update(
                TABLE_CELL_TRANSITIONS,
                ContentValues().apply {
                    // Los contadores se actualizan abajo mediante SQL para que el incremento sea
                    // atómico. Aquí solo se refrescan los metadatos del último handover.
                    put("last_status", result.status.name)
                    put("last_seen_ms", System.currentTimeMillis())
                },
                "from_identity=? AND to_identity=?", arrayOf(from, to)
            )
            if (changed > 0) {
                db.execSQL(
                    "UPDATE $TABLE_CELL_TRANSITIONS SET observations=observations+1, " +
                        "trusted_observations=trusted_observations+? " +
                        "WHERE from_identity=? AND to_identity=?",
                    arrayOf<Any>(trustedIncrement, from, to)
                )
            } else {
                db.insertOrThrow(TABLE_CELL_TRANSITIONS, null, ContentValues().apply {
                    put("from_identity", from); put("to_identity", to)
                    put("observations", 1); put("trusted_observations", trustedIncrement)
                    put("last_status", result.status.name); put("last_seen_ms", System.currentTimeMillis())
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun openTrip(): MobilityTrip? = readableDatabase.rawQuery(
        "SELECT trip_id,started_at_ms,last_seen_ms,has_moving,last_serving," +
            "same_serving_since_ms,static_since_ms FROM $TABLE_MOBILITY_TRIPS " +
            "WHERE state='OPEN' LIMIT 1", null
    ).use { c ->
        if (!c.moveToFirst()) null else MobilityTrip(
            id = c.getString(0), startedAtMs = c.getLong(1), lastSeenMs = c.getLong(2),
            hasMoving = c.getInt(3) != 0, lastServing = c.getString(4),
            sameServingSinceMs = c.getLong(5), staticSinceMs = if (c.isNull(6)) null else c.getLong(6)
        )
    }

    override fun createTrip(trip: MobilityTrip, firstCell: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow(TABLE_MOBILITY_TRIPS, null, ContentValues().apply {
                put("trip_id", trip.id); put("started_at_ms", trip.startedAtMs); put("last_seen_ms", trip.lastSeenMs)
                put("has_moving", if (trip.hasMoving) 1 else 0); put("last_serving", trip.lastServing)
                put("same_serving_since_ms", trip.sameServingSinceMs); putNull("static_since_ms"); put("state", "OPEN")
            })
            db.insertOrThrow(TABLE_MOBILITY_TRIP_CELLS, null, ContentValues().apply {
                put("trip_id", trip.id); put("cell_identity", firstCell)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun updateTrip(trip: MobilityTrip) {
        writableDatabase.update(TABLE_MOBILITY_TRIPS, ContentValues().apply {
            put("last_seen_ms", trip.lastSeenMs); put("has_moving", if (trip.hasMoving) 1 else 0)
            put("last_serving", trip.lastServing); put("same_serving_since_ms", trip.sameServingSinceMs)
            if (trip.staticSinceMs == null) putNull("static_since_ms") else put("static_since_ms", trip.staticSinceMs)
        }, "trip_id=? AND state='OPEN'", arrayOf(trip.id))
    }

    override fun addTripCell(tripId: String, identity: String) {
        writableDatabase.insertWithOnConflict(TABLE_MOBILITY_TRIP_CELLS, null, ContentValues().apply {
            put("trip_id", tripId); put("cell_identity", identity)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun addTripEdge(tripId: String, edge: MobilityEdge) {
        writableDatabase.insertWithOnConflict(TABLE_MOBILITY_TRIP_EDGES, null, ContentValues().apply {
            put("trip_id", tripId); put("from_identity", edge.from); put("to_identity", edge.to)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun tripCells(tripId: String): Set<String> = buildSet {
        readableDatabase.rawQuery(
            "SELECT cell_identity FROM $TABLE_MOBILITY_TRIP_CELLS WHERE trip_id=?", arrayOf(tripId)
        ).use { c -> while (c.moveToNext()) add(c.getString(0)) }
    }

    override fun tripEdges(tripId: String): Set<MobilityEdge> = buildSet {
        readableDatabase.rawQuery(
            "SELECT from_identity,to_identity FROM $TABLE_MOBILITY_TRIP_EDGES WHERE trip_id=?", arrayOf(tripId)
        ).use { c -> while (c.moveToNext()) add(MobilityEdge(c.getString(0), c.getString(1))) }
    }

    override fun priorTripCounts(edges: Set<MobilityEdge>): Map<MobilityEdge, Int> = buildMap {
        edges.forEach { edge ->
            val count = readableDatabase.rawQuery(
                "SELECT trip_count FROM $TABLE_CELL_TRANSITIONS WHERE from_identity=? AND to_identity=?",
                arrayOf(edge.from, edge.to)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            put(edge, count)
        }
    }

    override fun commitMobilityTrip(tripId: String, reason: MobilityTripCloseReason, closedAtMs: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val isOpen = db.rawQuery(
                "SELECT 1 FROM $TABLE_MOBILITY_TRIPS WHERE trip_id=? AND state='OPEN'", arrayOf(tripId)
            ).use { it.moveToFirst() }
            if (!isOpen) { db.setTransactionSuccessful(); return }
            tripEdges(tripId).forEach { edge ->
                db.insertWithOnConflict(TABLE_CELL_TRANSITIONS, null, ContentValues().apply {
                    put("from_identity", edge.from); put("to_identity", edge.to)
                    put("observations", 0); put("trusted_observations", 0)
                    put("last_status", HeuristicStatus.NOT_EVALUATED.name); put("last_seen_ms", closedAtMs)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                db.execSQL(
                    "UPDATE $TABLE_CELL_TRANSITIONS SET trip_count=trip_count+1,last_trip_id=?," +
                        "mobility_first_seen_ms=COALESCE(mobility_first_seen_ms,?),mobility_last_seen_ms=? " +
                        "WHERE from_identity=? AND to_identity=? AND (last_trip_id IS NULL OR last_trip_id<>?)",
                    arrayOf<Any>(tripId, closedAtMs, closedAtMs, edge.from, edge.to, tripId)
                )
            }
            closeTripRow(db, tripId, reason, closedAtMs)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun discardMobilityTrip(tripId: String, reason: MobilityTripCloseReason, closedAtMs: Long) {
        closeTripRow(writableDatabase, tripId, reason, closedAtMs)
    }

    private fun closeTripRow(db: SQLiteDatabase, tripId: String, reason: MobilityTripCloseReason, closedAtMs: Long) {
        db.update(TABLE_MOBILITY_TRIPS, ContentValues().apply {
            put("state", "CLOSED"); put("close_reason", reason.name); put("closed_at_ms", closedAtMs)
        }, "trip_id=? AND state='OPEN'", arrayOf(tripId))
    }

    override fun pruneMobilityTripDetails(beforeMs: Long) {
        writableDatabase.delete(
            TABLE_MOBILITY_TRIPS, "state='CLOSED' AND closed_at_ms IS NOT NULL AND closed_at_ms<?",
            arrayOf(beforeMs.toString())
        )
    }

    /** Stable-Site evidence. Full identities and local RF fingerprints are persisted separately. */
    fun recordStableSiteContext(
        eventKey: String,
        siteKey: String,
        serving: CellData,
        neighbours: List<CellData>,
        motion: MotionEvidence,
        wallMs: Long = System.currentTimeMillis()
    ) {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(wallMs))
        val observations = buildList { add(serving.identityKey to "SERVING"); neighbours.forEach { if (it.cellId != "N/A") add(it.identityKey to "NEIGHBOUR") } }.distinct()
        val rfNeighbours = neighbours.mapNotNull(StableSiteNeighbourEvidence::rfFingerprint)
            .distinctBy { it.value }
        val db = writableDatabase
        db.beginTransaction()
        try {
            observations.forEach { (identity, role) ->
                val inserted = db.insertWithOnConflict(TABLE_SITE_EVENTS, null, ContentValues().apply {
                    put("event_key", eventKey); put("site_key", siteKey); put("cell_identity", identity); put("role", role); put("seen_ms", wallMs)
                }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
                if (inserted) {
                    val updated = db.update(TABLE_SITE_CELLS, ContentValues().apply { put("last_seen_ms", wallMs) }, "site_key=? AND cell_identity=? AND role=?", arrayOf(siteKey, identity, role))
                    if (updated == 0) db.insertOrThrow(TABLE_SITE_CELLS, null, ContentValues().apply {
                        put("site_key", siteKey); put("cell_identity", identity); put("role", role); put("first_seen_ms", wallMs); put("last_seen_ms", wallMs); put("observations", 0)
                    })
                    db.execSQL("UPDATE $TABLE_SITE_CELLS SET observations=observations+1,last_seen_ms=MAX(last_seen_ms,?) WHERE site_key=? AND cell_identity=? AND role=?", arrayOf<Any>(wallMs, siteKey, identity, role))
                    db.insertWithOnConflict(TABLE_SITE_DAYS, null, ContentValues().apply { put("site_key", siteKey); put("cell_identity", identity); put("role", role); put("day", day) }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            rfNeighbours.forEach { rf ->
                val eventIdentity = "RF:${rf.value}"
                val inserted = db.insertWithOnConflict(TABLE_SITE_EVENTS, null, ContentValues().apply {
                    put("event_key", eventKey); put("site_key", siteKey); put("cell_identity", eventIdentity); put("role", "RF_NEIGHBOUR"); put("seen_ms", wallMs)
                }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
                if (inserted) {
                    val source = neighbours.first { StableSiteNeighbourEvidence.rfFingerprint(it)?.value == rf.value }
                    val updated = db.update(TABLE_SITE_RF_NEIGHBOURS, ContentValues().apply { put("last_seen_ms", wallMs) }, "site_key=? AND fingerprint=?", arrayOf(siteKey, rf.value))
                    if (updated == 0) db.insertOrThrow(TABLE_SITE_RF_NEIGHBOURS, null, ContentValues().apply {
                        put("site_key", siteKey); put("fingerprint", rf.value); put("radio", rf.radio.name); put("arfcn", rf.arfcn); put("pci", rf.pci)
                        put("mcc", source.mcc.takeUnless { it == "N/A" }); put("mnc", source.mnc.takeUnless { it == "N/A" }); put("tac", source.tac.takeUnless { it == "N/A" })
                        put("first_seen_ms", wallMs); put("last_seen_ms", wallMs); put("observations", 0)
                    })
                    db.execSQL("UPDATE $TABLE_SITE_RF_NEIGHBOURS SET observations=observations+1,last_seen_ms=MAX(last_seen_ms,?) WHERE site_key=? AND fingerprint=?", arrayOf<Any>(wallMs, siteKey, rf.value))
                    db.insertWithOnConflict(TABLE_SITE_RF_NEIGHBOUR_DAYS, null, ContentValues().apply { put("site_key", siteKey); put("fingerprint", rf.value); put("day", day) }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            if (motion.state != MotionState.UNKNOWN) db.insertWithOnConflict(TABLE_SITE_MOTION_DAYS, null, ContentValues().apply {
                put("site_key", siteKey); put("day", day); put("state", motion.state.name)
                motion.accuracyM?.let { put("accuracy_band_m", (it / 10).toInt() * 10) }
                put("duration_s", motion.durationSeconds)
                motion.displacementM?.let { put("displacement_band_m", (it / 10).toInt() * 10) }
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Read-only candidate evaluation. Safe to call for every overlapping grid. */
    fun evaluateStableSiteCandidate(siteKey: String?, current: CellData, previousIdentity: String?, motion: MotionEvidence): StableSiteDecision {
        if (siteKey == null) return StableSiteDecision(reason = "NO_RELIABLE_LOCATION")
        val db = readableDatabase
        fun scalar(sql: String, args: Array<String>): Int = db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        fun roleDays(identity: String, role: String) = scalar("SELECT COUNT(*) FROM $TABLE_SITE_DAYS WHERE site_key=? AND cell_identity=? AND role=?", arrayOf(siteKey, identity, role))
        val servingDays = scalar("SELECT COUNT(DISTINCT day) FROM $TABLE_SITE_DAYS WHERE site_key=? AND role='SERVING'", arrayOf(siteKey))
        val staticDays = scalar("SELECT COUNT(*) FROM $TABLE_SITE_MOTION_DAYS WHERE site_key=? AND state='STATIC_CONFIRMED'", arrayOf(siteKey))
        val neighbourDays = scalar("SELECT COUNT(DISTINCT day) FROM $TABLE_SITE_DAYS WHERE site_key=? AND role='NEIGHBOUR'", arrayOf(siteKey))
        val rfNeighbourDays = scalar("SELECT COUNT(DISTINCT day) FROM $TABLE_SITE_RF_NEIGHBOUR_DAYS WHERE site_key=?", arrayOf(siteKey))
        val servingObs = scalar("SELECT COALESCE(SUM(observations),0) FROM $TABLE_SITE_CELLS WHERE site_key=? AND role='SERVING'", arrayOf(siteKey))
        val maturity = StableSiteMaturityPolicy.evaluate(servingDays, staticDays, neighbourDays, rfNeighbourDays, servingObs)
        val state = maturity.state
        val currentServingDays = roleDays(current.identityKey, "SERVING")
        val currentNeighbourDays = roleDays(current.identityKey, "NEIGHBOUR")
        val globallyKnown = scalar("SELECT COUNT(*) FROM $TABLE_HISTORY WHERE $COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_MCC=? AND $COLUMN_RADIO=? LIMIT 1", arrayOf(current.cellId,current.mnc,current.tac,current.mcc,current.radioTech.name)) > 0
        // After a process/device restart the in-memory previous identity is absent. Recover the
        // most recently observed established serving identity at this site, excluding the current
        // one. This preserves the startup novelty signal without treating a fresh site as mature.
        val inMemoryEstablished = previousIdentity?.takeIf { it != current.identityKey && roleDays(it, "SERVING") >= 3 }
        val effectivePreviousIdentity = inMemoryEstablished ?: db.rawQuery(
            "SELECT cell_identity FROM $TABLE_SITE_DAYS WHERE site_key=? AND role='SERVING' AND cell_identity<>? " +
                "GROUP BY cell_identity HAVING COUNT(*)>=3 ORDER BY MAX(day) DESC LIMIT 1",
            arrayOf(siteKey, current.identityKey)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val previousDays = effectivePreviousIdentity?.let { roleDays(it, "SERVING") } ?: 0
        val evidence = StableSiteEvidence(siteKey, state, servingDays, staticDays, maxOf(neighbourDays, rfNeighbourDays), currentServingDays, currentNeighbourDays, globallyKnown, previousDays, motion, servingObs, effectivePreviousIdentity, neighbourDays, rfNeighbourDays, maturity.capability, maturity.reason)
        // v2.9.0 initially measures wouldTrigger in shadow mode. Activation is deliberately
        // deferred until field telemetry demonstrates an acceptable false-positive rate.
        return StableSiteEvaluator.evaluate(evidence)
    }

    /** Tracks one logical serving episode independently of GPS/motion availability. */
    fun observeStableSiteEpisode(currentIdentity: String, previousIdentity: String?, now: Long = System.currentTimeMillis()): String? {
        val db = writableDatabase
        db.rawQuery("SELECT episode_id FROM $TABLE_SITE_SHADOW_TRIGGERS WHERE candidate_identity=? AND closed_ms IS NULL LIMIT 1", arrayOf(currentIdentity)).use {
            if (it.moveToFirst()) {
                val id=it.getString(0); db.update(TABLE_SITE_SHADOW_TRIGGERS,ContentValues().apply{put("last_seen_ms",now)},"episode_id=?",arrayOf(id)); return id
            }
        }
        db.update(TABLE_SITE_SHADOW_TRIGGERS,ContentValues().apply{put("closed_ms",now);put("last_seen_ms",now)},"closed_ms IS NULL",null)
        if (previousIdentity == null || previousIdentity == currentIdentity) return null
        val id=java.util.UUID.randomUUID().toString()
        db.insertOrThrow(TABLE_SITE_SHADOW_TRIGGERS,null,ContentValues().apply {
            put("episode_id",id);put("previous_identity",previousIdentity);put("candidate_identity",currentIdentity)
            put("first_seen_ms",now);put("last_seen_ms",now);put("shadow_recorded",0);put("corroborated",0)
        })
        return id
    }

    /** Applies persistence/hold side effects once, after the service selects one candidate. */
    fun applyStableSiteDecision(
        candidate: StableSiteDecision,
        current: CellData,
        candidateSiteKeys: List<String> = listOfNotNull(candidate.siteKey),
        enforcementEnabled: Boolean = STABLE_SITE_ENFORCEMENT_ENABLED
    ): StableSiteDecision {
        val siteKey = candidate.siteKey ?: return candidate
        var decision = candidate
        val currentServingDays = candidate.evidence?.currentServingDays ?: 0
        val currentNeighbourDays = candidate.evidence?.currentNeighbourDays ?: 0
        var episodeId = openStableSiteEpisodeId(current.identityKey)
        if (episodeId == null && decision.wouldTrigger) {
            episodeId = observeStableSiteEpisode(current.identityKey, candidate.evidence?.previousIdentity)
        }
        episodeId?.let { updateStableSiteEpisode(it, decision, current.identityKey) }
        val existingHold = findActiveStableSiteHold(current.identityKey, candidateSiteKeys)
        val corroborated = currentServingDays >= 3 || currentNeighbourDays >= 2
        if (corroborated && existingHold != null) releaseStableSiteHold(existingHold)
        // Shadow releases/ignores holds produced by development builds; no learning is frozen.
        if (!enforcementEnabled && existingHold != null) releaseStableSiteHold(existingHold)
        val mature = decision.featureState == StableSiteFeatureState.ACTIVE
        if ((enforcementEnabled && decision.wouldTrigger && mature) || (enforcementEnabled && existingHold != null && !corroborated)) {
            val holdEpisode = episodeId ?: existingHold ?: return decision
            activateStableSiteHold(holdEpisode, siteKey, current.identityKey)
            decision = decision.copy(enforced = true, wouldTrigger = true, reason = "STABLE_SITE_NOVELTY_HOLD")
        }
        return decision
    }

    private fun openStableSiteEpisodeId(identity:String) = readableDatabase.rawQuery("SELECT episode_id FROM $TABLE_SITE_SHADOW_TRIGGERS WHERE candidate_identity=? AND closed_ms IS NULL LIMIT 1",arrayOf(identity)).use{if(it.moveToFirst())it.getString(0) else null}
    private fun updateStableSiteEpisode(id:String, decision:StableSiteDecision, identity:String) {
        val e=decision.evidence
        val now=System.currentTimeMillis()
        writableDatabase.update(TABLE_SITE_SHADOW_TRIGGERS,ContentValues().apply{
            put("site_key",decision.siteKey)
            put("last_seen_ms",now)
            put("evaluated_ms",now)
            if(decision.wouldTrigger) put("shadow_recorded",1)
            put("reason",decision.reason)
            put("motion_state",e?.motion?.state?.name);put("feature_state",decision.featureState.name)
            put("globally_known",if(e?.currentSeenGlobally==true)1 else 0);put("known_at_site",if((e?.currentServingDays?:0)>=3)1 else 0)
            put("seen_as_neighbour",if((e?.currentNeighbourDays?:0)>0)1 else 0);put("serving_days",e?.currentServingDays?:0);put("neighbour_days",e?.currentNeighbourDays?:0)
            put("corroborated",if((e?.currentServingDays?:0)>=3||(e?.currentNeighbourDays?:0)>=2)1 else 0)
        },"episode_id=? AND candidate_identity=?",arrayOf(id,identity))
    }
    private fun findActiveStableSiteHold(identity:String, candidateSites:List<String>):String? {
        val placeholders=candidateSites.joinToString(","){"?"}
        val where=if(candidateSites.isEmpty())"cell_identity=? AND active=1" else "cell_identity=? AND active=1 AND (site_key IN ($placeholders) OR episode_id IN (SELECT episode_id FROM $TABLE_SITE_SHADOW_TRIGGERS WHERE candidate_identity=? AND closed_ms IS NULL))"
        val args=if(candidateSites.isEmpty()) arrayOf(identity) else (listOf(identity)+candidateSites+identity).toTypedArray()
        return readableDatabase.rawQuery("SELECT episode_id FROM $TABLE_SITE_HOLDS WHERE $where LIMIT 1",args).use{if(it.moveToFirst())it.getString(0) else null}
    }
    private fun activateStableSiteHold(episodeId:String, site: String, identity: String) {
        val now=System.currentTimeMillis(); val db=writableDatabase
        if (db.update(TABLE_SITE_HOLDS, ContentValues().apply { put("site_key",site);put("last_seen_ms",now);put("active",1) }, "episode_id=?", arrayOf(episodeId))==0)
            db.insert(TABLE_SITE_HOLDS,null,ContentValues().apply{put("episode_id",episodeId);put("site_key",site);put("cell_identity",identity);put("first_seen_ms",now);put("last_seen_ms",now);put("active",1)})
    }
    private fun releaseStableSiteHold(episodeId:String) { writableDatabase.update(TABLE_SITE_HOLDS,ContentValues().apply{put("active",0)},"episode_id=?",arrayOf(episodeId)) }

    fun resetStableSiteLearning() { val db=writableDatabase; db.beginTransaction(); try { listOf(TABLE_SITE_EVENTS,TABLE_SITE_DAYS,TABLE_SITE_CELLS,TABLE_SITE_RF_NEIGHBOURS,TABLE_SITE_RF_NEIGHBOUR_DAYS,TABLE_SITE_MOTION_DAYS,TABLE_SITE_HOLDS,TABLE_SITE_SHADOW_TRIGGERS).forEach { db.delete(it,null,null) }; db.setTransactionSuccessful() } finally { db.endTransaction() } }

    /** Privacy-reduced field export: hashed sites, aggregate cells, motion bands and episodes. */
    fun getStableSiteExportFiles(): LinkedHashMap<String,String> {
        fun csv(v:Any?):String { val s=v?.toString().orEmpty(); return if(s.any{it==','||it=='"'||it=='\n'||it=='\r'}) "\"${s.replace("\"","\"\"")}\"" else s }
        fun query(name:String, header:String, sql:String):Pair<String,String> = name to buildString {
            appendLine(header)
            readableDatabase.rawQuery(sql,null).use { c -> while(c.moveToNext()) appendLine((0 until c.columnCount).joinToString(","){csv(if(c.isNull(it)) null else c.getString(it))}) }
        }
        val files=linkedMapOf<String,String>()
        val siteStats="SELECT s.site_key AS site_key,SUM(CASE WHEN s.role='SERVING' THEN s.observations ELSE 0 END) AS serving_observations,"+
                "(SELECT COUNT(DISTINCT d.day) FROM $TABLE_SITE_DAYS d WHERE d.site_key=s.site_key AND d.role='SERVING') AS serving_days,"+
                "(SELECT COUNT(DISTINCT m.day) FROM $TABLE_SITE_MOTION_DAYS m WHERE m.site_key=s.site_key AND m.state='STATIC_CONFIRMED') AS static_days,"+
                "(SELECT COUNT(DISTINCT d.day) FROM $TABLE_SITE_DAYS d WHERE d.site_key=s.site_key AND d.role='NEIGHBOUR') AS full_neighbour_days,"+
                "(SELECT COALESCE(SUM(c.observations),0) FROM $TABLE_SITE_CELLS c WHERE c.site_key=s.site_key AND c.role='NEIGHBOUR') AS full_neighbour_observations,"+
                "(SELECT COUNT(DISTINCT r.day) FROM $TABLE_SITE_RF_NEIGHBOUR_DAYS r WHERE r.site_key=s.site_key) AS rf_neighbour_days,"+
                "(SELECT COALESCE(SUM(r.observations),0) FROM $TABLE_SITE_RF_NEIGHBOURS r WHERE r.site_key=s.site_key) AS rf_neighbour_observations,"+
                "MIN(s.first_seen_ms) AS first_seen,MAX(s.last_seen_ms) AS last_seen FROM $TABLE_SITE_CELLS s GROUP BY s.site_key"
        // v2.10.1 — Una sola fuente de verdad: la madurez que sale en el export la decide la misma
        // StableSiteMaturityPolicy que usa la app en tiempo real. Antes había una copia de la regla
        // en SQL; si una cambiaba y la otra no, el export habría descrito decisiones que la app no toma.
        files["sites.csv"] = buildString {
            appendLine("site_key,feature_state,neighbour_capability,maturity_reason,serving_observations,serving_distinct_days,static_distinct_days,full_neighbour_observations,full_neighbour_distinct_days,rf_neighbour_observations,rf_neighbour_distinct_days,first_seen_ms,last_seen_ms")
            readableDatabase.rawQuery("SELECT site_key,serving_observations,serving_days,static_days,full_neighbour_observations,full_neighbour_days,rf_neighbour_observations,rf_neighbour_days,first_seen,last_seen FROM ($siteStats)",null).use { c ->
                while (c.moveToNext()) {
                    val maturity = StableSiteMaturityPolicy.evaluate(
                        servingDays = c.getInt(2), staticDays = c.getInt(3),
                        fullNeighbourDays = c.getInt(5), rfNeighbourDays = c.getInt(7),
                        servingObservations = c.getInt(1)
                    )
                    appendLine(listOf(
                        c.getString(0), maturity.state.name, maturity.capability.name, maturity.reason,
                        c.getLong(1), c.getInt(2), c.getInt(3), c.getLong(4), c.getInt(5), c.getLong(6), c.getInt(7),
                        if (c.isNull(8)) null else c.getLong(8), if (c.isNull(9)) null else c.getLong(9)
                    ).joinToString(",") { csv(it) })
                }
            }
        }
        files += query("site_cells.csv","site_key,cell_identity,serving_observations,serving_distinct_days,neighbour_observations,neighbour_distinct_days,first_serving_ms,last_serving_ms,first_neighbour_ms,last_neighbour_ms",
            "SELECT s.site_key,s.cell_identity,SUM(CASE WHEN s.role='SERVING' THEN s.observations ELSE 0 END),"+
                "(SELECT COUNT(*) FROM $TABLE_SITE_DAYS d WHERE d.site_key=s.site_key AND d.cell_identity=s.cell_identity AND d.role='SERVING'),"+
                "SUM(CASE WHEN s.role='NEIGHBOUR' THEN s.observations ELSE 0 END),(SELECT COUNT(*) FROM $TABLE_SITE_DAYS d WHERE d.site_key=s.site_key AND d.cell_identity=s.cell_identity AND d.role='NEIGHBOUR'),"+
                "MIN(CASE WHEN s.role='SERVING' THEN s.first_seen_ms END),MAX(CASE WHEN s.role='SERVING' THEN s.last_seen_ms END),MIN(CASE WHEN s.role='NEIGHBOUR' THEN s.first_seen_ms END),MAX(CASE WHEN s.role='NEIGHBOUR' THEN s.last_seen_ms END) FROM $TABLE_SITE_CELLS s GROUP BY s.site_key,s.cell_identity")
        files += query("site_rf_neighbours.csv","site_key,rf_fingerprint,evidence_type,radio,arfcn,pci,mcc_metadata,mnc_metadata,tac_metadata,observations,distinct_days,first_seen_ms,last_seen_ms",
            "SELECT r.site_key,r.fingerprint,'RF_CONTEXT',r.radio,r.arfcn,r.pci,r.mcc,r.mnc,r.tac,r.observations,(SELECT COUNT(*) FROM $TABLE_SITE_RF_NEIGHBOUR_DAYS d WHERE d.site_key=r.site_key AND d.fingerprint=r.fingerprint),r.first_seen_ms,r.last_seen_ms FROM $TABLE_SITE_RF_NEIGHBOURS r ORDER BY r.site_key,r.fingerprint")
        files += query("motion.csv","site_key,day,state,accuracy_band_m,duration_s,displacement_band_m","SELECT site_key,day,state,accuracy_band_m,duration_s,displacement_band_m FROM $TABLE_SITE_MOTION_DAYS ORDER BY day,site_key")
        files += query("shadow_episodes.csv","episode_id,site_key,previous_identity,candidate_identity,first_seen_ms,last_seen_ms,duration_ms,evaluated_ms,closed_ms,shadow_recorded,reason,motion_state,feature_state,globally_known,known_at_site,seen_as_neighbour,serving_days,neighbour_days,corroborated",
            "SELECT episode_id,site_key,previous_identity,candidate_identity,first_seen_ms,last_seen_ms,(last_seen_ms-first_seen_ms),evaluated_ms,closed_ms,shadow_recorded,reason,motion_state,feature_state,globally_known,known_at_site,seen_as_neighbour,serving_days,neighbour_days,corroborated FROM $TABLE_SITE_SHADOW_TRIGGERS ORDER BY first_seen_ms")
        return files
    }

    private fun createForensicTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_FORENSIC_CASES (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, case_code TEXT NOT NULL, " +
                "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, closed_at TEXT, " +
                "state TEXT NOT NULL, cell_identity TEXT NOT NULL, highest_phase INTEGER NOT NULL, " +
                "confirmed INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_FORENSIC_SAMPLES (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, case_id INTEGER NOT NULL, " +
                "wall_time_ms INTEGER NOT NULL, elapsed_time_ms INTEGER NOT NULL, event TEXT NOT NULL, " +
                "payload_json TEXT NOT NULL, FOREIGN KEY(case_id) REFERENCES $TABLE_FORENSIC_CASES(id) ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_forensic_samples_case ON $TABLE_FORENSIC_SAMPLES (case_id, id)")
    }

    override fun createForensicCase(cell: CellData, origin: ForensicCaseOrigin): Long {
        val db = writableDatabase
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val provisional = ContentValues().apply {
            put("case_code", "PENDING")
            put("created_at", now); put("updated_at", now)
            put("state", ForensicCaseState.CAPTURING.name); put("cell_identity", cell.identityKey)
            put("highest_phase", cell.temporalProgress.phase); put("confirmed", 0)
        }
        val id = db.insertOrThrow(TABLE_FORENSIC_CASES, null, provisional)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        db.update(TABLE_FORENSIC_CASES, ContentValues().apply {
            val prefix = if (origin == ForensicCaseOrigin.TRUST_CONTRADICTION) "ICD-OBS" else "ICD"
            put("case_code", "$prefix-$date-${id.toString().padStart(4, '0')}")
        }, "id=?", arrayOf(id.toString()))
        return id
    }

    /**
     * v2.8.0 — Devuelve el rowId, o -1 si la escritura falló. `insert()` captura el disco lleno,
     * el bloqueo y la violación de clave ajena y devuelve -1 sin lanzar: descartar ese valor era
     * perder el único aviso de que la captura forense no está guardando nada.
     */
    override fun insertForensicSample(caseId: Long, wall: Long, elapsed: Long, event: String, json: String): Long {
        return try {
            writableDatabase.insert(TABLE_FORENSIC_SAMPLES, null, ContentValues().apply {
                put("case_id", caseId); put("wall_time_ms", wall); put("elapsed_time_ms", elapsed)
                put("event", event); put("payload_json", json)
            })
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * v2.8.0 — ¿Hay ya un caso forense de esta identidad creado a partir de [sinceWallMs]?
     *
     * Deduplicación persistente de las capturas abiertas al arrancar con la celda ya contradicha.
     * Compara sobre `created_at`, que se guarda como texto `yyyy-MM-dd HH:mm:ss`: con formato fijo
     * y ancho fijo, el orden lexicográfico y el cronológico coinciden.
     *
     * Cuenta cualquier origen a propósito, pero exige al menos una muestra real. Un caso cuya
     * creación tuvo éxito justo antes de quedarse el disco sin espacio no constituye evidencia y
     * no puede bloquear una nueva captura durante 24 horas.
     */
    override fun hasRecentForensicCaseFor(identity: String, sinceWallMs: Long): Boolean {
        return try {
            val threshold = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(sinceWallMs))
            readableDatabase.rawQuery(
                "SELECT 1 FROM $TABLE_FORENSIC_CASES c " +
                    "WHERE c.cell_identity=? AND c.created_at>=? " +
                    "AND EXISTS (SELECT 1 FROM $TABLE_FORENSIC_SAMPLES s WHERE s.case_id=c.id) " +
                    "LIMIT 1",
                arrayOf(identity, threshold)
            ).use { it.moveToFirst() }
        } catch (_: Exception) {
            // Ante la duda, no bloquear la captura: perder una muestra es peor que duplicar un caso.
            false
        }
    }

    override fun updateForensicCaseProgress(caseId: Long, cell: CellData) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        writableDatabase.execSQL(
            "UPDATE $TABLE_FORENSIC_CASES SET updated_at=?, highest_phase=MAX(highest_phase, ?), " +
                "confirmed=MAX(confirmed, ?) WHERE id=?",
            arrayOf(now, cell.temporalProgress.phase, if (cell.temporalProgress.confirmed) 1 else 0, caseId)
        )
    }

    override fun setForensicCaseState(caseId: Long, state: ForensicCaseState) {
        writableDatabase.update(TABLE_FORENSIC_CASES, ContentValues().apply { put("state", state.name) }, "id=?", arrayOf(caseId.toString()))
    }

    override fun finishForensicCase(caseId: Long, state: ForensicCaseState) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        writableDatabase.update(TABLE_FORENSIC_CASES, ContentValues().apply {
            put("state", state.name); put("updated_at", now); put("closed_at", now)
        }, "id=?", arrayOf(caseId.toString()))
    }

    override fun promoteForensicCase(caseId: Long) {
        writableDatabase.execSQL(
            "UPDATE $TABLE_FORENSIC_CASES SET case_code=REPLACE(case_code, 'ICD-OBS-', 'ICD-') WHERE id=?",
            arrayOf(caseId)
        )
    }

    fun interruptOpenForensicCases() {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        writableDatabase.update(TABLE_FORENSIC_CASES, ContentValues().apply {
            put("state", ForensicCaseState.INTERRUPTED.name); put("updated_at", now); put("closed_at", now)
        }, "state IN (?,?)", arrayOf(ForensicCaseState.CAPTURING.name, ForensicCaseState.POST_CAPTURE.name))
    }

    fun getForensicCases(): List<ForensicCase> {
        val out = mutableListOf<ForensicCase>()
        readableDatabase.rawQuery(
            "SELECT c.*, (SELECT COUNT(*) FROM $TABLE_FORENSIC_SAMPLES s WHERE s.case_id=c.id) sample_count " +
                "FROM $TABLE_FORENSIC_CASES c ORDER BY c.id DESC", null
        ).use { c -> while (c.moveToNext()) {
            fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n))
            val closed = c.getColumnIndexOrThrow("closed_at")
            out += ForensicCase(
                c.getLong(c.getColumnIndexOrThrow("id")), s("case_code"), s("created_at"), s("updated_at"),
                if (c.isNull(closed)) null else c.getString(closed),
                runCatching { ForensicCaseState.valueOf(s("state")) }.getOrDefault(ForensicCaseState.INTERRUPTED),
                if (s("case_code").startsWith("ICD-OBS-")) ForensicCaseOrigin.TRUST_CONTRADICTION else ForensicCaseOrigin.ALARM,
                s("cell_identity"), c.getInt(c.getColumnIndexOrThrow("highest_phase")),
                c.getInt(c.getColumnIndexOrThrow("confirmed")) != 0,
                c.getInt(c.getColumnIndexOrThrow("sample_count"))
            )
        } }
        return out
    }

    /** Deletes one closed forensic package and all of its samples atomically. */
    fun deleteForensicCase(caseId: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.delete(TABLE_FORENSIC_SAMPLES, "case_id=?", arrayOf(caseId.toString()))
            val deleted = db.delete(TABLE_FORENSIC_CASES, "id=? AND state NOT IN (?,?)", arrayOf(
                caseId.toString(), ForensicCaseState.CAPTURING.name, ForensicCaseState.POST_CAPTURE.name
            )) > 0
            if (deleted) db.setTransactionSuccessful()
            deleted
        } finally {
            db.endTransaction()
        }
    }

    fun getForensicSamples(caseId: Long): List<ForensicSample> {
        val out = mutableListOf<ForensicSample>()
        readableDatabase.rawQuery("SELECT * FROM $TABLE_FORENSIC_SAMPLES WHERE case_id=? ORDER BY id", arrayOf(caseId.toString())).use { c ->
            while (c.moveToNext()) out += ForensicSample(
                c.getLong(c.getColumnIndexOrThrow("id")), caseId,
                c.getLong(c.getColumnIndexOrThrow("wall_time_ms")), c.getLong(c.getColumnIndexOrThrow("elapsed_time_ms")),
                c.getString(c.getColumnIndexOrThrow("event")), c.getString(c.getColumnIndexOrThrow("payload_json"))
            )
        }
        return out
    }

    private fun createIncidentTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_INCIDENTS (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, started_at TEXT NOT NULL, " +
                "updated_at TEXT NOT NULL, ended_at TEXT, identity TEXT NOT NULL, " +
                "cid TEXT NOT NULL, radio TEXT NOT NULL, state TEXT NOT NULL, " +
                "highest_phase INTEGER NOT NULL, required_phases INTEGER NOT NULL, " +
                "score INTEGER NOT NULL, anomaly_confidence REAL NOT NULL, " +
                "reason TEXT NOT NULL, heuristic_snapshot TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_incident_identity_state ON $TABLE_INCIDENTS (identity, state)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_incident_updated ON $TABLE_INCIDENTS (updated_at)")
    }

    /**
     * Abre o actualiza la caja negra del episodio actual. Una fase 1/3 ya se conserva; 3/3 cambia
     * el estado a CONFIRMED. No crea registros para observaciones sub-umbral.
     */
    fun recordIncidentPhase(cell: CellData) {
        val progress = cell.temporalProgress
        if (!progress.active) return
        val db = writableDatabase
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val openId = findOpenIncidentId(db, cell.identityKey)
        val state = if (progress.confirmed) IncidentState.CONFIRMED else IncidentState.OBSERVING
        val values = ContentValues().apply {
            put("updated_at", now)
            put("state", state.name)
            put("highest_phase", progress.phase)
            put("required_phases", progress.required)
            put("score", cell.securityScore)
            put("anomaly_confidence", cell.anomalyConfidence)
            put("reason", cell.suspiciousReason.orEmpty())
            put("heuristic_snapshot", cell.heuristicReport.snapshot())
        }
        if (openId == null) {
            values.put("started_at", now)
            values.put("identity", cell.identityKey)
            values.put("cid", cell.cellId)
            values.put("radio", cell.radioTech.name)
            db.insert(TABLE_INCIDENTS, null, values)
        } else {
            db.update(TABLE_INCIDENTS, values, "id=?", arrayOf(openId.toString()))
        }
    }

    /** Cierra episodios que dejaron de observarse o fueron interrumpidos por un handover. */
    fun closeOpenIncidents(activeIdentity: String?, interrupted: Boolean = false) {
        val db = writableDatabase
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val values = ContentValues().apply {
            put("updated_at", now)
            put("ended_at", now)
            put("state", if (interrupted) IncidentState.INTERRUPTED.name else IncidentState.RECOVERED.name)
        }
        val openStates = "state IN ('${IncidentState.OBSERVING.name}','${IncidentState.CONFIRMED.name}')"
        if (activeIdentity == null) {
            db.update(TABLE_INCIDENTS, values, openStates, null)
        } else {
            db.update(TABLE_INCIDENTS, values, "$openStates AND identity=?", arrayOf(activeIdentity))
        }
    }

    private fun findOpenIncidentId(db: SQLiteDatabase, identity: String): Long? {
        db.rawQuery(
            "SELECT id FROM $TABLE_INCIDENTS WHERE identity=? AND state IN (?,?) ORDER BY id DESC LIMIT 1",
            arrayOf(identity, IncidentState.OBSERVING.name, IncidentState.CONFIRMED.name)
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    fun getIncidents(): List<IncidentRecord> {
        val result = mutableListOf<IncidentRecord>()
        readableDatabase.rawQuery("SELECT * FROM $TABLE_INCIDENTS ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) {
                fun s(name: String) = c.getString(c.getColumnIndexOrThrow(name))
                result += IncidentRecord(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    startedAt = s("started_at"), updatedAt = s("updated_at"),
                    endedAt = c.getColumnIndexOrThrow("ended_at").let { if (c.isNull(it)) null else c.getString(it) },
                    identity = s("identity"), cid = s("cid"),
                    radio = runCatching { RadioTech.valueOf(s("radio")) }.getOrDefault(RadioTech.UNKNOWN),
                    state = runCatching { IncidentState.valueOf(s("state")) }.getOrDefault(IncidentState.INTERRUPTED),
                    highestPhase = c.getInt(c.getColumnIndexOrThrow("highest_phase")),
                    requiredPhases = c.getInt(c.getColumnIndexOrThrow("required_phases")),
                    score = c.getInt(c.getColumnIndexOrThrow("score")),
                    anomalyConfidence = c.getFloat(c.getColumnIndexOrThrow("anomaly_confidence")),
                    reason = s("reason"), heuristicSnapshot = s("heuristic_snapshot")
                )
            }
        }
        return result
    }

    /** Deletes one incident only after it has left the active observing states. */
    fun deleteIncident(incidentId: Long): Boolean = writableDatabase.delete(
        TABLE_INCIDENTS,
        "id=? AND ended_at IS NOT NULL",
        arrayOf(incidentId.toString())
    ) > 0

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
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
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
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
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
     * Asocia [status] a la observación más reciente de esta celda y tecnología y, si la API
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
    ): Int {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_VERIFIED, status.name)
            if (lat != null) put(COLUMN_API_LAT, lat)
            if (lon != null) put(COLUMN_API_LON, lon)
        }
        val identityWhere = if (mcc != null) {
            "$COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_MCC=? AND $COLUMN_RADIO=?"
        } else {
            "$COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_RADIO=?"
        }
        val args = if (mcc != null) arrayOf(cid, mnc, tac, mcc, radio.name)
                   else arrayOf(cid, mnc, tac, radio.name)

        // La respuesta describe el estado conocido AHORA. Se adjunta siempre a la observación
        // más reciente de esta identidad, aunque esa fila naciera como NOT_FOUND/REJECTED por una
        // consulta anterior. Limitar el UPDATE a PENDING hacía que una verificación posterior no
        // persistiera coordenadas ni estado. Solo se reescribe la última fila: las respuestas
        // históricas anteriores conservan su significado forense.
        val latestWhere = "$COLUMN_ID=(SELECT MAX($COLUMN_ID) FROM $TABLE_HISTORY WHERE $identityWhere)"
        return db.update(TABLE_HISTORY, values, latestWhere, args)
    }

    /**
     * Convierte la fila actual del cursor en un [HistoryRecord].
     *
     * v2.3.3 — Extraído de [getRecords] para que la lectura en streaming de [forEachRecord] y la
     * lista completa de la pantalla de historial usen exactamente el mismo mapeo. Un export y una
     * vista que interpretan las columnas de forma distinta es una fuente de discrepancias que no
     * se detecta hasta el análisis final.
     */
    private fun readRecord(cursor: Cursor): HistoryRecord {
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

        return HistoryRecord(
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
    }

    fun getRecords(limit: Int? = null): List<HistoryRecord> {
        val list = mutableListOf<HistoryRecord>()
        try {
        val db = this.readableDatabase
        val boundedLimit = limit?.coerceAtLeast(1)
        val query = "SELECT * FROM $TABLE_HISTORY ORDER BY $COLUMN_ID DESC" +
            (boundedLimit?.let { " LIMIT $it" } ?: "")
        val cursor: Cursor = db.rawQuery(query, null)
        try {
        if (cursor.moveToFirst()) {
            do {
                list.add(readRecord(cursor))
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

    fun getRecordCount(): Int = try {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_HISTORY", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    } catch (_: Exception) {
        0
    }

    fun clear() {
        val db = this.writableDatabase
        db.execSQL("DELETE FROM $TABLE_HISTORY")
        db.execSQL("DELETE FROM $TABLE_INCIDENTS")
        db.execSQL("DELETE FROM $TABLE_FORENSIC_SAMPLES")
        db.execSQL("DELETE FROM $TABLE_FORENSIC_CASES")
        db.execSQL("DELETE FROM $TABLE_CELL_TRANSITIONS")
        resetStableSiteLearning()
    }

    /**
     * Poda de registros antiguos: borra del historial las filas más viejas que [daysToKeep]
     * días. Las consultas de detección solo miran los últimos 30 días, así que mantener un
     * margen ([DEFAULT_RETENTION_DAYS] por defecto) garantiza que NO se borra nada que las
     * heurísticas puedan usar:
     * esto solo evita que la tabla crezca sin límite registrando 24/7. No toca el esquema ni
     * los datos recientes. Devuelve el número de filas borradas.
     */
    fun pruneOldRecords(daysToKeep: Int = DEFAULT_RETENTION_DAYS): Int {
        return try {
            val cutoff = System.currentTimeMillis() - (daysToKeep.toLong() * 24 * 60 * 60 * 1000)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            val threshold = sdf.format(Date(cutoff))
            val db = this.writableDatabase
            val historyDeleted = db.delete(TABLE_HISTORY, "$COLUMN_TIMESTAMP < ?", arrayOf(threshold))
            db.delete(TABLE_INCIDENTS, "updated_at < ?", arrayOf(threshold))
            val oldCases = db.rawQuery("SELECT id FROM $TABLE_FORENSIC_CASES WHERE updated_at < ?", arrayOf(threshold)).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            }
            oldCases.forEach { id -> db.delete(TABLE_FORENSIC_SAMPLES, "case_id=?", arrayOf(id.toString())) }
            db.delete(TABLE_FORENSIC_CASES, "updated_at < ?", arrayOf(threshold))
            db.delete(TABLE_CELL_TRANSITIONS, "last_seen_ms < ?", arrayOf(cutoff.toString()))
            val siteCutoff = System.currentTimeMillis() - 120L * 24 * 60 * 60 * 1000
            val eventCutoff = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
            val hardSiteCutoff = System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000
            db.delete(TABLE_SITE_EVENTS, "seen_ms < ?", arrayOf(eventCutoff.toString()))
            db.delete(TABLE_SITE_CELLS, "last_seen_ms < ? AND observations < 3", arrayOf(siteCutoff.toString()))
            db.delete(TABLE_SITE_CELLS, "last_seen_ms < ?", arrayOf(hardSiteCutoff.toString()))
            db.delete(TABLE_SITE_DAYS, "day < ?", arrayOf(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(siteCutoff))))
            db.delete(TABLE_SITE_MOTION_DAYS, "day < ?", arrayOf(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(siteCutoff))))
            // v2.10.1 — RF-only neighbour context follows exactly the same retention as full
            // neighbour identities. Without this, RF days older than 120 days kept counting towards
            // site maturity while full-identity days expired, and the tables grew without bound.
            db.delete(TABLE_SITE_RF_NEIGHBOUR_DAYS, "day < ?", arrayOf(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(siteCutoff))))
            db.delete(TABLE_SITE_RF_NEIGHBOURS, "last_seen_ms < ? AND observations < 3", arrayOf(siteCutoff.toString()))
            db.delete(TABLE_SITE_RF_NEIGHBOURS, "last_seen_ms < ?", arrayOf(hardSiteCutoff.toString()))
            db.execSQL("DELETE FROM $TABLE_SITE_RF_NEIGHBOUR_DAYS WHERE NOT EXISTS (SELECT 1 FROM $TABLE_SITE_RF_NEIGHBOURS r WHERE r.site_key=$TABLE_SITE_RF_NEIGHBOUR_DAYS.site_key AND r.fingerprint=$TABLE_SITE_RF_NEIGHBOUR_DAYS.fingerprint)")
            db.delete(TABLE_SITE_HOLDS, "last_seen_ms < ? AND active=0", arrayOf(siteCutoff.toString()))
            db.delete(TABLE_SITE_SHADOW_TRIGGERS, "last_seen_ms < ? AND closed_ms IS NOT NULL", arrayOf(siteCutoff.toString()))
            historyDeleted
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Aplica el tope de muestras forenses borrando CASOS CERRADOS ENTEROS — v2.8.0.
     *
     * La poda por antigüedad no basta: lo que llena el disco es el número de muestras, no su edad.
     * Pero la versión anterior recortaba por `id` sobre la tabla de muestras, sin mirar a qué caso
     * pertenecía cada una, y eso destruía la propiedad que hace útil a un paquete forense: que sea
     * completo. Un caso al que le faltan el prebúfer y los primeros minutos sigue listándose con su
     * código `ICD-…` y su recuento de muestras, se exporta y se analiza como si estuviera íntegro.
     * Un paquete que miente sobre su propia integridad es peor que no tener el paquete.
     *
     * Ahora se borra el caso cerrado más antiguo, y el siguiente, hasta caber bajo [maxSamples].
     * Los casos en CAPTURING o POST_CAPTURE no se tocan nunca: son la captura en curso, y borrarle
     * el suelo a la grabación mientras graba es exactamente lo que no debe pasar. Si con todos los
     * cerrados fuera el total sigue por encima del tope, se informa mediante
     * [ForensicPruneResult.overCapacity] en lugar de romper el caso abierto.
     */
    fun enforceForensicSampleCap(maxSamples: Int = MAX_FORENSIC_SAMPLES): ForensicPruneResult {
        return try {
            val db = this.writableDatabase
            val total = db.rawQuery("SELECT COUNT(*) FROM $TABLE_FORENSIC_SAMPLES", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
            if (total <= maxSamples) return ForensicPruneResult(remainingSamples = total)

            // Candidatos: SOLO casos cerrados, del más antiguo al más reciente (id AUTOINCREMENT).
            // Este filtro es la garantía de que una captura en curso (CAPTURING / POST_CAPTURE)
            // no se puede mutilar: la política de abajo solo borra lo que esta consulta devuelve.
            val candidates = db.rawQuery(
                "SELECT c.id, (SELECT COUNT(*) FROM $TABLE_FORENSIC_SAMPLES s WHERE s.case_id=c.id) n " +
                    "FROM $TABLE_FORENSIC_CASES c WHERE c.state IN (?,?) ORDER BY c.id ASC",
                arrayOf(ForensicCaseState.READY.name, ForensicCaseState.INTERRUPTED.name)
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(ForensicRetentionPolicy.Candidate(c.getLong(0), c.getInt(1)))
                    }
                }
            }

            val doomed = ForensicRetentionPolicy.casesToDelete(total, maxSamples, candidates)
            db.beginTransaction()
            try {
                doomed.forEach { candidate ->
                    // El borrado explícito de las muestras no sobra aunque ON DELETE CASCADE esté
                    // activo: deja el recuento a la vista y no depende del pragma de la conexión.
                    db.delete(TABLE_FORENSIC_SAMPLES, "case_id=?", arrayOf(candidate.caseId.toString()))
                    db.delete(TABLE_FORENSIC_CASES, "id=?", arrayOf(candidate.caseId.toString()))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            // Releer el total real tras la transacción. Así el diagnóstico no depende de una
            // resta estimada si otra ruta de mantenimiento cambia la BD en el futuro.
            val remaining = db.rawQuery("SELECT COUNT(*) FROM $TABLE_FORENSIC_SAMPLES", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
            ForensicPruneResult(
                casesDeleted = doomed.size,
                samplesDeleted = doomed.sumOf { it.sampleCount },
                remainingSamples = remaining,
                overCapacity = remaining > maxSamples
            )
        } catch (_: Exception) {
            ForensicPruneResult()
        }
    }

    /**
     * Recorre una instantánea coherente del historial sin materializarla en memoria. Devuelve el
     * número de filas que la instantánea declaró antes de recorrerla.
     *
     * v2.3.3 — [getRecords] carga decenas de miles de objetos de golpe y, si algo falla a mitad,
     * se traga la excepción y devuelve un export parcial que la pantalla celebra como éxito. Para
     * exportar una campaña entera hace falta lo contrario: streaming y fallo ruidoso. Esta función
     * NO captura excepciones a propósito.
     */
    fun forEachRecord(action: (HistoryRecord) -> Unit): Int {
        val db = this.readableDatabase
        db.beginTransactionNonExclusive()
        try {
            val expected = db.rawQuery("SELECT COUNT(*) FROM $TABLE_HISTORY", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
            db.rawQuery("SELECT * FROM $TABLE_HISTORY ORDER BY $COLUMN_ID DESC", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        action(readRecord(cursor))
                    } while (cursor.moveToNext())
                }
            }
            db.setTransactionSuccessful()
            return expected
        } finally {
            db.endTransaction()
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
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val recentThreshold = dateFormat.format(Date(fiveMinutesAgo))
        val oldThreshold = dateFormat.format(Date(thirtyDaysAgo))

        if (!hasMatureTrustedBaseline(cellId, mnc, tac, mcc, radio, oldThreshold)) {
            return emptyList()
        }
        
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
              AND $COLUMN_SCORE >= ?
              AND ($COLUMN_FAILED_H IS NULL OR TRIM($COLUMN_FAILED_H) = '' OR $COLUMN_FAILED_H = 'OK')
              AND $COLUMN_TIMESTAMP < ?
              AND $COLUMN_TIMESTAMP > ?
            ORDER BY $COLUMN_ID DESC
            LIMIT 300
        """.trimIndent()

        val maxUsableRecords = 20
        val cursor = db.rawQuery(
            query,
            arrayOf(
                cellId, mnc, tac, mcc, radio.name, TRUSTED_BASELINE_MIN_SCORE.toString(),
                recentThreshold, oldThreshold
            )
        )
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
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val oldThreshold = dateFormat.format(Date(thirtyDaysAgo))

        if (!hasMatureTrustedBaseline(cellId, mnc, tac, mcc, radio, oldThreshold)) return null

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
              AND $COLUMN_SCORE >= ?
              AND ($COLUMN_FAILED_H IS NULL OR TRIM($COLUMN_FAILED_H) = '' OR $COLUMN_FAILED_H = 'OK')
              AND $COLUMN_TIMESTAMP > ?
            ORDER BY $COLUMN_ID DESC
            LIMIT 200
        """.trimIndent()

        val cursor = db.rawQuery(
            query,
            arrayOf(cellId, mnc, tac, mcc, radio.name, TRUSTED_BASELINE_MIN_SCORE.toString(), oldThreshold)
        )
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
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val threshold = dateFormat.format(Date(ninetyDaysAgo))

        val query = """
            SELECT $COLUMN_SCORE, $COLUMN_TIMESTAMP, $COLUMN_FAILED_H
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
                    val reason = cursor.getString(2).orEmpty()
                    if (score >= 85 && (reason.isBlank() || reason == "OK")) clean++
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
     * Evidence for the revocable local-cell confidence profile. No new table is needed: only
     * clean rows are read, with a three-samples-per-day cap so rapid sampling cannot manufacture
     * trust. Suspicious/quarantined observations remain auditable but never contribute.
     */
    fun getLocalCellTrustEvidence(cell: CellData): LocalCellTrustEvidence {
        if (cell.cellId == "N/A" || cell.radioTech == RadioTech.UNKNOWN) return LocalCellTrustEvidence()
        val threshold = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(
            Date(System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000)
        )
        val args = arrayOf(
            cell.cellId, cell.mnc, cell.tac, cell.mcc, cell.radioTech.name,
            TRUSTED_BASELINE_MIN_SCORE.toString(), threshold
        )
        val pciCounts = HashMap<Int, Int>()
        val arfcnCounts = HashMap<Int, Int>()
        val pciByArfcn = HashMap<Int, HashMap<Int, Int>>()
        val recentCleanPcisByArfcn = HashMap<Int, MutableSet<Int>>()
        var detailedClean = 0
        var located = 0
        var rf = 0
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val cleanWhere = "$COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_MCC=? " +
            "AND $COLUMN_RADIO=? AND $COLUMN_SCORE>=? " +
            "AND ($COLUMN_FAILED_H IS NULL OR TRIM($COLUMN_FAILED_H)='' OR $COLUMN_FAILED_H='OK') " +
            "AND $COLUMN_TIMESTAMP>=? AND length($COLUMN_TIMESTAMP)>=10"
        val temporalSummary = getDailyEvidenceSummary(cleanWhere, args, format)
        val recentThreshold = format.format(Date(System.currentTimeMillis() - 48L * 60 * 60 * 1000))
        readableDatabase.rawQuery(
            "SELECT $COLUMN_TIMESTAMP,$COLUMN_LAT,$COLUMN_LON,$COLUMN_PCI,$COLUMN_ARFCN " +
                "FROM $TABLE_HISTORY WHERE $cleanWhere ORDER BY $COLUMN_ID DESC LIMIT 500",
            args
        ).use { c ->
            while (c.moveToNext()) {
                val ts = c.getString(0).orEmpty()
                detailedClean++
                if (!c.isNull(1) && !c.isNull(2)) located++
                val pci = if (!c.isNull(3)) c.getInt(3).takeIf { it in 0..1007 } else null
                val arfcn = if (!c.isNull(4)) c.getInt(4).takeIf { it > 0 } else null
                if (pci != null) { pciCounts[pci] = (pciCounts[pci] ?: 0) + 1; rf++ }
                if (arfcn != null) arfcnCounts[arfcn] = (arfcnCounts[arfcn] ?: 0) + 1
                if (pci != null && arfcn != null) {
                    val perCarrier = pciByArfcn.getOrPut(arfcn) { HashMap() }
                    perCarrier[pci] = (perCarrier[pci] ?: 0) + 1
                    if (ts >= recentThreshold) recentCleanPcisByArfcn.getOrPut(arfcn) { linkedSetOf() } += pci
                }
            }
        }
        fun establishedValues(counts: Map<Int, Int>): Set<Int> =
            com.alexisgordr.icdetector.core.DetailedRfEvidence.establishedValues(counts, detailedClean)
        val knownPcisByArfcn = pciByArfcn.mapNotNull { (carrier, counts) ->
            val carrierTotal = counts.values.sum()
            val established = counts.filterValues { count ->
                count >= 2 && carrierTotal > 0 && count.toDouble() / carrierTotal >= 0.15
            }.keys
            if (established.isEmpty()) null else carrier to established
        }.toMap()
        var trustedTransitions = 0
        var trustedRoutes = 0
        readableDatabase.rawQuery(
            "SELECT trusted_observations FROM $TABLE_CELL_TRANSITIONS " +
                "WHERE (from_identity=? OR to_identity=?) AND trusted_observations>0",
            arrayOf(cell.identityKey, cell.identityKey)
        ).use { c -> while (c.moveToNext()) { trustedTransitions += c.getInt(0); trustedRoutes++ } }
        val candidate = if (cell.pci != null && cell.arfcn != null &&
            cell.pci !in knownPcisByArfcn[cell.arfcn].orEmpty()) {
            var candidateLocated = 0
            val candidateArgs = arrayOf(
                cell.cellId, cell.mnc, cell.tac, cell.mcc, cell.radioTech.name,
                cell.pci.toString(), cell.arfcn.toString(),
                LOCAL_TRUST_RECONFIGURATION_HISTORY_LIKE, threshold
            )
            val candidateWhere = "$COLUMN_CID=? AND $COLUMN_MNC=? AND $COLUMN_TAC=? AND $COLUMN_MCC=? " +
                "AND $COLUMN_RADIO=? AND $COLUMN_PCI=? AND $COLUMN_ARFCN=? AND $COLUMN_SCORE>=100 " +
                "AND $COLUMN_FAILED_H LIKE ? AND $COLUMN_TIMESTAMP>=? AND length($COLUMN_TIMESTAMP)>=10"
            val candidateTemporal = getDailyEvidenceSummary(candidateWhere, candidateArgs, format)
            readableDatabase.rawQuery(
                "SELECT $COLUMN_LAT,$COLUMN_LON FROM $TABLE_HISTORY WHERE $candidateWhere " +
                    "ORDER BY $COLUMN_ID DESC LIMIT 500",
                candidateArgs
            ).use { c ->
                while (c.moveToNext()) {
                    if (!c.isNull(0) && !c.isNull(1)) candidateLocated++
                }
            }
            LocalRfReconfiguration(
                pci = cell.pci,
                arfcn = cell.arfcn,
                distinctDays = candidateTemporal.distinctDays,
                cappedObservations = candidateTemporal.cappedObservations,
                locatedObservations = candidateLocated,
                ageHours = candidateTemporal.ageHours,
                oldPairSeenRecently = recentCleanPcisByArfcn[cell.arfcn].orEmpty().any { it != cell.pci }
            )
        } else null
        return LocalCellTrustEvidence(
            cleanObservations = temporalSummary.observations,
            cappedCleanObservations = temporalSummary.cappedObservations,
            distinctDays = temporalSummary.distinctDays,
            ageHours = temporalSummary.ageHours,
            locatedObservations = located,
            knownPcis = establishedValues(pciCounts),
            knownArfcns = establishedValues(arfcnCounts),
            knownPcisByArfcn = knownPcisByArfcn,
            reconfigurationCandidate = candidate,
            rfObservations = rf,
            trustedTransitions = trustedTransitions,
            trustedRoutes = trustedRoutes
        )
    }

    private fun getDailyEvidenceSummary(
        whereClause: String,
        args: Array<String>,
        format: SimpleDateFormat
    ): com.alexisgordr.icdetector.core.DailyEvidenceSummary {
        val buckets = mutableListOf<com.alexisgordr.icdetector.core.DailyEvidenceBucket>()
        readableDatabase.rawQuery(
            "SELECT substr($COLUMN_TIMESTAMP,1,10),COUNT(*),MIN($COLUMN_TIMESTAMP),MAX($COLUMN_TIMESTAMP) " +
                "FROM $TABLE_HISTORY WHERE $whereClause GROUP BY substr($COLUMN_TIMESTAMP,1,10)",
            args
        ).use { c ->
            while (c.moveToNext()) {
                buckets += com.alexisgordr.icdetector.core.DailyEvidenceBucket(
                    observations = c.getInt(1),
                    firstTimestampMs = runCatching { format.parse(c.getString(2))?.time }.getOrNull(),
                    lastTimestampMs = runCatching { format.parse(c.getString(3))?.time }.getOrNull()
                )
            }
        }
        return com.alexisgordr.icdetector.core.DailyEvidenceSummary.from(buckets)
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
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val threshold = dateFormat.format(Date(ninetyDaysAgo))

        if (!hasMatureTrustedBaseline(cellId, mnc, tac, mcc, radio, threshold)) return null

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
              AND $COLUMN_SCORE >= ?
              AND ($COLUMN_FAILED_H IS NULL OR TRIM($COLUMN_FAILED_H) = '' OR $COLUMN_FAILED_H = 'OK')
              AND $COLUMN_TIMESTAMP > ?
            ORDER BY $COLUMN_ID DESC
            LIMIT 200
        """.trimIndent()

        val rsrqs = mutableListOf<Int>()
        val sinrs = mutableListOf<Int>()
        db.rawQuery(
            query,
            arrayOf(cellId, mnc, tac, mcc, radio.name, TRUSTED_BASELINE_MIN_SCORE.toString(), threshold)
        ).use { cursor ->
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
                  AND ABS($COLUMN_API_LAT - CAST(? AS REAL)) <= CAST(? AS REAL)
                  AND ABS($COLUMN_API_LON - CAST(? AS REAL)) <= CAST(? AS REAL)
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
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val oldThreshold = dateFormat.format(Date(thirtyDaysAgo))
        val recentThreshold = dateFormat.format(Date(recentWindow))

        if (!hasMatureTrustedBaseline(cellId, mnc, tac, mcc, radio, oldThreshold)) {
            return CellRfStability(0, emptyList(), emptyList())
        }

        val query = """
            SELECT $COLUMN_PCI, $COLUMN_ARFCN, $COLUMN_TIMESTAMP
            FROM $TABLE_HISTORY
            WHERE $COLUMN_CID = ?
              AND $COLUMN_MNC = ?
              AND $COLUMN_TAC = ?
              AND $COLUMN_MCC = ?
              AND $COLUMN_RADIO = ?
              AND $COLUMN_SCORE >= ?
              AND ($COLUMN_FAILED_H IS NULL OR TRIM($COLUMN_FAILED_H) = '' OR $COLUMN_FAILED_H = 'OK')
              AND $COLUMN_TIMESTAMP > ?
        """.trimIndent()

        val cursor = db.rawQuery(
            query,
            arrayOf(cellId, mnc, tac, mcc, radio.name, TRUSTED_BASELINE_MIN_SCORE.toString(), oldThreshold)
        )
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
