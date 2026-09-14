package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.VerificationStatus
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * REPRODUCCIÓN DE HISTORIALES REALES.
 *
 * `ScenarioTest` usa secuencias escritas a mano: prueba que el motor se comporta como dice su
 * diseño. Esto es el siguiente escalón — coger un **export real de campo** y volver a pasarlo por
 * el motor entero:
 *
 *     CSV -> CellData -> ThreatAnalyzer -> TemporalConfidence -> ¿alarma?
 *
 * Sirve para lo que ninguna otra prueba puede: contestar "¿cuántas alarmas habría producido esta
 * versión sobre dos meses de vida real?" antes de publicar un cambio. Es la red de seguridad
 * contra la regresión más cara que puede tener este proyecto — subir la sensibilidad sin darse
 * cuenta y llenar de ruido el historial de todo el mundo.
 *
 * ── CÓMO USARLO ──────────────────────────────────────────────────────────────────────────────
 * Deja un CSV exportado desde la app en cualquiera de estas rutas y el test lo recorrerá:
 *
 *     app/src/test/resources/replay/   (cualquier fichero .csv)
 *
 * Si no hay ninguno, el test **pasa sin hacer nada**: nadie debe verse obligado a tener datos de
 * campo para poder compilar el proyecto.
 *
 * ── AVISO DE PRIVACIDAD, Y VA EN SERIO ───────────────────────────────────────────────────────
 * Un export real contiene las coordenadas GPS de dónde has estado: tu casa, tu trabajo, tu ruta
 * diaria. `app/src/test/resources/replay/` está en .gitignore precisamente por eso.
 *
 * **No subas nunca un CSV real a un repositorio público.** Si quieres compartir un caso concreto,
 * vacía antes las columnas Lat, Lon, ApiLat y ApiLon: las heurísticas que este replay ejercita
 * —H1, H2, H6, H14, H15— no usan ninguna de ellas, así que el valor de regresión se conserva
 * intacto. Sería irónico que la herramienta que audita quién te sigue la pista fuese la que
 * publicara dónde vives. (Las columnas TA / TAUnit no llevan información de ubicación: se pueden
 * compartir sin problema, y son justo las que dan valor al replay.)
 *
 * El replay reconstruye también el Timing Advance y su unidad a partir de las
 * columnas `TA` / `TAUnit`, así que H6 se ejercita sobre observaciones reales y no solo sobre
 * escenarios escritos a mano. Con historiales anteriores a v2.1 esas columnas no existen, el TA
 * queda a null y H6 se abstiene — que es lo correcto: el dato no se registró, no es que faltara.
 *
 * ── LÍMITE HONESTO ───────────────────────────────────────────────────────────────────────────
 * Esto NO es ground truth: no hay etiquetas de "aquí había un IMSI-catcher". Es un historial
 * benigno por presunción razonable. Lo que mide es **estabilidad**, no acierto: si un cambio
 * dispara alarmas sobre datos que antes estaban tranquilos, hay algo que mirar.
 */
class CsvReplayTest {

    private val replayDir = File("src/test/resources/replay")

    /** Una fila del export, ya convertida a lo que el motor espera recibir. */
    private fun rowToCell(header: Map<String, Int>, cols: List<String>): CellData? {
        fun str(name: String): String? = header[name]?.let { cols.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() }
        fun int(name: String): Int? = str(name)?.toIntOrNull()

        val cid = str("CID") ?: return null
        val netType = str("NetType") ?: "4G LTE"
        val dbm = int("DBM") ?: return null

        return CellData(
            isRegistered = true,
            networkType = netType,
            cellId = cid,
            mnc = str("MNC") ?: "N/A",
            tac = str("TAC") ?: "N/A",
            dbm = dbm,
            mcc = str("MCC") ?: "N/A",
            verified = str("Verified")?.let {
                runCatching { VerificationStatus.valueOf(it) }.getOrNull()
            } ?: VerificationStatus.PENDING,
            arfcn = int("ARFCN"),
            pci = int("PCI"),
            rsrq = int("RSRQ"),
            sinr = int("SINR"),
            band = int("ARFCN")?.let { BandPlan.earfcnToBandLte(it) },
            // v2.1 — El export registra el TA y su unidad, así que el replay los
            // reconstruye y H6 se ejercita también sobre datos reales. Un historial anterior a
            // v2.1 no tiene esas columnas: entonces el TA queda a null y H6 simplemente no
            // juzga, que es el comportamiento correcto para "este dato no se registró".
            timingAdvance = int("TA"),
            timingAdvanceUnit = str("TAUnit")
                ?.let { runCatching { TimingAdvanceUnit.valueOf(it) }.getOrNull() }
                ?: TimingAdvanceUnit.UNKNOWN,
            radioTech = str("Radio")
                ?.let { runCatching { RadioTech.valueOf(it) }.getOrNull() }
                ?: RadioTech.UNKNOWN
        )
    }

    /** Parser CSV mínimo con comillas RFC 4180 — el mismo formato que escribe ExportUtils. */
    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    @Test fun `reproducir historiales reales no debe producir alarmas nuevas`() {
        val files = replayDir.listFiles { f -> f.extension.equals("csv", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()

        if (files.isEmpty()) {
            println(
                "[REPLAY] Sin historiales en ${replayDir.path} — nada que reproducir. " +
                    "Deja ahí un CSV exportado (sin columnas de coordenadas si va a salir de tu máquina)."
            )
            return
        }

        files.forEach { file ->
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.size < 2) return@forEach
            val header = splitCsvLine(lines.first()).withIndex().associate { (i, name) -> name.trim() to i }

            val confirmation = TemporalConfidence()
            var cycles = 0
            var alarms = 0
            val reasons = mutableListOf<String>()

            // El export va del más reciente al más antiguo; el motor necesita orden cronológico.
            lines.drop(1).reversed().forEach { line ->
                val cell = rowToCell(header, splitCsvLine(line)) ?: return@forEach
                cycles++
                val analyzed = ThreatAnalyzer.analyzeThreats(
                    active = cell,
                    neighbors = emptyList(),
                    isHardwareCipheringActive = true,
                    isHardwareCipheringAvailable = false,
                    cellChangeHistory = emptyList(),
                    currentLocation = null
                )
                val confirmed = confirmation.apply(analyzed)
                if (confirmed.isSuspicious) {
                    alarms++
                    reasons.add("${cell.cellId}: ${confirmed.suspiciousReason}")
                }
            }

            println("[REPLAY] ${file.name}: $cycles observaciones, $alarms alarmas confirmadas")
            reasons.take(5).forEach { println("          $it") }

            // Umbral deliberadamente laxo: sin vecinas ni historial, el replay solo puede
            // ejercitar las heurísticas que dependen de la celda servidora. Lo que vigila es un
            // cambio brusco, no el número exacto.
            val rate = if (cycles > 0) alarms.toDouble() / cycles else 0.0
            assertTrue(
                "El replay de ${file.name} disparó $alarms alarmas en $cycles observaciones " +
                    "(${"%.1f".format(rate * 100)} %). Sobre un historial benigno eso es una " +
                    "regresión de sensibilidad: ${reasons.take(3)}",
                rate <= 0.01
            )
        }
    }
}
