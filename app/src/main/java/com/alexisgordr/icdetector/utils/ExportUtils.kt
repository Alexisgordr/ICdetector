package com.alexisgordr.icdetector.utils

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.alexisgordr.icdetector.models.HistoryRecord
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object ExportUtils {
    fun exportToCsv(context: Context, items: List<HistoryRecord>, uri: Uri) {
        try {
            val resolver = context.contentResolver
            resolver.openOutputStream(uri).use { outputStream ->
                if (outputStream != null) {
                    OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                        // v2.1 — tres columnas nuevas al final (se añaden al final a propósito,
                        // para no romper scripts que leyeran el CSV por posición):
                        //   ThreatProb     probabilidad bayesiana en el momento de la observación.
                        //                  Sin ella no había forma de recalibrar los likelihood
                        //                  ratios con datos de campo (roadmap #7).
                        //   ApiLat/ApiLon  posición de la ANTENA según WiGLE/OpenCellID. Lat/Lon
                        //                  son y solo son la posición GPS del dispositivo; antes
                        //                  ambas magnitudes se mezclaban en las mismas columnas.
                        writer.append("Timestamp,NetType,CID,MNC,TAC,MCC,DBM,Verified,SecurityScore,FailedHeuristics,Lat,Lon,PCI,ARFCN,RSRQ,SINR,ThreatProb,ApiLat,ApiLon\n")
                        items.forEach { item ->
                            val row = listOf(
                                item.timestamp,
                                item.netType,
                                item.cid,
                                item.mnc,
                                item.tac,
                                item.mcc,
                                item.dbm,
                                item.verified.name,
                                item.score,
                                item.failedHeuristics,
                                item.lat ?: "",
                                item.lon ?: "",
                                item.pci ?: "",
                                item.arfcn ?: "",
                                item.rsrq ?: "",
                                item.sinr ?: "",
                                String.format(java.util.Locale.ROOT, "%.1f", item.threatProbability),
                                item.apiLat ?: "",
                                item.apiLon ?: ""
                            ).joinToString(",") { csvEscape(it) }
                            writer.append(row).append("\n")
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

    /**
     * Escapa un campo para CSV según RFC 4180: si contiene coma, comillas dobles o saltos de
     * línea, lo envuelve entre comillas y duplica las comillas internas; si no, lo deja tal cual.
     * Así ningún valor raro (p.ej. una razón con comillas o una coma) puede descuadrar una fila,
     * y los datos llegan íntegros al análisis posterior.
     */
    private fun csvEscape(value: Any?): String {
        val s = value?.toString() ?: ""
        return if (s.contains('"') || s.contains(',') || s.contains('\n') || s.contains('\r')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
    }
}
