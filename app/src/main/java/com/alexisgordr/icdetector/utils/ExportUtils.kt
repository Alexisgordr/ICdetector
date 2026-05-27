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
                        writer.append("Date,Timestamp,NetType,CID,MNC,TAC,MCC,DBM,Verified,SecurityScore,FailedHeuristics\n")
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
}
