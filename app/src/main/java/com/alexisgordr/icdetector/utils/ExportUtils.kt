package com.alexisgordr.icdetector.utils

import android.content.Context
import android.net.Uri
import com.alexisgordr.icdetector.models.HistoryRecord
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object ExportUtils {
    fun exportToCsv(context: Context, items: List<HistoryRecord>, uri: Uri): Result<Unit> =
        runCatching {
            val resolver = context.contentResolver
            resolver.openOutputStream(uri).use { outputStream ->
                requireNotNull(outputStream) { "El destino no permitió abrir el archivo" }
                    OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                        // Tres columnas al final (al final a propósito, para no romper scripts
                        // que leyeran el CSV por posición):
                        //   AnomalyConfidence  salida del motor bayesiano en ese instante.
                        //                      Se llama así y no "probabilidad" porque los likelihood
                        //                      ratios son estimaciones razonadas, NO constantes
                        //                      medidas, así que llamar "probabilidad" al posterior
                        //                      sugería una validación estadística que no existe.
                        //                      El número es idéntico; la etiqueta ahora dice lo
                        //                      que de verdad es. Ver BayesianScorer.
                        //   ApiLat/ApiLon      posición de la ANTENA según WiGLE/OpenCellID. Lat/Lon
                        //                      son y solo son la posición GPS del dispositivo; antes
                        //                      ambas magnitudes se mezclaban en las mismas columnas.
                        // v2.1 — TA, TAUnit y TAMeters. El Timing Advance era la única señal del motor
                        // que no salía en el export, y a la vez la de mayor penalización (-40).
                        // Sin exportarlo no había forma de saber si un teléfono concreto lo
                        // reporta siquiera. TAMeters va vacío cuando la unidad no admite una
                        // conversión defendible (NR, o valor raspado): esa columna vacía ES el
                        // dato — dice "tengo el número pero no puedo afirmar la distancia".
                        // v2.1 — Radio: la tecnología según la CLASE de CellInfo. NetType queda como estaba,
                        // pero NetType es la cadena del icono del móvil y no es de fiar para analizar:
                        // la misma celda alterna entre "4G" y "5G" sin cambiar de identidad. Radio es
                        // el dato firme.
                        writer.append("Timestamp,NetType,CID,MNC,TAC,MCC,DBM,Verified,SecurityScore,FailedHeuristics,Lat,Lon,PCI,ARFCN,RSRQ,SINR,AnomalyConfidence,ApiLat,ApiLon,TA,TAUnit,TAMeters,Radio\n")
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
                                String.format(java.util.Locale.ROOT, "%.1f", item.anomalyConfidence),
                                item.apiLat ?: "",
                                item.apiLon ?: "",
                                item.timingAdvance ?: "",
                                if (item.timingAdvance != null) item.timingAdvanceUnit.name else "",
                                item.timingAdvance?.let { item.timingAdvanceUnit.toMeters(it) } ?: "",
                                item.radio.name
                            ).joinToString(",") { csvEscape(it) }
                            writer.append(row).append("\n")
                        }
                        writer.flush()
                    }
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
