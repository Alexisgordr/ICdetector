package com.alexisgordr.icdetector.service

import android.location.Location
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.ServiceStateSnapshot
import com.alexisgordr.icdetector.models.toCompactString
import com.alexisgordr.icdetector.storage.CellDbHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Owns routine, handover and confirmed-alarm writes to cellular history. */
internal class ObservationPersistenceController(
    private val scope: CoroutineScope,
    private val db: CellDbHelper,
    private val location: () -> Location?,
    /** v2.10.4 — Último ServiceState conocido, guardado junto a cada fila como contexto. */
    private val serviceState: () -> ServiceStateSnapshot? = { null },
    private val onWrite: (Long) -> Unit,
    private val onPeriodicMissingLocation: (Long) -> Unit
) {
    private var lastPeriodicWrite = 0L

    fun recordHandover(cell: CellData) {
        record(cell)
        lastPeriodicWrite = System.currentTimeMillis()
    }

    fun recordConfirmedAlarm(cell: CellData) =
        record(cell, cell.suspiciousReason ?: "ALARMA CONFIRMADA")

    fun recordPeriodicIfDue(cell: CellData, screenOn: Boolean) {
        if (!isRecordable(cell)) return
        val now = System.currentTimeMillis()
        val interval = if (screenOn) SCREEN_ON_INTERVAL_MS else SCREEN_OFF_INTERVAL_MS
        if (now - lastPeriodicWrite < interval) return
        lastPeriodicWrite = now

        scope.launch(Dispatchers.IO) {
            val fix = location()
            val rowId = insert(cell, cell.suspiciousReason ?: "OK", fix)
            onWrite(rowId)
            if (rowId != -1L && fix == null && !screenOn) onPeriodicMissingLocation(now)
        }
    }

    private fun record(cell: CellData, reason: String = cell.suspiciousReason ?: "OK") {
        if (!isRecordable(cell)) return
        scope.launch(Dispatchers.IO) {
            onWrite(insert(cell, reason, location()))
        }
    }

    private fun insert(cell: CellData, reason: String, fix: Location?): Long {
        val service = serviceState()
        return db.logConnection(
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
        networkRoaming = service?.roaming
        )
    }

    private fun isRecordable(cell: CellData): Boolean =
        cell.cellId != "N/A" && cell.dbm != -999 && cell.dbm != Int.MAX_VALUE

    private companion object {
        const val SCREEN_ON_INTERVAL_MS = 5L * 60L * 1000L
        const val SCREEN_OFF_INTERVAL_MS = 15L * 60L * 1000L
    }
}
