package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech

/**
 * 3.0 (#19) — Qué es un handover para la app: cambia la identidad completa de la celda
 * servidora (MCC, MNC, TAC, Cell ID o tecnología), no solo el número de celda.
 *
 * Antes se comparaba solo el Cell ID. Dos celdas con el mismo número en otro operador, otra área
 * de seguimiento o en LTE/NR se trataban como la misma: no se escribía la fila del handover, no
 * se reiniciaba el estado de la celda anterior y H10 no contaba ese cambio.
 *
 * Esta misma definición la usan la fila de handover, el reinicio de episodios y H10: no hay una
 * versión más estrecha en ningún sitio.
 *
 * Un campo que uno de los dos lados no conoce (`N/A`, vacío, tecnología desconocida) NO cuenta
 * como cambio: algunos módems dejan de rellenar el MCC/MNC durante una lectura, y contarlo como
 * handover inventaría cambios de celda (y fallos de H10) que no ocurrieron.
 *
 * 3.0 — El Cell ID sigue la misma regla. Antes se comparaba siempre: un CID «N/A» transitorio daba
 * dos handovers falsos (X→N/A y N/A→X), dos entradas para H10, una ventana GPS nueva y, sobre todo,
 * reiniciaba el episodio de alarma en curso. Ahora una lectura con el CID ilegible nunca abre un
 * handover, y [step] completa los campos desconocidos de la identidad anterior con los que llegan
 * después (sin eso, una primera lectura con CID «N/A» bloquearía la detección).
 */
internal object ServingIdentityChange {

    data class Identity(
        val mcc: String,
        val mnc: String,
        val tac: String,
        val cellId: String,
        val radio: RadioTech
    ) {
        /** Clave con la que se guarda el cambio (misma forma que `identityKey`). */
        val key: String get() = "$mcc-$mnc-$tac-$cellId-${radio.name}"
    }

    fun of(cell: CellData) = Identity(cell.mcc, cell.mnc, cell.tac, cell.cellId, cell.radioTech)

    fun isHandover(previous: Identity?, current: Identity): Boolean {
        if (!known(current.cellId)) return false
        if (previous == null) return true
        return knownAndDifferent(previous.cellId, current.cellId) ||
            knownAndDifferent(previous.mcc, current.mcc) ||
            knownAndDifferent(previous.mnc, current.mnc) ||
            knownAndDifferent(previous.tac, current.tac) ||
            (previous.radio != current.radio &&
                previous.radio != RadioTech.UNKNOWN && current.radio != RadioTech.UNKNOWN)
    }

    /** Resultado de un ciclo: si hubo handover y la identidad que queda como «anterior». */
    data class Step(val handover: Boolean, val next: Identity?)

    /**
     * Con handover, la actual pasa a ser la anterior. Sin él, la anterior se conserva y solo se
     * rellenan sus campos desconocidos con los conocidos de la lectura actual; un valor «N/A»
     * nunca sustituye a uno conocido. Una lectura con el CID ilegible no rellena nada: no se sabe
     * si es la misma celda, y adoptar su TAC podría convertir la siguiente lectura en un handover.
     */
    fun step(previous: Identity?, current: Identity): Step {
        if (isHandover(previous, current)) return Step(true, current)
        if (previous == null || !hasReadableCellId(current)) return Step(false, previous)
        fun pick(old: String, new: String) = if (known(old)) old else new
        return Step(false, Identity(
            mcc = pick(previous.mcc, current.mcc), mnc = pick(previous.mnc, current.mnc),
            tac = pick(previous.tac, current.tac), cellId = pick(previous.cellId, current.cellId),
            radio = if (previous.radio != RadioTech.UNKNOWN) previous.radio else current.radio
        ))
    }

    /** ¿La lectura trae un Cell ID utilizable? Sin él no se le atribuye nada a ninguna celda. */
    fun hasReadableCellId(identity: Identity): Boolean = known(identity.cellId)

    private fun known(value: String) = value.isNotBlank() && value != "N/A"

    private fun knownAndDifferent(a: String, b: String) = known(a) && known(b) && a != b
}
