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

    // La tecnología se toma de la CLASE de CellInfo y de nada más. Es la única fuente firme: la
    // cadena `networkTypeString` viene de TelephonyDisplayInfo y describe el icono de la barra de
    // estado. Las dos primeras versiones de este mapeo se equivocaron —GSM salía etiquetado como
    // UMTS y WCDMA se quedaba sin tecnología—, y la tecnología forma parte de la identidad de la
    // celda. Hay un test por cada una de las cuatro clases.


    // 3.0 — PCI, PSC y canales: solo `CellInfo.UNAVAILABLE` (Int.MAX_VALUE) pasa a null
    // (RadioChannels.unavailableToNull). Un valor medido fuera de rango se guarda tal cual: H8 lo
    // juzga. Antes el centinela llegaba como 2147483647 al historial y a H11.
    //
    // 3.1.0 (#47) — En dos pasos: leer los getters de `CellInfo` ([LteReading]…[GsmReading], sin
    // ninguna decisión) e interpretarlos ([parse]). Las clases de telefonía no se pueden construir
    // en un test de JVM; las lecturas sí, así que toda la interpretación (centinelas, vecina frente a
    // registrada, unidad del TA, banda) se prueba con fixtures (CellParserFixturesTest).
    fun parseCell(info: CellInfo, networkTypeString: String, mcc: String, mnc: String): CellData? = when (info) {
        is CellInfoLte -> parse(LteReading.of(info), networkTypeString, mcc, mnc)
        is CellInfoNr -> parse(NrReading.of(info), networkTypeString, mcc, mnc)
        is CellInfoWcdma -> parse(WcdmaReading.of(info), networkTypeString, mcc, mnc)
        is CellInfoGsm -> parse(GsmReading.of(info), networkTypeString, mcc, mnc)
        else -> null
    }

    /** Getters de una `CellInfoLte`, tal cual (`CellInfo.UNAVAILABLE` incluido). */
    internal data class LteReading(
        val registered: Boolean, val connection: CellConnectionState,
        val ci: Int, val pci: Int, val tac: Int, val earfcn: Int, val bandwidth: Int,
        val mccString: String?, val mncString: String?,
        val dbm: Int, val rsrq: Int, val rssnr: Int, val timingAdvance: Int,
        val extras: IdentityExtras = IdentityExtras()
    ) {
        companion object {
            fun of(info: CellInfoLte): LteReading {
                val id = info.cellIdentity
                val ss = info.cellSignalStrength
                return LteReading(info.isRegistered, connectionOf(info), id.ci, id.pci, id.tac, id.earfcn, id.bandwidth,
                    id.mccString, id.mncString, ss.dbm, ss.rsrq, ss.rssnr, ss.timingAdvance, extrasLte(id))
            }
        }
    }

    /**
     * Getters de una `CellInfoNr`. [timingAdvanceMicros] es el del getter público (API 34+), null si
     * no existe o falla; [text] es el `toString()` del fabricante, que solo se usa como contexto.
     */
    internal data class NrReading(
        val registered: Boolean, val connection: CellConnectionState,
        val nci: Long, val pci: Int, val tac: Int, val nrarfcn: Int,
        val mccString: String?, val mncString: String?,
        val ssRsrp: Int, val dbm: Int, val ssRsrq: Int, val ssSinr: Int,
        val timingAdvanceMicros: Int?, val text: String,
        val extras: IdentityExtras = IdentityExtras()
    ) {
        companion object {
            fun of(info: CellInfoNr): NrReading {
                val id = info.cellIdentity as CellIdentityNr
                val ss = info.cellSignalStrength as CellSignalStrengthNr
                val micros = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    try { ss.timingAdvanceMicros } catch (_: Exception) { null }
                } else null
                return NrReading(info.isRegistered, connectionOf(info), id.nci, id.pci, id.tac, id.nrarfcn,
                    id.mccString, id.mncString, ss.ssRsrp, ss.dbm, ss.ssRsrq, ss.ssSinr, micros,
                    try { info.toString() } catch (_: Exception) { "" }, extrasNr(id))
            }
        }
    }

    /** Getters de una `CellInfoWcdma`. */
    internal data class WcdmaReading(
        val registered: Boolean, val connection: CellConnectionState,
        val cid: Int, val lac: Int, val psc: Int, val uarfcn: Int,
        val mccString: String?, val mncString: String?, val dbm: Int,
        val extras: IdentityExtras = IdentityExtras()
    ) {
        companion object {
            fun of(info: CellInfoWcdma): WcdmaReading {
                val id = info.cellIdentity
                return WcdmaReading(info.isRegistered, connectionOf(info), id.cid, id.lac, id.psc, id.uarfcn,
                    id.mccString, id.mncString, info.cellSignalStrength.dbm, extrasWcdma(id))
            }
        }
    }

    /** Getters de una `CellInfoGsm`. */
    internal data class GsmReading(
        val registered: Boolean, val connection: CellConnectionState,
        val cid: Int, val lac: Int, val arfcn: Int,
        val mccString: String?, val mncString: String?, val dbm: Int, val timingAdvance: Int,
        val extras: IdentityExtras = IdentityExtras()
    ) {
        companion object {
            fun of(info: CellInfoGsm): GsmReading {
                val id = info.cellIdentity
                return GsmReading(info.isRegistered, connectionOf(info), id.cid, id.lac, id.arfcn,
                    id.mccString, id.mncString, info.cellSignalStrength.dbm, info.cellSignalStrength.timingAdvance, extrasGsm(id))
            }
        }
    }

    internal fun parse(r: LteReading, networkTypeString: String, mcc: String, mnc: String): CellData {
        val reg = r.registered
        val cellMcc = identityOrFallback(r.mccString, reg, mcc)
        val cellMnc = identityOrFallback(r.mncString, reg, mnc)
        val rsrq = r.rsrq.let { if (it == Int.MAX_VALUE) null else it }
        val sinr = r.rssnr.let { if (it == Int.MAX_VALUE) null else it }
        // TA de LTE: índice 0..1282, SOLO del getter. 3.0 — Ya no se raspa toString(): el
        // texto del fabricante no se convierte en LTE_INDEX (la regex aceptaba `data=0` o
        // `delta=0` como TA 0). Sin valor válido del getter, no hay TA.
        val ta = TimingAdvanceInput.lte(r.timingAdvance)
        // 3.0 (#11) — En vecinas LTE, TAC 65535 y Cell ID 268435455 son relleno: N/A.
        // 3.0 (#32) — Banda: el EARFCN si está en la tabla; si no, la que declare el módem.
        return CellData(reg, networkTypeString, NeighbourPlaceholders.lteCellId(r.ci, reg), cellMnc, NeighbourPlaceholders.lteTac(r.tac, reg), r.dbm, cellMcc, timingAdvance = ta, timingAdvanceUnit = TimingAdvanceUnit.LTE_INDEX, radioTech = radioTechForClass(CellInfoLte::class.java), arfcn = RadioChannels.unavailableToNull(r.earfcn), pci = RadioChannels.unavailableToNull(r.pci), rsrq = rsrq, sinr = sinr, band = BandPlan.resolveLteBand(r.earfcn.takeIf { it != Int.MAX_VALUE }, r.extras.bands))
            .withRadioContext(r.connection, validBandwidth(r.bandwidth), r.extras)
    }

    internal fun parse(r: NrReading, networkTypeString: String, mcc: String, mnc: String): CellData {
        val reg = r.registered
        // ssRsrp puede ser Int.MAX_VALUE (no disponible). Si lo es, se intenta getDbm(), pero
        // NO es una medida alternativa garantizada: según la versión de Android puede ser el
        // mismo SS-RSRP o también "no disponible", y en ese caso la entrada se filtra aguas
        // abajo como sin señal utilizable. No se sustituye por CSI-RSRP, que mide otra cosa
        // (ni aquí, ni en el motor, ni en los perfiles).
        val dbm = r.ssRsrp.let { if (it == Int.MAX_VALUE) r.dbm else it }
        val cellMcc = identityOrFallback(r.mccString, reg, mcc)
        val cellMnc = identityOrFallback(r.mncString, reg, mnc)
        // TA de NR. Dos rutas, y la unidad viaja con el valor (ver TimingAdvanceUnit):
        //  - API pública getTimingAdvanceMicros() (API 34+) -> NR_RAW. No se deduce distancia
        //    ni se cambia su semántica por el nombre del método.
        //  - Si no da un valor válido, el toString() del fabricante, solo como contexto ->
        //    UNKNOWN, con el nombre del campo delimitado y el número completo.
        var ta: Int? = r.timingAdvanceMicros?.let { TimingAdvanceInput.nr(it) }
        var taUnit = if (ta != null) TimingAdvanceUnit.NR_RAW else TimingAdvanceUnit.UNKNOWN
        if (ta == null) {
            ta = try { TimingAdvanceInput.scrapedNr(r.text) } catch (_: Exception) { null }
        }
        val rsrq = r.ssRsrq.let { if (it == Int.MAX_VALUE) null else it }
        val sinr = r.ssSinr.let { if (it == Int.MAX_VALUE) null else it }
        return CellData(reg, networkTypeString, r.nci.valOrNa(), cellMnc, r.tac.valOrNa(), dbm, cellMcc, timingAdvance = ta, timingAdvanceUnit = taUnit, radioTech = radioTechForClass(CellInfoNr::class.java), arfcn = RadioChannels.unavailableToNull(r.nrarfcn), pci = RadioChannels.unavailableToNull(r.pci), rsrq = rsrq, sinr = sinr)
            .withRadioContext(r.connection, null, r.extras)
    }

    internal fun parse(r: WcdmaReading, networkTypeString: String, mcc: String, mnc: String): CellData {
        val cellMcc = identityOrFallback(r.mccString, r.registered, mcc)
        val cellMnc = identityOrFallback(r.mncString, r.registered, mnc)
        // Sin Timing Advance: CellSignalStrengthWcdma no lo expone en la API pública.
        return CellData(r.registered, networkTypeString, r.cid.valOrNa(), cellMnc, r.lac.valOrNa(), r.dbm, cellMcc, radioTech = radioTechForClass(CellInfoWcdma::class.java), arfcn = RadioChannels.unavailableToNull(r.uarfcn), pci = RadioChannels.unavailableToNull(r.psc))
            .withRadioContext(r.connection, null, r.extras)
    }

    internal fun parse(r: GsmReading, networkTypeString: String, mcc: String, mnc: String): CellData {
        val cellMcc = identityOrFallback(r.mccString, r.registered, mcc)
        val cellMnc = identityOrFallback(r.mncString, r.registered, mnc)
        // Índice 0..219; centinelas y fuera de rango, sin TA.
        val ta = TimingAdvanceInput.gsm(r.timingAdvance)
        return CellData(r.registered, networkTypeString, r.cid.valOrNa(), cellMnc, r.lac.valOrNa(), r.dbm, cellMcc, timingAdvance = ta, timingAdvanceUnit = TimingAdvanceUnit.GSM_INDEX, radioTech = radioTechForClass(CellInfoGsm::class.java), arfcn = RadioChannels.unavailableToNull(r.arfcn))
            .withRadioContext(r.connection, null, r.extras)
    }

    // ── v2.10.4 — Contexto de radio (solo recolección) ─────────────────────────────────────
    //
    // Todo lo que sigue se lee de la API pública sin root. Los getters de API 30 van detrás de
    // una comprobación de versión: en Android 10 esos campos quedan vacíos, que es lo honesto.
    // Cualquier excepción de un fabricante deja el campo vacío en vez de perder la celda entera.

    /** Campos opcionales de identidad que solo existen desde Android 11 (API 30). */
    internal data class IdentityExtras(
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
