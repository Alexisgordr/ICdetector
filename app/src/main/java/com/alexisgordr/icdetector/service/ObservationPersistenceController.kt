package com.alexisgordr.icdetector.service

import android.location.Location
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.ServiceStateSnapshot
import com.alexisgordr.icdetector.models.toCompactString
import com.alexisgordr.icdetector.storage.CellDbHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns routine, handover and confirmed-alarm writes to cellular history.
 *
 * v2.10.10 — Cada fila se escribe con una instantánea tomada al OBSERVAR: celda, posición GPS,
 * estado de servicio y hora. Antes la posición, el estado y la hora se leían al ejecutar la
 * escritura en segundo plano, y si esta se retrasaba una fila de la celda A podía llevar el
 * contexto del momento B. Las escrituras van además por una cola de un solo hilo, en orden, y
 * se descartan las que se programaron antes de un "Borrar historial".
 */
internal class ObservationPersistenceController(
    private val scope: CoroutineScope,
    private val db: CellDbHelper,
    private val location: () -> Location?,
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

    /** Todo lo que describe la observación, fijado en el momento de observarla. */
    private class Snapshot(
        val cell: CellData,
        val reason: String,
        val fix: Location?,
        val service: ServiceStateSnapshot?,
        val observedAtMs: Long,
        val generation: Long
    )

    private fun snapshot(cell: CellData, reason: String) = Snapshot(
        cell = cell,
        reason = reason,
        fix = location(),
        service = serviceState(),
        observedAtMs = clock(),
        generation = historyGeneration()
    )

    /** Escribe en orden; null si la fila pertenece a un historial que ya se borró. */
    private fun write(snapshot: Snapshot): Long? =
        if (snapshot.generation != historyGeneration()) null else insert(snapshot)

    fun recordHandover(cell: CellData) {
        record(cell)
        lastPeriodicWrite = System.currentTimeMillis()
    }

    fun recordConfirmedAlarm(cell: CellData) =
        record(cell, cell.suspiciousReason ?: "ALARMA CONFIRMADA")

    fun recordPeriodicIfDue(cell: CellData, screenOn: Boolean) {
        if (!isRecordable(cell)) return
        val now = clock()
        val interval = if (screenOn) SCREEN_ON_INTERVAL_MS else SCREEN_OFF_INTERVAL_MS
        if (now - lastPeriodicWrite < interval) return
        lastPeriodicWrite = now

        val snapshot = snapshot(cell, cell.suspiciousReason ?: "OK")
        scope.launch(writeDispatcher) {
            val rowId = write(snapshot) ?: return@launch
            onWrite(rowId)
            if (rowId != -1L && snapshot.fix == null && !screenOn) onPeriodicMissingLocation(snapshot.observedAtMs)
        }
    }

    private fun record(cell: CellData, reason: String = cell.suspiciousReason ?: "OK") {
        if (!isRecordable(cell)) return
        val snapshot = snapshot(cell, reason)
        scope.launch(writeDispatcher) {
            write(snapshot)?.let(onWrite)
        }
    }

    private fun insert(snapshot: Snapshot): Long {
        val cell = snapshot.cell
        val fix = snapshot.fix
        val service = snapshot.service
        val reason = snapshot.reason
        return db.logConnection(
        observedAtMs = snapshot.observedAtMs,
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
        gpsAccuracyM = fix?.takeIf { it.hasAccuracy() }?.accuracy
        )
    }

    private fun isRecordable(cell: CellData): Boolean =
        cell.cellId != "N/A" && cell.dbm != -999 && cell.dbm != Int.MAX_VALUE

    private companion object {
        const val SCREEN_ON_INTERVAL_MS = 5L * 60L * 1000L
        const val SCREEN_OFF_INTERVAL_MS = 15L * 60L * 1000L
    }
}
