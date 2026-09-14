package com.alexisgordr.icdetector.telephony

import android.os.Build
import android.telephony.*
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import com.alexisgordr.icdetector.core.BandPlan

object CellParser {

    /**
     * Tecnología de radio a partir de la CLASE de `CellInfo`.
     *
     * Se trabaja con la clase y no con la instancia para que pueda probarse sin fabricar objetos de
     * telefonía, que en un test de JVM no se pueden construir. El mapeo vive aquí, una sola vez, y
     * cada rama del parser lo usa: mientras estuvo escrito a mano rama por rama, dos de las cuatro
     * estaban mal —GSM etiquetado como UMTS y WCDMA sin tecnología— sin que nada lo señalara.
     */
    internal fun radioTechForClass(clazz: Class<*>): RadioTech = when {
        CellInfoLte::class.java.isAssignableFrom(clazz) -> RadioTech.LTE
        CellInfoNr::class.java.isAssignableFrom(clazz) -> RadioTech.NR
        CellInfoWcdma::class.java.isAssignableFrom(clazz) -> RadioTech.UMTS
        CellInfoGsm::class.java.isAssignableFrom(clazz) -> RadioTech.GSM
        else -> RadioTech.UNKNOWN
    }

    /** [radioTechForClass] para una lectura concreta. */
    internal fun radioTechOf(info: CellInfo): RadioTech = radioTechForClass(info.javaClass)

    // La tecnología se toma de la CLASE de CellInfo y de nada más. Es la única fuente firme: la
    // cadena `networkTypeString` viene de TelephonyDisplayInfo y describe el icono de la barra de
    // estado. Las dos primeras versiones de este mapeo se equivocaron —GSM salía etiquetado como
    // UMTS y WCDMA se quedaba sin tecnología—, que es justo el error que manda una consulta GSM a
    // OpenCellID pidiendo radio=UMTS. Hay un test por cada una de las cuatro clases.


    fun parseCell(info: CellInfo, networkTypeString: String, mcc: String, mnc: String): CellData? {
        val reg = info.isRegistered

        return when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
                val cellMcc = id.mccString ?: mcc
                val cellMnc = id.mncString ?: mnc
                val rsrq = info.cellSignalStrength.rsrq
                    .let { if (it == Int.MAX_VALUE) null else it }
                val sinr = info.cellSignalStrength.rssnr
                    .let { if (it == Int.MAX_VALUE) null else it }
                // TA de LTE: SIEMPRE un índice (1 unidad ≈ 78 m), tanto por el getter como por el
                // raspado de reserva, que lee el mismo campo impreso de CellSignalStrengthLte.
                var ta = info.cellSignalStrength.timingAdvance.let { if (it == Int.MAX_VALUE) null else it }
                if (ta == null) {
                    try {
                        val rawString = info.cellSignalStrength.toString()
                        val taMatch = TA_REGEX.find(rawString)
                        if (taMatch != null) {
                            val extracted = taMatch.groupValues[1].toIntOrNull()
                            if (extracted != null && extracted != Int.MAX_VALUE) ta = extracted
                        }
                    } catch (_: Exception) {}
                }
                CellData(reg, networkTypeString, id.ci.valOrNa(), cellMnc, id.tac.valOrNa(), dbm, cellMcc, timingAdvance = ta, timingAdvanceUnit = TimingAdvanceUnit.LTE_INDEX, radioTech = radioTechOf(info), arfcn = id.earfcn, pci = id.pci, rsrq = rsrq, sinr = sinr, band = BandPlan.earfcnToBandLte(id.earfcn))
            }
            is CellInfoNr -> {
                val id = info.cellIdentity as CellIdentityNr
                val strength = info.cellSignalStrength as CellSignalStrengthNr
                // ssRsrp puede ser Int.MAX_VALUE (no disponible). Si lo es, caemos al dbm
                // normalizado del framework para no inyectar un valor absurdo (2147483647)
                // que dispararía en falso todas las heurísticas de potencia.
                val dbm = strength.ssRsrp.let { if (it == Int.MAX_VALUE) strength.dbm else it }
                val cellMcc = id.mccString ?: mcc
                val cellMnc = id.mncString ?: mnc
                // TA de NR. Dos rutas con unidades DISTINTAS, y por eso la unidad viaja con el
                // valor (ver TimingAdvanceUnit):
                //  - getTimingAdvanceMicros() devuelve microsegundos de ida y vuelta -> NR_RAW.
                //  - el raspado de toString() devuelve lo que imprima el fabricante, que puede ser
                //    un índice o microsegundos. No es determinable -> UNKNOWN, y H6 se abstiene de
                //    deducir distancia. Se conserva el valor porque sigue sirviendo para mostrarlo,
                //    pero no se usa para geometría: es preferible no juzgar a juzgar con la unidad
                //    equivocada, que en el peor caso erraba por un factor cercano a 2.
                var ta: Int? = null
                var taUnit = TimingAdvanceUnit.UNKNOWN
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        val method = strength.javaClass.getMethod("getTimingAdvanceMicros")
                        val res = method.invoke(strength) as Int
                        if ((res != Int.MAX_VALUE) && (res != -1)) {
                            ta = res
                            taUnit = TimingAdvanceUnit.NR_RAW
                        }
                    } catch (_: Exception) { }
                }
                if (ta == null) {
                    try {
                        val rawString = info.toString()
                        val taMatch = TA_REGEX.find(rawString) ?: TA_LONG_REGEX.find(rawString)
                        if (taMatch != null) {
                            val extracted = taMatch.groupValues[1].toIntOrNull()
                            if (extracted != null && extracted != Int.MAX_VALUE) {
                                ta = extracted
                                taUnit = TimingAdvanceUnit.UNKNOWN
                            }
                        }
                    } catch (_: Exception) {}
                }
                val rsrq = strength.ssRsrq
                    .let { if (it == Int.MAX_VALUE) null else it }
                val sinr = strength.ssSinr
                    .let { if (it == Int.MAX_VALUE) null else it }
                CellData(reg, networkTypeString, id.nci.valOrNa(), cellMnc, id.tac.valOrNa(), dbm, cellMcc, timingAdvance = ta, timingAdvanceUnit = taUnit, radioTech = radioTechOf(info), arfcn = id.nrarfcn, pci = id.pci, rsrq = rsrq, sinr = sinr)
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
                val cellMcc = id.mccString ?: mcc
                val cellMnc = id.mncString ?: mnc
                // Sin Timing Advance: CellSignalStrengthWcdma no lo expone en la API pública.
                CellData(reg, networkTypeString, id.cid.valOrNa(), cellMnc, id.lac.valOrNa(), dbm, cellMcc, radioTech = radioTechOf(info), arfcn = id.uarfcn, pci = id.psc)
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
                val cellMcc = id.mccString ?: mcc
                val cellMnc = id.mncString ?: mnc
                val ta = info.cellSignalStrength.timingAdvance.let { if (it == Int.MAX_VALUE) null else it }
                CellData(reg, networkTypeString, id.cid.valOrNa(), cellMnc, id.lac.valOrNa(), dbm, cellMcc, timingAdvance = ta, timingAdvanceUnit = TimingAdvanceUnit.GSM_INDEX, radioTech = radioTechOf(info), arfcn = id.arfcn)
            }
            else -> null
        }
    }

    // Regex compiladas una sola vez (antes se construían en cada lectura de cada celda, varias
    // veces por segundo en zonas de transición).
    private val TA_REGEX = Regex("ta=([0-9]+)")
    private val TA_LONG_REGEX = Regex("timingAdvance=([0-9]+)")

    private fun Int.valOrNa() = if (this == Int.MAX_VALUE || this == -1) "N/A" else this.toString()
    private fun Long.valOrNa() = if (this == Long.MAX_VALUE || this == -1L) "N/A" else this.toString()
}
