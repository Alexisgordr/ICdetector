package com.alexisgordr.icdetector.storage

import com.alexisgordr.icdetector.core.RadioChannels
import com.alexisgordr.icdetector.models.CellRfStability
import com.alexisgordr.icdetector.models.RadioTech

/**
 * 3.0 — Acumulador de H15 (estabilidad de identidad RF), sin Android. Misma lógica que antes vivía
 * dentro del bucle del cursor de [CellDbHelper.getCellRfStability]; se separa para poder probarla y
 * para que una lectura fallida a medias NUNCA se entregue como completa (ver [readComplete]).
 */
class RfStabilityAccumulator(private val radio: RadioTech, private val recentWindow: Long) {
    private val pciCounts = HashMap<Int, Int>()
    private val arfcnCounts = HashMap<Int, Int>()
    private val recentPciCounts = HashMap<Int, Int>()
    private val recentArfcnCounts = HashMap<Int, Int>()
    // v2.1: mismo recuento de PCI, pero desglosado por portadora (ARFCN). Ver CellRfStability.
    private val pciByArfcn = HashMap<Int, HashMap<Int, Int>>()
    private val recentPciByArfcn = HashMap<Int, HashMap<Int, Int>>()
    private val recentPciEpisodes = HashMap<Int, Int>()
    private val recentPciEpisodesByArfcn = HashMap<Int, HashMap<Int, Int>>()
    private val lastPciByArfcn = HashMap<Int, Int>()
    private val currentEpisodeReachedRecent = HashMap<Int, Boolean>()
    private var lastGlobalPci: Int? = null
    private var currentGlobalEpisodeReachedRecent = false
    private var total = 0

    /** Una fila elegible, en orden ascendente de observación. */
    fun add(rawPci: Int?, rawArfcn: Int?, timestamp: String?, observedAtMs: Long?) {
        total++
        val ts = timestamp ?: ""
        val isRecent = ObservationTime.isAfter(observedAtMs, ts, recentWindow)

        // 3.0 (#22) — Validador por tecnología. Antes `> 0` mezclaba el EARFCN 0 (Banda 1)
        // con las filas sin frecuencia (UNKNOWN_ARFCN), y cualquier PCI hasta 1007 valía en LTE.
        val pci = RadioChannels.physicalIdOrNull(radio, rawPci)
        val arfcn = RadioChannels.channelOrNull(radio, rawArfcn)

        if (pci != null) {
            pciCounts[pci] = (pciCounts[pci] ?: 0) + 1
            if (isRecent) recentPciCounts[pci] = (recentPciCounts[pci] ?: 0) + 1

            // Desglose por portadora. Las filas sin ARFCN (histórico anterior a la
            // migración v6) se agrupan bajo UNKNOWN_ARFCN, así que siguen contando
            // entre ellas y no se mezclan con las que sí tienen portadora conocida.
            val carrier = arfcn ?: CellRfStability.UNKNOWN_ARFCN
            pciByArfcn.getOrPut(carrier) { HashMap() }
                .let { it[pci] = (it[pci] ?: 0) + 1 }
            if (isRecent) {
                recentPciByArfcn.getOrPut(carrier) { HashMap() }
                    .let { it[pci] = (it[pci] ?: 0) + 1 }
            }

            // Episodios, no muestras: varias lecturas consecutivas de un handover son
            // una sola aparición. El mismo PCI solo suma otra vez tras alternar a otro
            // PCI en esa misma portadora.
            val carrierChanged = lastPciByArfcn[carrier] != pci
            if (carrierChanged) {
                lastPciByArfcn[carrier] = pci
                currentEpisodeReachedRecent[carrier] = isRecent
                if (isRecent) recentPciEpisodesByArfcn.getOrPut(carrier) { HashMap() }
                    .let { it[pci] = (it[pci] ?: 0) + 1 }
            } else if (isRecent && currentEpisodeReachedRecent[carrier] != true) {
                currentEpisodeReachedRecent[carrier] = true
                recentPciEpisodesByArfcn.getOrPut(carrier) { HashMap() }
                    .let { it[pci] = (it[pci] ?: 0) + 1 }
            }

            val globalChanged = lastGlobalPci != pci
            if (globalChanged) {
                lastGlobalPci = pci
                currentGlobalEpisodeReachedRecent = isRecent
                if (isRecent) recentPciEpisodes[pci] = (recentPciEpisodes[pci] ?: 0) + 1
            } else if (isRecent && !currentGlobalEpisodeReachedRecent) {
                currentGlobalEpisodeReachedRecent = true
                recentPciEpisodes[pci] = (recentPciEpisodes[pci] ?: 0) + 1
            }
        }

        if (arfcn != null) {
            arfcnCounts[arfcn] = (arfcnCounts[arfcn] ?: 0) + 1
            if (isRecent) recentArfcnCounts[arfcn] = (recentArfcnCounts[arfcn] ?: 0) + 1
        }
    }

    fun result(): CellRfStability = CellRfStability(
        totalObservations = total,
        distinctPci = pciCounts.map { it.key to it.value },
        distinctArfcn = arfcnCounts.map { it.key to it.value },
        recentDistinctPci = recentPciCounts.map { it.key to it.value },
        recentDistinctArfcn = recentArfcnCounts.map { it.key to it.value },
        pciByArfcn = pciByArfcn.mapValues { (_, counts) -> counts.map { it.key to it.value } },
        recentPciByArfcn = recentPciByArfcn.mapValues { (_, counts) -> counts.map { it.key to it.value } },
        recentPciEpisodes = recentPciEpisodes.map { it.key to it.value },
        recentPciEpisodesByArfcn = recentPciEpisodesByArfcn.mapValues { (_, counts) ->
            counts.map { it.key to it.value }
        }
    )

    companion object {
        /**
         * Lee TODAS las filas con [read]; si la lectura lanza en cualquier punto devuelve null, nunca lo
         * acumulado hasta entonces. Null significa "error de lectura" (H15 se abstiene), distinto de
         * un historial insuficiente, que es un resultado completo con pocas observaciones.
         */
        fun readComplete(radio: RadioTech, recentWindow: Long, read: (RfStabilityAccumulator) -> Unit): CellRfStability? = try {
            RfStabilityAccumulator(radio, recentWindow).also(read).result()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
