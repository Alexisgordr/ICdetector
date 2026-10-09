package com.alexisgordr.icdetector.service

import com.alexisgordr.icdetector.core.ObservationContext
import com.alexisgordr.icdetector.core.ObservedFix
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.ServiceStateSnapshot
import com.alexisgordr.icdetector.models.toCompactString
import com.alexisgordr.icdetector.storage.CellDbHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Una fila del historial: la celda analizada, el motivo y el contexto de cuando se observó. */
internal class ObservationRow(val cell: CellData, val reason: String, val context: ObservationContext)

/**
 * Owns routine, handover and confirmed-alarm writes to cellular history.
 *
 * v2.10.10 — Cada fila se escribe con una instantánea tomada al OBSERVAR: celda, posición GPS,
 * estado de servicio y hora. Antes la posición, el estado y la hora se leían al ejecutar la
 * escritura en segundo plano, y si esta se retrasaba una fila de la celda A podía llevar el
 * contexto del momento B. Las escrituras van además por una cola de un solo hilo, en orden, y
 * se descartan las que se programaron antes de un "Borrar historial".
 *
 * 3.0 (A03) — El contexto ya no se toma al guardar (después del análisis) sino al RECIBIR la
 * lectura de celdas: [capture] se llama en la entrega y su [ObservationContext] viaja con el
 * ciclo hasta aquí. Un análisis lento ya no puede asociar una lectura anterior a la hora, la
 * posición o el estado de servicio de un momento posterior.
 */
internal class ObservationPersistenceController(
    private val scope: CoroutineScope,
    /** Escribe la fila y devuelve su rowId (-1 si falló). En la app, [logObservation]. */
    private val insert: (ObservationRow) -> Long,
    private val location: () -> ObservedFix?,
    /** v2.10.4 — Último ServiceState conocido, guardado junto a cada fila como contexto. */
    private val serviceState: () -> ServiceStateSnapshot? = { null },
    private val onWrite: (Long) -> Unit,
    private val onPeriodicMissingLocation: (Long) -> Unit,
    /** Cola de un solo hilo: las filas se guardan en el orden en que se observaron. */
    private val writeDispatcher: CoroutineDispatcher,
    /** Cambia con cada "Borrar historial": una escritura de antes del borrado no se guarda. */
    private val historyGeneration: () -> Long = { 0L },
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var lastPeriodicWrite = 0L

    /** Lo que se escribe, con la generación del historial en la que se programó. */
    private class Pending(val row: ObservationRow, val generation: Long)

    /**
     * 3.0 (A03) — Contexto de la observación que acaba de llegar. Se llama al recibir la lectura,
     * antes de analizarla. [fix] es la posición que use el análisis; si no se pasa, la actual.
     */
    fun capture(fix: ObservedFix? = location()) = ObservationContext(
        observedAtMs = clock(),
        fix = fix,
        service = serviceState()
    )

    private fun pending(cell: CellData, reason: String, context: ObservationContext) =
        Pending(ObservationRow(cell, reason, context), historyGeneration())

    /** Escribe en orden; null si la fila pertenece a un historial que ya se borró. */
    private fun write(pending: Pending): Long? =
        if (pending.generation != historyGeneration()) null else insert(pending.row)

    fun recordHandover(cell: CellData, context: ObservationContext) {
        record(cell, context)
        lastPeriodicWrite = context.observedAtMs
    }

    fun recordConfirmedAlarm(cell: CellData, context: ObservationContext) =
        record(cell, context, cell.suspiciousReason ?: "ALARMA CONFIRMADA")

    fun recordPeriodicIfDue(cell: CellData, screenOn: Boolean, context: ObservationContext) {
        if (!isRecordable(cell)) return
        val now = context.observedAtMs
        val interval = if (screenOn) SCREEN_ON_INTERVAL_MS else SCREEN_OFF_INTERVAL_MS
        if (now - lastPeriodicWrite < interval) return
        lastPeriodicWrite = now

        val pending = pending(cell, cell.suspiciousReason ?: "OK", context)
        scope.launch(writeDispatcher) {
            val rowId = write(pending) ?: return@launch
            onWrite(rowId)
            if (rowId != -1L && context.fix == null && !screenOn) onPeriodicMissingLocation(context.observedAtMs)
        }
    }

    private fun record(cell: CellData, context: ObservationContext, reason: String = cell.suspiciousReason ?: "OK") {
        if (!isRecordable(cell)) return
        val pending = pending(cell, reason, context)
        scope.launch(writeDispatcher) {
            write(pending)?.let(onWrite)
        }
    }

    private fun isRecordable(cell: CellData): Boolean =
        cell.cellId != "N/A" && cell.dbm != -999 && cell.dbm != Int.MAX_VALUE

    private companion object {
        const val SCREEN_ON_INTERVAL_MS = 5L * 60L * 1000L
        const val SCREEN_OFF_INTERVAL_MS = 15L * 60L * 1000L
    }
}

/** Guarda una fila del historial con su contexto de observación y la versión de la app (#30). */
internal fun CellDbHelper.logObservation(row: ObservationRow, appVersion: String?): Long {
    val cell = row.cell
    val fix = row.context.fix
    val service = row.context.service
    val reason = row.reason
    return logConnection(
        observedAtMs = row.context.observedAtMs,
        netType = cell.networkType,
        cid = cell.cellId,
        mnc = cell.mnc,
        tac = cell.tac,
        mcc = cell.mcc,
        dbm = cell.dbm,
        verified = cell.verified,
        score = cell.securityScore,
        failedHeuristics = reason,
        lat = fix?.latitude,
        lon = fix?.longitude,
        pci = cell.pci,
        arfcn = cell.arfcn,
        rsrq = cell.rsrq,
        sinr = cell.sinr,
        anomalyConfidence = cell.anomalyConfidence,
        timingAdvance = cell.timingAdvance,
        timingAdvanceUnit = cell.timingAdvanceUnit,
        radio = cell.radioTech,
        connectionState = cell.connectionState,
        bandwidthKhz = cell.bandwidthKhz,
        bands = cell.bands.joinToString(";").ifEmpty { null },
        additionalPlmns = cell.additionalPlmns.joinToString(";").ifEmpty { null },
        csg = cell.csg,
        secondaryCarriers = cell.secondaryCarriers.toCompactString().ifEmpty { null },
        serviceState = service?.state,
        networkOperator = service?.operatorNumeric,
        simOperator = service?.simOperator,
        networkRoaming = service?.roaming,
        notEvaluatedHeuristics = cell.heuristicReport.notEvaluatedIds(),
        gpsAccuracyM = fix?.accuracyM,
        appVersion = appVersion
    )
}
