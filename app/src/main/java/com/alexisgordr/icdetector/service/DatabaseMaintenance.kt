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
            // v2.8.0 — El tope forense se aplica por casos completos, no por muestras sueltas: un
            // paquete mutilado se exporta como si estuviera íntegro. Ver enforceForensicSampleCap.
            val pruned = db.enforceForensicSampleCap()
            if (pruned.casesDeleted > 0) {
                log(
                    "Poda forense: ${pruned.casesDeleted} caso(s) cerrado(s) eliminados enteros " +
                        "(${pruned.samplesDeleted} muestras) para respetar el tope de " +
                        "${CellDbHelper.MAX_FORENSIC_SAMPLES}. Restantes: ${pruned.remainingSamples}."
                )
            }
            if (pruned.overCapacity) {
                log(
                    "⚠️ Tope forense superado (${pruned.remainingSamples} muestras) con solo casos " +
                        "abiertos. No se recortan: una captura en curso no se mutila."
                )
            }
        } catch (e: Exception) {
            log("⚠️ Mantenimiento de base de datos fallido: ${e.message}")
        }
    }

    private companion object {
        const val INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
