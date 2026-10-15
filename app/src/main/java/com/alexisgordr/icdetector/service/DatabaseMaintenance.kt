package com.alexisgordr.icdetector.service

import android.os.SystemClock
import com.alexisgordr.icdetector.core.MaintenanceReport
import com.alexisgordr.icdetector.storage.CellDbHelper

/** Applies bounded retention independently from the service lifecycle orchestration. */
internal class DatabaseMaintenance(
    private val db: CellDbHelper,
    private val log: (String) -> Unit
) {
    // 3.0 (corte de campaña) — La primera petición del servicio ejecuta ya (ver MaintenanceSchedule).
    private val schedule = com.alexisgordr.icdetector.core.MaintenanceSchedule { SystemClock.elapsedRealtime() }

    fun claimRun(force: Boolean = false): Boolean = schedule.claim(force)

    fun run() {
        // 3.0 — La poda tiene su propio resultado: si falla (la transacción ya se revirtió), se dice
        // una vez en este ciclo diario, sin el mensaje del error, y el tope forense se aplica igual.
        val prune = try {
            db.pruneOldRecords()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            MaintenanceReport.Prune.Failed(e.javaClass.simpleName)
        }
        MaintenanceReport.pruneLine(prune, CellDbHelper.DEFAULT_RETENTION_DAYS)?.let(log)
        try {
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
            log("⚠️ Mantenimiento de base de datos fallido (${e.javaClass.simpleName}).")
        }
    }
}
