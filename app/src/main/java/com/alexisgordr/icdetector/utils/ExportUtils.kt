package com.alexisgordr.icdetector.utils

import android.content.Context
import android.net.Uri
import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.models.ServiceStateSnapshot
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object ExportUtils {

    /** Cabecera del CSV. Pura y expuesta para que las pruebas fijen el orden de las columnas. */
    const val CSV_HEADER = "Timestamp,NetType,CID,MNC,TAC,MCC,DBM,Verified,SecurityScore,FailedHeuristics,Lat,Lon,PCI,ARFCN,RSRQ,SINR,AnomalyConfidence,ApiLat,ApiLon,TA,TAUnit,TAMeters,Radio," +
        // v2.10.4 — Contexto de radio. Van al final para que cualquier análisis que lea las
        // columnas por nombre (o las 23 primeras por posición) siga funcionando igual.
        "ServingConnection,BandwidthKHz,Bands,AdditionalPlmns,CsgIndicator,CsgIdentity,CsgName," +
        "SecondaryCarriers,ServiceState,NetworkOperator,SimOperator,NetworkRoaming"

    /** Cabecera del CSV de eventos de servicio (pestaña Radio). */
    const val SERVICE_STATE_CSV_HEADER = "TimestampUtc,State,DataRegistered,VoiceRegistered,Searching," +
        "Roaming,NetworkOperator,NetworkName,SimOperator,ManualSelection,ChannelNumber," +
        "CellBandwidthsKHz,DataNetwork,VoiceNetwork,Source"

    /**
     * Comprueba que se escribieron exactamente las filas que la base de datos dijo tener.
     *
     * v2.3.3 — Antes, una lectura que fallaba a mitad devolvía las filas recogidas hasta ese
     * momento (las más nuevas, porque la consulta ordena por id descendente) y la pantalla
     * anunciaba "CSV exportado con éxito". Para el export final de una campaña de tres meses eso
     * es lo peor que puede pasar: un fichero truncado que parece completo. Ahora una discrepancia
     * es un fallo explícito.
     */
    fun verifyRowCount(expected: Int, written: Int) {
        if (expected != written) {
            throw IllegalStateException(
                "Export incompleto: la base de datos declara $expected filas y se escribieron $written. " +
                    "No se da por bueno un export parcial."
            )
        }
    }

    /**
     * Exporta el historial en streaming.
     *
     * [streamRecords] recorre una instantánea transaccional del cursor, entrega cada fila y
     * devuelve el recuento declarado por esa misma instantánea. Ninguna lista completa llega a
     * memoria y una escritura concurrente del servicio no puede provocar una falsa discrepancia.
     */
    fun exportToCsv(
        context: Context,
        uri: Uri,
        streamRecords: ((HistoryRecord) -> Unit) -> Int
    ): Result<Int> =
        runCatching {
            val resolver = context.contentResolver
            resolver.openOutputStream(uri).use { outputStream ->
                requireNotNull(outputStream) { "El destino no permitió abrir el archivo" }
                OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.append(CSV_HEADER).append("\n")
                    var written = 0
                    val expected = streamRecords { item ->
                        writer.append(csvRow(item)).append("\n")
                        written++
                    }
                    writer.flush()
                    verifyRowCount(expected, written)
                    written
                }
            }
        }

    /** Una fila del CSV, ya escapada. Pura: se puede probar sin Android. */
    fun csvRow(item: HistoryRecord): String = listOf(
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
        item.radio.name,
        item.connectionState?.name ?: "",
        item.bandwidthKhz ?: "",
        item.bands ?: "",
        item.additionalPlmns ?: "",
        item.csgIndicator?.let { if (it) 1 else 0 } ?: "",
        item.csgIdentity ?: "",
        item.csgName ?: "",
        item.secondaryCarriers ?: "",
        item.serviceState ?: "",
        item.networkOperator ?: "",
        item.simOperator ?: "",
        item.networkRoaming?.let { if (it) 1 else 0 } ?: ""
    ).joinToString(",") { csvEscape(it) }

    /** Una fila del CSV de eventos de servicio. Pura: se puede probar sin Android. */
    fun serviceStateCsvRow(item: ServiceStateSnapshot): String = listOf(
        java.time.Instant.ofEpochMilli(item.timestampMs).toString(),
        item.state.name,
        item.dataRegistered?.let { if (it) 1 else 0 } ?: "",
        item.voiceRegistered?.let { if (it) 1 else 0 } ?: "",
        item.searching?.let { if (it) 1 else 0 } ?: "",
        if (item.roaming) 1 else 0,
        item.operatorNumeric ?: "",
        item.operatorAlphaLong ?: "",
        item.simOperator ?: "",
        if (item.manualSelection) 1 else 0,
        item.channelNumber ?: "",
        item.cellBandwidthsKhz.joinToString(";"),
        item.dataNetworkType ?: "",
        item.voiceNetworkType ?: "",
        item.source.name
    ).joinToString(",") { csvEscape(it) }

    /** Exporta los eventos de servicio (del más antiguo al más reciente) a [uri]. */
    fun exportServiceStateCsv(context: Context, uri: Uri, events: List<ServiceStateSnapshot>): Result<Int> =
        runCatching {
            context.contentResolver.openOutputStream(uri).use { outputStream ->
                requireNotNull(outputStream) { "El destino no permitió abrir el archivo" }
                OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.append(SERVICE_STATE_CSV_HEADER).append("\n")
                    events.sortedBy { it.timestampMs }.forEach { writer.append(serviceStateCsvRow(it)).append("\n") }
                    writer.flush()
                    events.size
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
