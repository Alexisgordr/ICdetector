package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech

/**
 * 3.1.0 (#37) — Contexto de banda con el que H14 compara la celda siguiente: banda LTE (y la
 * estación base en la que se midió) o NR-ARFCN de la última servidora publicada.
 *
 * Antes se sobrescribía en cada ciclo con lo que traía la lectura. Muchos módems publican a veces la
 * servidora sin ARFCN; esa lectura borraba la banda ya conocida de la MISMA celda, y si el handover
 * llegaba justo después H14 quedaba en N/A sin motivo. Ahora, mientras la identidad completa no
 * cambia (misma regla de handover que H10, [ServingIdentityChange]), una lectura sin canal conserva
 * la banda conocida y la estación en la que se midió. En un handover el contexto pasa a ser el de la
 * celda nueva, aunque llegue sin canal: la banda de la anterior ya no describe a la servidora. Tras
 * una pérdida de servicio o un hueco el servicio lo descarta entero ([CoverageContinuity]).
 */
internal data class BandContext(
    val identity: ServingIdentityChange.Identity,
    val lteBand: Int?,
    /** [LteSite.key] de la celda en la que se midió [lteBand]. */
    val site: String?,
    val nrArfcn: Int?
) {
    companion object {
        fun next(previous: BandContext?, current: CellData): BandContext {
            val identity = ServingIdentityChange.of(current)
            val band = if (current.radioTech == RadioTech.LTE) {
                current.band ?: current.arfcn?.let { BandPlan.earfcnToBandLte(it) }
            } else null
            val nr = current.arfcn.takeIf { current.radioTech == RadioTech.NR }
            val site = LteSite.key(current)
            val step = ServingIdentityChange.step(previous?.identity, identity)
            if (previous == null || step.handover) return BandContext(identity, band, site.takeIf { band != null }, nr)
            // Misma celda: lo que trae la lectura manda; lo que le falta, se conserva.
            return BandContext(
                identity = step.next ?: previous.identity,
                lteBand = band ?: previous.lteBand,
                site = if (band != null) site else previous.site,
                nrArfcn = nr ?: previous.nrArfcn
            )
        }
    }
}
