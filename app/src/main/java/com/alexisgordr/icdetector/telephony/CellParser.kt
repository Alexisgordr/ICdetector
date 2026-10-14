package com.alexisgordr.icdetector.telephony

import android.os.Build
import android.telephony.*
import com.alexisgordr.icdetector.models.CellConnectionState
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.CsgInfo
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import com.alexisgordr.icdetector.core.BandPlan
import com.alexisgordr.icdetector.core.NeighbourPlaceholders
import com.alexisgordr.icdetector.core.RadioChannels
import com.alexisgordr.icdetector.core.TimingAdvanceInput

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


    // 3.0 — PCI, PSC y canales: solo `CellInfo.UNAVAILABLE` (Int.MAX_VALUE) pasa a null
    // (RadioChannels.unavailableToNull). Un valor medido fuera de rango se guarda tal cual: H8 lo
    // juzga. Antes el centinela llegaba como 2147483647 al historial y a H11.
    fun parseCell(info: CellInfo, networkTypeString: String, mcc: String, mnc: String): CellData? {
        val reg = info.isRegistered

        return when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
                val cellMcc = identityOrFallback(id.mccString, reg, mcc)
                val cellMnc = identityOrFallback(id.mncString, reg, mnc)
                val rsrq = info.cellSignalStrength.rsrq
                    .let { if (it == Int.MAX_VALUE) null else it }
                val sinr = info.cellSignalStrength.rssnr
                    .let { if (it == Int.MAX_VALUE) null else it }
                // TA de LTE: índice 0..1282, SOLO del getter. 3.0 — Ya no se raspa toString(): el
                // texto del fabricante no se convierte en LTE_INDEX (la regex aceptaba `data=0` o
                // `delta=0` como TA 0). Sin valor válido del getter, no hay TA.
                val ta = TimingAdvanceInput.lte(info.cellSignalStrength.timingAdvance)
                val extras = extrasLte(id)
                // 3.0 (#11) — En vecinas LTE, TAC 65535 y Cell ID 268435455 son relleno: N/A.
                // 3.0 (#32) — Banda: el EARFCN si está en la tabla; si no, la que declare el módem.
                CellData(reg, networkTypeString, NeighbourPlaceholders.lteCellId(id.ci, reg), cellMnc, NeighbourPlaceholders.lteTac(id.tac, reg), dbm, cellMcc, timingAdvance = ta, timingAdvanceUnit = TimingAdvanceUnit.LTE_INDEX, radioTech = radioTechOf(info), arfcn = RadioChannels.unavailableToNull(id.earfcn), pci = RadioChannels.unavailableToNull(id.pci), rsrq = rsrq, sinr = sinr, band = BandPlan.resolveLteBand(id.earfcn.takeIf { it != Int.MAX_VALUE }, extras.bands))
                    .withRadioContext(connectionOf(info), validBandwidth(id.bandwidth), extras)
            }
            is CellInfoNr -> {
                val id = info.cellIdentity as CellIdentityNr
                val strength = info.cellSignalStrength as CellSignalStrengthNr
                // ssRsrp puede ser Int.MAX_VALUE (no disponible). Si lo es, se intenta getDbm(), pero
                // NO es una medida alternativa garantizada: según la versión de Android puede ser el
                // mismo SS-RSRP o también "no disponible", y en ese caso la entrada se filtra aguas
                // abajo como sin señal utilizable. No se sustituye por CSI-RSRP, que mide otra cosa
                // (ni aquí, ni en el motor, ni en los perfiles).
                val dbm = strength.ssRsrp.let { if (it == Int.MAX_VALUE) strength.dbm else it }
                val cellMcc = identityOrFallback(id.mccString, reg, mcc)
                val cellMnc = identityOrFallback(id.mncString, reg, mnc)
                // TA de NR. Dos rutas, y la unidad viaja con el valor (ver TimingAdvanceUnit):
                //  - API pública getTimingAdvanceMicros() (API 34+) -> NR_RAW. No se deduce distancia
                //    ni se cambia su semántica por el nombre del método.
                //  - Si no da un valor válido, el toString() del fabricante, solo como contexto ->
                //    UNKNOWN, con el nombre del campo delimitado y el número completo.
                var ta: Int? = null
                var taUnit = TimingAdvanceUnit.UNKNOWN
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ta = try { TimingAdvanceInput.nr(strength.timingAdvanceMicros) } catch (_: Exception) { null }
                    if (ta != null) taUnit = TimingAdvanceUnit.NR_RAW
                }
                if (ta == null) {
                    ta = try { TimingAdvanceInput.scrapedNr(info.toString()) } catch (_: Exception) { null }
                }
                val rsrq = strength.ssRsrq
                    .let { if (it == Int.MAX_VALUE) null else it }
                val sinr = strength.ssSinr
                    .let { if (it == Int.MAX_VALUE) null else it }
                CellData(reg, networkTypeString, id.nci.valOrNa(), cellMnc, id.tac.valOrNa(), dbm, cellMcc, timingAdvance = ta, timingAdvanceUnit = taUnit, radioTech = radioTechOf(info), arfcn = RadioChannels.unavailableToNull(id.nrarfcn), pci = RadioChannels.unavailableToNull(id.pci), rsrq = rsrq, sinr = sinr)
                    .withRadioContext(connectionOf(info), null, extrasNr(id))
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
                val cellMcc = identityOrFallback(id.mccString, reg, mcc)
                val cellMnc = identityOrFallback(id.mncString, reg, mnc)
                // Sin Timing Advance: CellSignalStrengthWcdma no lo expone en la API pública.
                CellData(reg, networkTypeString, id.cid.valOrNa(), cellMnc, id.lac.valOrNa(), dbm, cellMcc, radioTech = radioTechOf(info), arfcn = RadioChannels.unavailableToNull(id.uarfcn), pci = RadioChannels.unavailableToNull(id.psc))
                    .withRadioContext(connectionOf(info), null, extrasWcdma(id))
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                val dbm = info.cellSignalStrength.dbm
                val cellMcc = identityOrFallback(id.mccString, reg, mcc)
                val cellMnc = identityOrFallback(id.mncString, reg, mnc)
                // Índice 0..219; centinelas y fuera de rango, sin TA.
                val ta = TimingAdvanceInput.gsm(info.cellSignalStrength.timingAdvance)
                CellData(reg, networkTypeString, id.cid.valOrNa(), cellMnc, id.lac.valOrNa(), dbm, cellMcc, timingAdvance = ta, timingAdvanceUnit = TimingAdvanceUnit.GSM_INDEX, radioTech = radioTechOf(info), arfcn = RadioChannels.unavailableToNull(id.arfcn))
                    .withRadioContext(connectionOf(info), null, extrasGsm(id))
            }
            else -> null
        }
    }

    // ── v2.10.4 — Contexto de radio (solo recolección) ─────────────────────────────────────
    //
    // Todo lo que sigue se lee de la API pública sin root. Los getters de API 30 van detrás de
    // una comprobación de versión: en Android 10 esos campos quedan vacíos, que es lo honesto.
    // Cualquier excepción de un fabricante deja el campo vacío en vez de perder la celda entera.

    /** Campos opcionales de identidad que solo existen desde Android 11 (API 30). */
    private data class IdentityExtras(
        val bands: List<Int> = emptyList(),
        val additionalPlmns: List<String> = emptyList(),
        val csg: CsgInfo? = null
    )

    private fun CellData.withRadioContext(
        connection: CellConnectionState,
        bandwidthKhz: Int?,
        extras: IdentityExtras
    ): CellData = copy(
        connectionState = connection,
        bandwidthKhz = bandwidthKhz,
        bands = extras.bands,
        additionalPlmns = extras.additionalPlmns,
        csg = extras.csg
    )

    private fun connectionOf(info: CellInfo): CellConnectionState = try {
        CellConnectionState.fromAndroid(info.cellConnectionStatus)
    } catch (_: Exception) {
        CellConnectionState.UNKNOWN
    }

    /**
     * MCC/MNC de una celda. Solo la celda registrada hereda el del operador cuando el módem no lo
     * informa. Una vecina sin identidad se queda en "N/A": su MCC/MNC viaja en el SIB1, que el
     * móvil no lee de las vecinas, y rellenarlo con el de nuestra red hacía que H3 y H4 salieran
     * "superadas" sin haber comparado nada (y guardaba ese MCC/MNC inventado en la BD de sitios).
     */
    internal fun identityOrFallback(reported: String?, registered: Boolean, operatorValue: String): String =
        reported ?: if (registered) operatorValue else "N/A"

    /** Ancho de banda en kHz; `CellInfo.UNAVAILABLE` (Int.MAX_VALUE) y valores no positivos son nulos. */
    internal fun validBandwidth(value: Int): Int? = value.takeIf { it in 1 until Int.MAX_VALUE }

    private fun extrasLte(id: CellIdentityLte): IdentityExtras =
        try {
            IdentityExtras(
                bands = id.bands.toList(),
                additionalPlmns = id.additionalPlmns.sorted(),
                csg = id.closedSubscriberGroupInfo?.let { csgOf(it) }
            )
        } catch (_: Exception) { IdentityExtras() }

    private fun extrasNr(id: CellIdentityNr): IdentityExtras =
        try {
            IdentityExtras(bands = id.bands.toList(), additionalPlmns = id.additionalPlmns.sorted())
        } catch (_: Exception) { IdentityExtras() }

    private fun extrasWcdma(id: CellIdentityWcdma): IdentityExtras =
        try {
            IdentityExtras(
                additionalPlmns = id.additionalPlmns.sorted(),
                csg = id.closedSubscriberGroupInfo?.let { csgOf(it) }
            )
        } catch (_: Exception) { IdentityExtras() }

    private fun extrasGsm(id: CellIdentityGsm): IdentityExtras =
        try {
            IdentityExtras(additionalPlmns = id.additionalPlmns.sorted())
        } catch (_: Exception) { IdentityExtras() }

    private fun csgOf(info: ClosedSubscriberGroupInfo): CsgInfo = CsgInfo(
        indicator = info.csgIndicator,
        identity = info.csgIdentity.takeIf { it in 0 until Int.MAX_VALUE },
        homeNodebName = info.homeNodebName.takeIf { it.isNotBlank() }
    )

    private fun Int.valOrNa() = if (this == Int.MAX_VALUE || this == -1) "N/A" else this.toString()
    private fun Long.valOrNa() = if (this == Long.MAX_VALUE || this == -1L) "N/A" else this.toString()
}
