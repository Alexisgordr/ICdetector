package com.alexisgordr.icdetector.service

import android.os.SystemClock
import com.alexisgordr.icdetector.storage.CellDbHelper

/** Applies bounded retention independently from the service lifecycle orchestration. */
internal class DatabaseMaintenance(
    private val db: CellDbHelper,
    private val log: (String) -> Unit
) {
    private var lastRunElapsedMs = 0L

    @Synchronized
    fun claimRun(force: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRunElapsedMs < INTERVAL_MS) return false
        lastRunElapsedMs = now
        return true
    }

    fun run() {
        try {
            val deleted = db.pruneOldRecords()
            if (deleted > 0) {
                log("Poda de histórico: $deleted registros de más de ${CellDbHelper.DEFAULT_RETENTION_DAYS} días eliminados.")
            }
            val trimmed = db.enforceForensicSampleCap()
            if (trimmed > 0) {
                log("Muestras forenses recortadas al tope de ${CellDbHelper.MAX_FORENSIC_SAMPLES}: $trimmed eliminadas.")
            }
        } catch (e: Exception) {
            log("⚠️ Mantenimiento de base de datos fallido: ${e.message}")
        }
    }

    private companion object {
        const val INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
