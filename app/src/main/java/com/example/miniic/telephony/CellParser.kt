package com.example.miniic.telephony

import android.os.Build
import android.telephony.*
import com.example.miniic.models.CellData

object CellParser {

    fun parseCell(info: CellInfo, networkTypeString: String, mcc: String, mnc: String): CellData? {
        val reg = info.isRegistered

        return when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
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
                CellData(reg, networkTypeString, id.ci.valOrNa(), mnc, id.tac.valOrNa(), dbm, mcc, timingAdvance = ta, arfcn = id.earfcn)
            }
            is CellInfoNr -> {
                val id = info.cellIdentity as CellIdentityNr
                val strength = info.cellSignalStrength as CellSignalStrengthNr
                val dbm = strength.ssRsrp
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
                CellData(reg, networkTypeString, id.nci.valOrNa(), mnc, id.tac.valOrNa(), dbm, mcc, timingAdvance = ta, arfcn = id.nrarfcn)
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
                CellData(reg, networkTypeString, id.cid.valOrNa(), mnc, id.lac.valOrNa(), dbm, mcc, arfcn = id.uarfcn)
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
                val ta = info.cellSignalStrength.timingAdvance.let { if (it == Int.MAX_VALUE) null else it }
                CellData(reg, networkTypeString, id.cid.valOrNa(), mnc, id.lac.valOrNa(), dbm, mcc, timingAdvance = ta, arfcn = id.arfcn)
            }
            else -> null
        }
    }

    private fun Int.valOrNa() = if (this == Int.MAX_VALUE || this == -1) "N/A" else this.toString()
    private fun Long.valOrNa() = if (this == Long.MAX_VALUE || this == -1L) "N/A" else this.toString()
}
