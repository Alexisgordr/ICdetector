package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.MccAbstention
import com.alexisgordr.icdetector.models.ServiceStateSnapshot

/**
 * 3.0.0-beta5 — H3, consistencia MCC: compara la servidora con la SIM, no con las vecinas.
 *
 * Antes H3 fallaba si alguna vecina tenía un MCC distinto del de la servidora. Eso es justo lo que
 * ocurre cerca de una frontera, y no lo que hace un IMSI catcher: el falso suele anunciar un PLMN
 * que no es el de tu SIM, en un país que no es el suyo, y aislado. Ahora:
 *
 *  - N/A si falta el MCC de la servidora, el de la SIM o el ServiceState (no se vuelve a la
 *    regla antigua: sin esos datos la regla no tiene nada que comparar).
 *  - PASSED si el MCC de la servidora es el de la SIM. Las vecinas no se miran.
 *  - N/A si son distintos y Android declara roaming: es lo esperado en el extranjero.
 *  - N/A si son distintos sin roaming pero alguna vecina comparte el MCC de la servidora: una
 *    red extranjera coherente con su entorno, típica de frontera.
 *  - FAILED si son distintos, sin roaming, y ninguna vecina comparte el MCC de la servidora.
 *
 * Los MCC se comparan como números ("214" y "0214" son el mismo), igual que en
 * [ThreatAnalyzer.sameOperatorOrUnknown]. El MCC de la servidora es el de su identidad de celda;
 * si la celda no lo da, el del PLMN que anuncia el ServiceState. Los puntos (−30) y la LR (9,0)
 * no cambian.
 */
object MccConsistencyRule {

    /** Comienzo del motivo guardado de H3. Las filas anteriores a beta5 llevan solo este texto. */
    const val REASON_PREFIX = "Inconsistencia MCC"

    /** Motivo guardado cuando H3 falla, con los dos MCC comparados. */
    fun failReason(servingMcc: Int, simMcc: Int): String =
        "$REASON_PREFIX: servidora $servingMcc ≠ SIM $simMcc, sin roaming ni vecinas con ese MCC"

    data class Result(
        val status: HeuristicStatus,
        /** Motivo para la fila si [status] es FAILED. */
        val failReason: String? = null,
        /** Por qué se abstuvo si [status] es NOT_EVALUATED. */
        val abstention: MccAbstention? = null
    )

    /** Código MCC/MNC conocido: ni vacío ni "N/A". */
    internal fun known(code: String?): Boolean = !code.isNullOrBlank() && code.trim() != "N/A"

    /** Mismo código como número ("01" = "1"); si alguno no es numérico, como texto. */
    internal fun sameCode(a: String, b: String): Boolean =
        a.trim().toIntOrNull()?.let { it == b.trim().toIntOrNull() } ?: (a.trim() == b.trim())

    /** MCC como número; null si no se conoce o no es numérico. */
    internal fun mccOf(code: String?): Int? = code?.takeIf(::known)?.trim()?.toIntOrNull()

    /** MCC de un PLMN MCC+MNC ("21407" → 214); null si no tiene la forma esperada. */
    internal fun mccOfPlmn(plmn: String?): Int? {
        val p = plmn?.trim().orEmpty()
        if (p.length !in 5..6 || !p.all(Char::isDigit)) return null
        return p.substring(0, 3).toInt()
    }

    fun evaluate(active: CellData, neighbours: List<CellData>, service: ServiceStateSnapshot?): Result {
        if (service == null) return abstain(MccAbstention.NO_SERVICE_STATE)
        val servingMcc = mccOf(active.mcc) ?: mccOfPlmn(service.operatorNumeric)
            ?: return abstain(MccAbstention.NO_SERVING_MCC)
        val simMcc = mccOfPlmn(service.simOperator) ?: return abstain(MccAbstention.NO_SIM)
        if (servingMcc == simMcc) return Result(HeuristicStatus.PASSED)
        if (service.roaming) return abstain(MccAbstention.ROAMING)
        if (neighbours.any { mccOf(it.mcc) == servingMcc }) return abstain(MccAbstention.BORDER)
        return Result(HeuristicStatus.FAILED, failReason = failReason(servingMcc, simMcc))
    }

    private fun abstain(why: MccAbstention) = Result(HeuristicStatus.NOT_EVALUATED, abstention = why)
}
