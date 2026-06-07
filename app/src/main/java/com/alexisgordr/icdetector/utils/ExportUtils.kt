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
                        writer.append("Timestamp,NetType,CID,MNC,TAC,MCC,DBM,Verified,SecurityScore,FailedHeuristics,Lat,Lon,PCI,ARFCN,RSRQ,SINR\n")
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
                                item.sinr ?: ""
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
