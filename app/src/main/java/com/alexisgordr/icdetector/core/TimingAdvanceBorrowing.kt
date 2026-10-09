package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech

/**
 * 3.0 (#21) — ¿De qué entrada se puede copiar el Timing Advance de la servidora?
 *
 * Un TA es la distancia a UNA antena concreta. El único caso legítimo para copiarlo es que la
 * misma celda aparezca duplicada en la lista (pasa con NSA y con algunos módems) y solo una de
 * las entradas traiga el TA. Antes bastaba con el mismo Cell ID y TAC: no se exigía la misma
 * tecnología ni el mismo emisor físico. LTE y NR pueden compartir números y tienen unidades de TA
 * distintas; la unidad se copiaba, pero el valor era de otro transmisor, y alimentaba H6, que es
 * la penalización más alta del sistema.
 *
 * Ahora la entrada tiene que ser la misma celda en todo lo que se pueda comprobar:
 *  - misma tecnología (conocida);
 *  - mismo Cell ID y TAC; MCC/MNC iguales o no informados en la entrada duplicada;
 *  - al menos un dato físico (PCI o frecuencia) VÁLIDO en ambas y todos los válidos en ambas,
 *    iguales. Se valida con [RadioChannels]: el "no disponible" de Android (Int.MAX_VALUE) o un
 *    valor fuera de rango cuenta como no informado, nunca como coincidencia. Un dato que un
 *    módem omite no se exige, pero sin ninguno válido no hay forma de saber que es el mismo
 *    emisor y se abstiene.
 */
internal object TimingAdvanceBorrowing {

    fun source(active: CellData, entries: List<CellData>): CellData? {
        if (active.timingAdvance != null || active.cellId == "N/A" || active.radioTech == RadioTech.UNKNOWN) return null
        return entries.firstOrNull { c -> !c.isRegistered && isSameTransmitter(active, c) }
    }

    fun isSameTransmitter(active: CellData, other: CellData): Boolean {
        val ta = other.timingAdvance ?: return false
        if (ta < 0) return false
        if (other.radioTech != active.radioTech) return false
        if (other.cellId != active.cellId || other.tac != active.tac) return false
        if (other.mcc != active.mcc && other.mcc != "N/A") return false
        if (other.mnc != active.mnc && other.mnc != "N/A") return false
        val radio = active.radioTech
        val activePci = RadioChannels.physicalIdOrNull(radio, active.pci)
        val otherPci = RadioChannels.physicalIdOrNull(radio, other.pci)
        val activeChannel = RadioChannels.channelOrNull(radio, active.arfcn)
        val otherChannel = RadioChannels.channelOrNull(radio, other.arfcn)
        val pciKnown = activePci != null && otherPci != null
        val channelKnown = activeChannel != null && otherChannel != null
        if (!pciKnown && !channelKnown) return false
        if (pciKnown && activePci != otherPci) return false
        if (channelKnown && activeChannel != otherChannel) return false
        return true
    }
}
