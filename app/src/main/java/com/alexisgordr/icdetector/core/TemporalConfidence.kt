package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.identityKey
import com.alexisgordr.icdetector.models.SUBTHRESHOLD_PREFIX
import java.util.concurrent.ConcurrentHashMap

/**
 * Confirmación temporal: una sospecha solo se convierte en alarma si se repite en la misma celda
 * durante [confirmationCycles] ciclos consecutivos. Es la principal defensa del proyecto contra
 * los falsos positivos instantáneos (una lectura rara del módem, un handover a medias).
 *
 * Extraída de MiniICService en v2.1. El motivo no es estética de arquitectura: mientras vivía
 * dentro del servicio, como método privado con su estado en campos del servicio, **era imposible
 * escribir un test de extremo a extremo** — y esta clase es precisamente la que decide si suena
 * una alarma. Ahora ScenarioTest puede recorrer secuencias completas de ciclos y comprobar cuántas
 * alarmas produce un escenario benigno, que es la pregunta que de verdad importa.
 *
 * No es thread-safe por diseño más allá del mapa: se llama siempre desde el mismo hilo (el bucle
 * de análisis, en Dispatchers.Main).
 */
class TemporalConfidence(private val confirmationCycles: Int = 3) {

    private val anomalyStreaks = ConcurrentHashMap<String, Int>()
    private var lastStreakKey = ""

    /** Racha actual de la celda (para diagnóstico y tests). */
    fun streakOf(cell: CellData): Int = anomalyStreaks[keyOf(cell)] ?: 0

    /** Olvida todas las rachas. Útil al reiniciar el servicio o entre escenarios de test. */
    fun reset() {
        anomalyStreaks.clear()
        lastStreakKey = ""
    }

    /**
     * Clave por IDENTIDAD COMPLETA. Con el `cellId` pelado, una transición con el mismo CID bajo
     * distinto MNC/TAC/MCC (frontera, roaming, red compartida, multi-SIM) arrastraba la racha de
     * otra celda.
     */
    private fun keyOf(cell: CellData) = cell.identityKey

    fun apply(cell: CellData): CellData {
        val streakKey = keyOf(cell)

        // Si cambia la celda activa, resetear todos los streaks
        if (streakKey != lastStreakKey) {
            anomalyStreaks.clear()
            lastStreakKey = streakKey
        }

        val currentStreak = if (cell.isSuspicious) {
            val newStreak = (anomalyStreaks[streakKey] ?: 0) + 1
            anomalyStreaks[streakKey] = newStreak
            newStreak
        } else {
            anomalyStreaks.remove(streakKey)
            0
        }

        val isConfirmed = cell.isSuspicious && currentStreak >= confirmationCycles

        return cell.copy(
            isSuspicious = isConfirmed,
            suspiciousReason = when {
                isConfirmed -> cell.suspiciousReason
                cell.isSuspicious -> "[$currentStreak/$confirmationCycles ciclos confirmando] ${cell.suspiciousReason}"
                // La celda no llega al umbral de sospecha, pero SÍ falló heurísticas. Antes esto
                // era `null` y el motivo se perdía para siempre: la fila acababa en el historial
                // como "85 / OK". Ahora se conserva marcado como sub-umbral. No cambia nada del
                // comportamiento de alarma (isSuspicious sigue siendo false, no suena nada, no se
                // muestra como amenaza): solo deja de destruirse la evidencia.
                cell.suspiciousReason != null -> "$SUBTHRESHOLD_PREFIX ${cell.suspiciousReason}"
                else -> null
            }
        )
    }
}
