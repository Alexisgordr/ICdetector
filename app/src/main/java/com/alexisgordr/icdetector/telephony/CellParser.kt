package com.alexisgordr.icdetector.telephony

import android.os.Build
import android.telephony.*
import com.alexisgordr.icdetector.models.CellData

object CellParser {

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
                var ta = info.cellSignalStrength.timingAdvance.let { if (it == Int.MAX_VALUE) null else it }
                if (ta == null) {
                    try {
                        val rawString = info.cellSignalStrength.toString()
                        val taMatch = Regex("ta=([0-9]+)").find(rawString)
                        if (taMatch != null) {
                            val extracted = taMatch.groupValues[1].toInt()
                            if (extracted != Int.MAX_VALUE) ta = extracted
                        }
                    } catch (_: Exception) {}
                }
                CellData(reg, networkTypeString, id.ci.valOrNa(), cellMnc, id.tac.valOrNa(), dbm, cellMcc, timingAdvance = ta, arfcn = id.earfcn, pci = id.pci, rsrq = rsrq, sinr = sinr)
            }
            is CellInfoNr -> {
                val id = info.cellIdentity as CellIdentityNr
                val strength = info.cellSignalStrength as CellSignalStrengthNr
                val dbm = strength.ssRsrp
                val cellMcc = id.mccString ?: mcc
                val cellMnc = id.mncString ?: mnc
                var ta: Int? = null
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        val method = strength.javaClass.getMethod("getTimingAdvanceMicros")
                        val res = method.invoke(strength) as Int
                        if ((res != Int.MAX_VALUE) && (res != -1)) ta = res
                    } catch (_: Exception) { }
                }
                if (ta == null) {
                    try {
                        val rawString = info.toString()
                        val taMatch = Regex("ta=([0-9]+)").find(rawString) ?: Regex("timingAdvance=([0-9]+)").find(rawString)
                        if (taMatch != null) {
                            val extracted = taMatch.groupValues[1].toInt()
                            if (extracted != Int.MAX_VALUE) ta = extracted
                        }
                    } catch (_: Exception) {}
                }
                val rsrq = strength.ssRsrq
                    .let { if (it == Int.MAX_VALUE) null else it }
                val sinr = strength.ssSinr
                    .let { if (it == Int.MAX_VALUE) null else it }
                CellData(reg, networkTypeString, id.nci.valOrNa(), cellMnc, id.tac.valOrNa(), dbm, cellMcc, timingAdvance = ta, arfcn = id.nrarfcn, pci = id.pci, rsrq = rsrq, sinr = sinr)
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
                val cellMcc = id.mccString ?: mcc
                val cellMnc = id.mncString ?: mnc
                CellData(reg, networkTypeString, id.cid.valOrNa(), cellMnc, id.lac.valOrNa(), dbm, cellMcc, arfcn = id.uarfcn, pci = id.psc)
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
                val cellMcc = id.mccString ?: mcc
                val cellMnc = id.mncString ?: mnc
                val ta = info.cellSignalStrength.timingAdvance.let { if (it == Int.MAX_VALUE) null else it }
                CellData(reg, networkTypeString, id.cid.valOrNa(), cellMnc, id.lac.valOrNa(), dbm, cellMcc, timingAdvance = ta, arfcn = id.arfcn)
            }
            else -> null
        }
    }

    private fun Int.valOrNa() = if (this == Int.MAX_VALUE || this == -1) "N/A" else this.toString()
    private fun Long.valOrNa() = if (this == Long.MAX_VALUE || this == -1L) "N/A" else this.toString()
}
