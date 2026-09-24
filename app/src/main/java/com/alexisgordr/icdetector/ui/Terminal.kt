package com.alexisgordr.icdetector.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.alexisgordr.icdetector.R

@Composable
fun LiveTerminalPanel(logs: List<String>) {
    val scrollState = rememberScrollState()
    val english = LocalConfiguration.current.locales[0].language != "es"

    // El buffer conserva 40 líneas; su tamaño deja de cambiar al llenarse. La última línea sí
    // identifica cada actualización y mantiene activo el auto-scroll durante toda la sesión.
    LaunchedEffect(logs.lastOrNull()) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF050505)),
        border = BorderStroke(1.dp, Color(0xFF222222)),
        shape = RoundedCornerShape(4.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                stringResource(R.string.raw_terminal_output),
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
                    val visibleLine = if (english) localizeTerminalLine(logLine) else logLine
                    Text(
                        text = visibleLine,
                        color = if (logLine.contains("PELIGRO") || logLine.contains("⚠️") || logLine.contains("🚨") || logLine.contains("ALERTA")) Color(0xFFCF6679) 
                                else if (logLine.contains("✅") || logLine.contains("VERIFICADA") || logLine.contains("SYS:")) Color(0xFF4CAF50)
                                else Color(0xFF00FF00),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        lineHeight = 12.sp
                    )
                }
            }
        }
    }
}

internal fun localizeTerminalLine(line: String): String {
    var translated = line
    val replacements = linkedMapOf(
        // v2.10.4 — Líneas [RADIO] y [SERVICIO]. Van antes que las genéricas ("Celda ", etc.)
        // y las frases largas antes que las cortas que contienen.
        "[SERVICIO]" to "[SERVICE]",
        "Estado de servicio" to "Service state",
        "EN SERVICIO" to "IN SERVICE",
        "SIN SERVICIO" to "OUT OF SERVICE",
        "SOLO EMERGENCIAS" to "EMERGENCY ONLY",
        "RADIO APAGADA" to "RADIO OFF",
        "red anunciada distinta de la SIM sin roaming" to "network PLMN differs from SIM without roaming",
        "selección manual de red" to "manual network selection",
        " no registrado ·" to " not registered ·",
        " registrado ·" to " registered ·",
        "roaming=sí" to "roaming=yes",
        " · datos=" to " · data=",
        " · red=" to " · network=",
        "sin portadoras secundarias" to "no secondary carriers",
        "portadoras secundarias" to "secondary carriers",
        "estado de conexión" to "connection state",
        "entradas registradas" to "registered entries",
        "Servidora " to "Serving ",
        "Celda de grupo cerrado" to "Closed subscriber group cell",
        "posible femtocelda" to "possible femtocell",
        " nombre=" to " name=",
        "Celda ya verificada" to "Cell already verified",
        "Coordenadas frescas rellenadas" to "Fresh coordinates filled",
        "GPS inestable tras espera" to "GPS unstable after waiting",
        "GPS estabilizado" to "GPS stabilized",
        "Solicitando fix GPS preciso" to "Requesting precise GPS fix",
        "Fix GPS preciso no disponible" to "Precise GPS fix unavailable",
        "Handover celular completado" to "Cellular handover completed",
        "Nueva celda" to "New cell",
        "Ping-Pong observado" to "Ping-pong observed",
        "Sin credenciales configuradas" to "No credentials configured",
        "Verificando antena" to "Verifying cell",
        "Antena no identificada" to "Cell not identified",
        "Validación OK" to "Validation OK",
        "Error al verificar" to "Verification error",
        "Respuesta descartada" to "Response rejected",
        "Ubicación continua activa" to "Continuous location active",
        "Batería crítica" to "Critical battery",
        "Alimentación recuperada" to "Power recovered",
        "Modo Avión activo" to "Airplane mode active",
        "Conexión restaurada" to "Connection restored",
        "Escritura en base de datos restablecida" to "Database writes restored",
        "Movimiento detectado" to "Movement detected",
        "ICdetector Engine Iniciado" to "ICdetector engine started",
        "Esperando hooks del módem" to "Waiting for modem callbacks",
        "Error en ciclo de escaneo" to "Scanning-cycle error",
        "se continúa" to "continuing",
        "ubicación continua en pausa" to "continuous location paused",
        "recolección 24/7 reanudadas" to "24/7 collection resumed",
        "Suspendiendo escaneo" to "Pausing scanning",
        "Analizando nueva celda activa" to "Analyzing new serving cell",
        "Fix disponible pero no lo bastante fresco" to "Fix available but not fresh enough",
        "se omite el relleno" to "coordinate backfill skipped",
        "espera al fix preciso" to "waiting for a precise fix",
        "sin necesidad de API" to "API query not required",
        "relanzando verificación" to "retrying verification",
        "sin confirmar; sin tono" to "unconfirmed; no alert tone",
        "Ignorando alerta" to "Ignoring alert",
        "ESCRITURA FALLIDA" to "WRITE FAILED",
        "la base de datos rechaza las filas" to "the database is rejecting rows",
        "disco lleno" to "disk full",
        "La recolección NO está guardando datos" to "Collection is NOT saving data",
        "Coordenada rechazada" to "Coordinate rejected",
        "áreas de seguimiento sin relación entre sí" to "unrelated tracking areas",
        "valor por defecto de la API" to "API default value",
        "no identifica ninguna antena" to "does not identify a cell",
        "de tu posición de referencia" to "from your reference position",
        "índice heurístico" to "heuristic score",
        "CICLO DE AUDITORÍA" to "AUDIT CYCLE",
        "reglas evaluadas" to "rules evaluated",
        "16 REGLAS" to "16 RULES",
        "sin fallos" to "without failures",
        "Sin anomalías en las reglas que pudieron evaluarse" to "No anomalies in the rules that could be evaluated",
        "Sin alarma, pero con observaciones" to "No alarm, but observations remain",
        "Antena sospechosa detectada" to "Suspicious cell detected",
        "El módem no reporta Timing Advance en esta celda" to "The modem does not report Timing Advance for this cell",
        "no juzga geometría" to "does not assess geometry",
        "de la antena" to "from the cell",
        "sin conversión defendible" to "no defensible conversion",
        "no se usa para geometría" to "not used for geometry",
        "Ve a Ajustes para añadir OpenCellID" to "Open Settings to add OpenCellID",
        "Esta antena ya constaba verificada" to "This cell was already verified",
        "una consulta vacía o fallida no borra esa evidencia" to "an empty or failed query does not erase that evidence",
        "Se reintentará" to "Will retry",
        "no permite afirmar ni desmentir nada sobre esta antena" to "provides no evidence for or against this cell",
        "Firmas geográficas obtenidas" to "Geographic signatures obtained",
        "Poda de histórico" to "History pruning",
        "registros de más de" to "records older than",
        "días eliminados" to "days deleted",
        "Muestras forenses recortadas" to "Forensic samples trimmed",
        // v2.8.0 — Poda forense por casos completos y salud de la escritura de muestras.
        "Poda forense" to "Forensic pruning",
        "caso(s) cerrado(s) eliminados enteros" to "closed case(s) deleted in full",
        "muestras) para respetar el tope de" to "samples) to respect the cap of",
        "Restantes" to "Remaining",
        "Tope forense superado" to "Forensic cap exceeded",
        "muestras) con solo casos abiertos" to "samples) with only open cases left",
        "No se recortan: una captura en curso no se mutila." to
            "Not trimmed: a capture in progress is never mutilated.",
        "Captura forense degradada — las muestras no se están guardando." to
            "Forensic capture degraded — samples are not being saved.",
        "Captura forense restablecida." to "Forensic capture restored.",
        "Mantenimiento de base de datos fallido" to "Database maintenance failed",
        "No se pudo registrar" to "Could not register",
        "Red 2G/3G detectada" to "2G/3G network detected",
        "Abriendo ajustes para Modo Avión manual" to "Opening settings for manual Airplane mode",
        "Latencia" to "Latency",
        "sin suficientes endpoints disponibles" to "not enough endpoints available",
        "anómala" to "anomalous",
        "media" to "average",
        "ANOMALÍA DE RED PERSISTENTE" to "PERSISTENT NETWORK ANOMALY",
        "posible interferencia" to "possible interference",
        "normalizada" to "normalized",
        "aprendiendo baseline" to "learning baseline",
        "Señal sospechosamente fuerte detectada" to "Suspiciously strong signal detected",
        "Efecto Ping-Pong confirmado" to "Ping-pong effect confirmed",
        "Callback de telefonía registrado" to "Telephony callback registered",
        "detección de cifrado nulo/IMSI: pendiente API Android 16" to "null-cipher/IMSI detection: awaiting Android 16 API",
        "Transición coherente" to "Coherent transition",
        "Transición incoherente" to "Incoherent transition",
        "respuesta descartada por coordenada no creíble" to "response rejected due to implausible coordinates",
        "No se concluye nada sobre la antena" to "No conclusion can be drawn about the cell",
        "respuesta sin coordenadas" to "response without coordinates",
        "no se puede comprobar" to "cannot be verified",
        "Estabilidad Potencia" to "Power stability",
        "Potencia vs Histórico" to "Power vs history",
        "Downgrade de Banda" to "Band downgrade",
        "Celda Aislada" to "Isolated cell",
        "Consistencia MCC" to "MCC consistency",
        "Límite MNC" to "MNC limit",
        "Validación Regional TAC" to "Regional TAC validation",
        "Geometría (TA)" to "Geometry (TA)",
        "Cifrado Nulo" to "Null ciphering",
        "Sanidad ARFCN" to "ARFCN sanity",
        "Integridad PCI" to "PCI integrity",
        "Consistencia Geográfica" to "Geographic consistency",
        "Correlación Latency + RF" to "Latency + RF correlation",
        "Correlación Latencia + RF" to "Latency + RF correlation",
        "Reputación de Celda" to "Cell reputation",
        "Coherencia de Transición" to "Transition coherence",
        "Resultado provisional (falta la verificación)" to "Provisional result (verification pending)",
        "Resultado Global" to "Overall result",
        "Auditoría en curso" to "Audit in progress",
        "Auditoría completada" to "Audit completed",
        "registrada en bases públicas" to "registered in public databases",
        "sin registro en bases públicas" to "not registered in public databases",
        "respuesta descartada o no creíble" to "rejected or implausible response",
        "no concluye nada sobre la antena" to "provides no conclusion about the cell",
        "las bases públicas no han contestado" to "public databases did not respond",
        "verificación pendiente" to "verification pending",
        "Falta la respuesta de las bases públicas" to "Waiting for the public-database response",
        "sin datos" to "unavailable",
        "CRÍTICO" to "CRITICAL",
        "El módem devuelve 0 en todas las celdas" to "The modem returns 0 for every cell",
        "distintas comprobadas" to "distinct cells checked",
        "el campo no está poblado y H6 queda N/A" to "the field is unpopulated and H6 remains N/A",
        "a menos de un paso de TA" to "less than one TA step",
        "No es una medida de 0 m" to "This is not a 0 m measurement",
        "es el escalón mínimo" to "it is the minimum step",
        "Consumo de batería elevado por diseño" to "High battery consumption by design",
        "las coordenadas son necesarias" to "coordinates are required",
        "hasta conectar el cargador" to "until the charger is connected",
        "para fijar coordenadas" to "to capture coordinates",
        "reintentando más tarde" to "retrying later",
        "Incidente localizado" to "Incident geolocated",
        "con coordenadas precisas" to "with precise coordinates",
        "Fix preciso obtenido" to "Precise fix obtained",
        "sin registros pendientes de coordenadas" to "no records awaiting coordinates",
        "Diagnóstico de TA recuperado del arranque anterior" to "TA diagnosis restored from the previous run",
        "el módem no rellena el campo" to "the modem does not populate the field",
        "Recolección interrumpida" to "Collection interrupted",
        "sin registrar nada desde la última escritura" to "nothing recorded since the last write",
        "Revisa si el servicio se detuvo" to "Check whether the service stopped",
        "reinicio del móvil" to "phone restart",
        "batería agotada" to "empty battery",
        "no se están guardando datos" to "data is not being saved",
        "ya consta en" to "already appears in",
        "registros" to "records",
        "eliminadas" to "deleted",
        "límite diario de consultas agotado" to "daily query limit exhausted",
        "NO significa que la antena no exista" to "This does NOT mean the cell does not exist",
        "demasiadas peticiones seguidas" to "too many consecutive requests",
        "la API key no es válida o no tiene permiso" to "the API key is invalid or lacks permission",
        "Revísala en Ajustes" to "Review it in Settings",
        "servicio temporalmente no disponible" to "service temporarily unavailable",
        "esta celda no está en su base" to "this cell is not in its database",
        "respuesta sin coordenadas y sin código de error" to "response without coordinates or an error code",
        "Respuesta" to "Response",
        "Celda detectada a distancia anómala" to "Cell detected at an anomalous distance",
        "Salto potencia" to "Signal-power jump",
        "Desviación TAC" to "TAC deviation",
        "Suplantación TA" to "TA spoofing",
        "Proximidad anómala" to "Anomalous proximity",
        "Cambios rápidos en parado" to "Rapid changes while stationary",
        "Consistencia geográfica fallida" to "Geographic consistency failed",
        "RF anómalo" to "Anomalous RF",
        "posible MITM" to "possible MITM",
        "Potencia anómala vs historial" to "Anomalous power vs history",
        "Transición celular incoherente" to "Incoherent cellular transition",
        "Episodio multiseñal" to "Multi-signal episode",
        "Cambio en identidad celular consolidada" to "Established local cell identity changed",
        "familias independientes" to "independent families",
        "todavía no se ha observado un handover evaluable" to "no evaluable handover has been observed yet",
        "faltan dos posiciones GPS contemporáneas" to "two contemporaneous GPS positions are unavailable",
        "la precisión GPS es insuficiente" to "GPS accuracy is insufficient",
        "se exige" to "required",
        "Transición aprendida previamente" to "Previously learned transition",
        "observaciones coherentes" to "coherent observations",
        "baseline geográfico en aprendizaje" to "geographic baseline still learning",
        "Handover incoherente" to "Incoherent handover",
        "el móvil recorrió" to "the phone moved",
        "Desplazamiento compatible con las zonas históricas" to "Movement consistent with historical areas",
        "Datos disponibles; no se observó la condición anómala" to "Data available; the anomalous condition was not observed",
        "La condición anómala de esta regla está presente en el ciclo actual" to "This rule's anomalous condition is present in the current cycle",
        "Wi-Fi activo; se evita atribuir el contexto de red al enlace celular" to "Wi-Fi active; network context is not attributed to the cellular link",
        "sin lectura celular válida" to "no valid cellular reading",
        "hacen falta celdas vecinas con potencia para comparar" to "neighbor cells with signal readings are required for comparison",
        "falta MCC válido en la celda activa o en sus vecinas" to "the serving cell or its neighbors lack a valid MCC",
        "faltan MNC válidos de celdas vecinas" to "valid MNC values from neighbor cells are unavailable",
        "no hay TAC válidos de vecinas para contrastar" to "no valid neighbor TAC values are available for comparison",
        "el módem no entrega un Timing Advance convertible a metros" to "the modem does not provide Timing Advance convertible to meters",
        "no hay celdas vecinas disponibles para contrastar el espectro" to "no neighbor cells are available for spectrum comparison",
        "el módem no entrega ARFCN/EARFCN para esta tecnología" to "the modem does not provide ARFCN/EARFCN for this technology",
        "Android o el fabricante no exponen el estado de cifrado a esta app" to "Android or the manufacturer does not expose ciphering status to this app",
        "estado de cifrado no disponible" to "ciphering status unavailable",
        "todavía no existe una secuencia temporal utilizable" to "no usable temporal sequence exists yet",
        "falta una posición GPS reciente" to "a recent GPS position is unavailable",
        "aún no hay observaciones previas geolocalizadas de esta celda" to "this cell has no previous geolocated observations yet",
        "identidad celular insuficiente para comparar" to "insufficient cell identity for comparison",
        "Wi-Fi/VPN impide atribuir la latencia al enlace celular" to "Wi-Fi/VPN prevents attributing latency to the cellular link",
        "la sonda de latencia todavía no tiene una medición válida" to "the latency probe does not yet have a valid measurement",
        "el módem no entrega RSRQ ni SINR" to "the modem provides neither RSRQ nor SINR",
        "faltan datos cruzados del ciclo" to "cross-signal data is unavailable for this cycle",
        "baseline en aprendizaje; se necesitan muestras históricas compatibles" to "baseline still learning; compatible historical samples are required",
        "falta una banda LTE anterior válida para comparar" to "a previous valid LTE band is unavailable for comparison",
        "la tecnología o banda actual no permite la comparación" to "the current technology or band does not allow comparison",
        "historial RF insuficiente" to "insufficient RF history",
        "observaciones" to "observations",
        "Celda " to "Cell ",
        "Antena " to "Cell ",
        "sobre " to "across ",
        "más tarde" to "later",
        "fresco" to "fresh",
        "SUPERADA" to "PASSED",
        "FALLIDA" to "FAILED",
        "NO EVALUADA" to "NOT EVALUATED"
    )
    replacements.forEach { (source, target) -> translated = translated.replace(source, target, ignoreCase = true) }
    return translated
}
