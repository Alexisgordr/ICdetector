package com.alexisgordr.icdetector.telephony

import com.alexisgordr.icdetector.models.CellConnectionState
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.TimingAdvanceUnit
import com.alexisgordr.icdetector.telephony.CellParser.GsmReading
import com.alexisgordr.icdetector.telephony.CellParser.IdentityExtras
import com.alexisgordr.icdetector.telephony.CellParser.LteReading
import com.alexisgordr.icdetector.telephony.CellParser.NrReading
import com.alexisgordr.icdetector.telephony.CellParser.WcdmaReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 3.1.0 (#47) — CellParser con fixtures de CellInfo: lo que devuelven los getters de cada clase
 * (LTE, NR, WCDMA, GSM), en una celda registrada y en una vecina, con los centinelas
 * `CellInfo.UNAVAILABLE` (Int.MAX_VALUE) y el TA de NR de `getTimingAdvanceMicros()`. Las clases de
 * telefonía no se pueden construir en la JVM; `parseCell` las lee en una [LteReading]… y las
 * interpreta con `parse`, que es lo que se prueba aquí.
 */
class CellParserFixturesTest {
    private val U = Int.MAX_VALUE                    // CellInfo.UNAVAILABLE
    private val OP_MCC = "214"
    private val OP_MNC = "07"

    // ── LTE ─────────────────────────────────────────────────────────────────────────────────

    private fun lte(registered: Boolean = true, ci: Int = 79360544, pci: Int = 312, tac: Int = 2801, earfcn: Int = 3350,
                    bandwidth: Int = 20_000, mcc: String? = "214", mnc: String? = "07", dbm: Int = -95, rsrq: Int = -11,
                    rssnr: Int = 12, ta: Int = 4, extras: IdentityExtras = IdentityExtras()) =
        LteReading(registered, if (registered) CellConnectionState.PRIMARY_SERVING else CellConnectionState.NONE,
            ci, pci, tac, earfcn, bandwidth, mcc, mnc, dbm, rsrq, rssnr, ta, extras)

    @Test fun `LTE registered cell`() {
        val c = CellParser.parse(lte(), "4G LTE", OP_MCC, OP_MNC)
        assertEquals(true, c.isRegistered)
        assertEquals(RadioTech.LTE, c.radioTech)
        assertEquals("79360544", c.cellId); assertEquals("2801", c.tac)
        assertEquals("214", c.mcc); assertEquals("07", c.mnc)
        assertEquals(-95, c.dbm); assertEquals(-11, c.rsrq); assertEquals(12, c.sinr)
        assertEquals(312, c.pci); assertEquals(3350, c.arfcn); assertEquals(7, c.band)
        assertEquals(4, c.timingAdvance); assertEquals(TimingAdvanceUnit.LTE_INDEX, c.timingAdvanceUnit)
        assertEquals(20_000, c.bandwidthKhz)
        assertEquals(CellConnectionState.PRIMARY_SERVING, c.connectionState)
    }

    @Test fun `LTE sentinels become empty, never 2147483647`() {
        val c = CellParser.parse(lte(pci = U, earfcn = U, bandwidth = U, rsrq = U, rssnr = U, ta = U), "4G LTE", OP_MCC, OP_MNC)
        assertNull(c.pci); assertNull(c.arfcn); assertNull(c.bandwidthKhz)
        assertNull(c.rsrq); assertNull(c.sinr); assertNull(c.timingAdvance)
        assertNull("sin EARFCN ni bandas declaradas no hay banda", c.band)
        // Un TA fuera del rango 0..1282 tampoco es un TA.
        assertNull(CellParser.parse(lte(ta = 1283), "4G LTE", OP_MCC, OP_MNC).timingAdvance)
        // Sin EARFCN, la banda declarada por el módem.
        assertEquals(20, CellParser.parse(lte(earfcn = U, extras = IdentityExtras(bands = listOf(20))), "4G LTE", OP_MCC, OP_MNC).band)
    }

    @Test fun `LTE neighbour - no inherited identity and placeholders are N-A`() {
        val n = CellParser.parse(lte(registered = false, ci = 268_435_455, tac = 65_535, mcc = null, mnc = null), "4G LTE", OP_MCC, OP_MNC)
        assertEquals(false, n.isRegistered)
        assertEquals("N/A", n.cellId); assertEquals("N/A", n.tac)
        assertEquals("una vecina no hereda el MCC/MNC del operador", "N/A", n.mcc)
        assertEquals("N/A", n.mnc)
        assertEquals(CellConnectionState.NONE, n.connectionState)
        // La registrada sin MCC/MNC sí hereda los del operador; su relleno no es relleno.
        val r = CellParser.parse(lte(ci = 268_435_455, tac = 65_535, mcc = null, mnc = null), "4G LTE", OP_MCC, OP_MNC)
        assertEquals("214", r.mcc); assertEquals("07", r.mnc)
        assertEquals("268435455", r.cellId); assertEquals("65535", r.tac)
    }

    // ── NR ──────────────────────────────────────────────────────────────────────────────────

    private fun nr(registered: Boolean = true, nci: Long = 5_000_123L, pci: Int = 501, tac: Int = 2801, nrarfcn: Int = 636_666,
                   mcc: String? = "214", mnc: String? = "07", ssRsrp: Int = -98, dbm: Int = -98, ssRsrq: Int = -12,
                   ssSinr: Int = 9, micros: Int? = 120, text: String = "CellInfoNr:{}") =
        NrReading(registered, CellConnectionState.PRIMARY_SERVING, nci, pci, tac, nrarfcn, mcc, mnc,
            ssRsrp, dbm, ssRsrq, ssSinr, micros, text)

    @Test fun `NR registered cell with the public timingAdvanceMicros getter`() {
        val c = CellParser.parse(nr(), "5G NR", OP_MCC, OP_MNC)
        assertEquals(RadioTech.NR, c.radioTech)
        assertEquals("5000123", c.cellId); assertEquals("2801", c.tac)
        assertEquals(-98, c.dbm); assertEquals(-12, c.rsrq); assertEquals(9, c.sinr)
        assertEquals(501, c.pci); assertEquals(636_666, c.arfcn)
        assertEquals("índice T_A crudo, sin distancia", 120, c.timingAdvance)
        assertEquals(TimingAdvanceUnit.NR_RAW, c.timingAdvanceUnit)
        assertNull(c.timingAdvanceUnit.toMeters(120))
    }

    @Test fun `NR timing advance - out of range or missing getter falls back to the text as UNKNOWN`() {
        val outOfRange = CellParser.parse(nr(micros = 3847, text = "CellSignalStrengthNr: ta=7 ssRsrp=-98"), "5G NR", OP_MCC, OP_MNC)
        assertEquals(7, outOfRange.timingAdvance); assertEquals(TimingAdvanceUnit.UNKNOWN, outOfRange.timingAdvanceUnit)
        val noGetter = CellParser.parse(nr(micros = null, text = "timingAdvance=15"), "5G NR", OP_MCC, OP_MNC)
        assertEquals(15, noGetter.timingAdvance); assertEquals(TimingAdvanceUnit.UNKNOWN, noGetter.timingAdvanceUnit)
        val none = CellParser.parse(nr(micros = U, text = "delta=0 data=0"), "5G NR", OP_MCC, OP_MNC)
        assertNull("UNAVAILABLE y un texto sin campo de TA: sin TA", none.timingAdvance)
    }

    @Test fun `NR sentinels - ssRsrp unavailable uses getDbm, the rest become empty`() {
        val c = CellParser.parse(nr(nci = Long.MAX_VALUE, tac = U, pci = U, nrarfcn = U, ssRsrp = U, dbm = -101, ssRsrq = U, ssSinr = U),
            "5G NR", OP_MCC, OP_MNC)
        assertEquals(-101, c.dbm)
        assertEquals("N/A", c.cellId); assertEquals("N/A", c.tac)
        assertNull(c.pci); assertNull(c.arfcn); assertNull(c.rsrq); assertNull(c.sinr)
        val neighbour = CellParser.parse(nr(registered = false, mcc = null, mnc = null), "5G NR", OP_MCC, OP_MNC)
        assertEquals("N/A", neighbour.mcc); assertEquals("N/A", neighbour.mnc)
    }

    // ── WCDMA ───────────────────────────────────────────────────────────────────────────────

    private fun wcdma(registered: Boolean = true, cid: Int = 12_345_678, lac: Int = 1200, psc: Int = 300, uarfcn: Int = 10_738,
                      mcc: String? = "214", mnc: String? = "07", dbm: Int = -85) =
        WcdmaReading(registered, CellConnectionState.PRIMARY_SERVING, cid, lac, psc, uarfcn, mcc, mnc, dbm)

    @Test fun `WCDMA is UMTS, has no timing advance and maps PSC and UARFCN`() {
        val c = CellParser.parse(wcdma(), "3G", OP_MCC, OP_MNC)
        assertEquals(RadioTech.UMTS, c.radioTech)
        assertEquals("12345678", c.cellId); assertEquals("1200", c.tac)
        assertEquals(300, c.pci); assertEquals(10_738, c.arfcn); assertEquals(-85, c.dbm)
        assertNull(c.timingAdvance)
        val sentinels = CellParser.parse(wcdma(cid = U, lac = -1, psc = U, uarfcn = U), "3G", OP_MCC, OP_MNC)
        assertEquals("N/A", sentinels.cellId); assertEquals("N/A", sentinels.tac)
        assertNull(sentinels.pci); assertNull(sentinels.arfcn)
        assertEquals("N/A", CellParser.parse(wcdma(registered = false, mcc = null), "3G", OP_MCC, OP_MNC).mcc)
    }

    // ── GSM ─────────────────────────────────────────────────────────────────────────────────

    private fun gsm(registered: Boolean = true, cid: Int = 4242, lac: Int = 1200, arfcn: Int = 62, mcc: String? = "214",
                    mnc: String? = "07", dbm: Int = -70, ta: Int = 3) =
        GsmReading(registered, CellConnectionState.PRIMARY_SERVING, cid, lac, arfcn, mcc, mnc, dbm, ta)

    @Test fun `GSM keeps its own technology and a GSM timing advance index`() {
        val c = CellParser.parse(gsm(), "2G", OP_MCC, OP_MNC)
        assertEquals(RadioTech.GSM, c.radioTech)
        assertEquals("4242", c.cellId); assertEquals("1200", c.tac); assertEquals(62, c.arfcn)
        assertEquals(3, c.timingAdvance); assertEquals(TimingAdvanceUnit.GSM_INDEX, c.timingAdvanceUnit)
        assertNull("GSM no tiene PCI", c.pci)
        assertNull("fuera de 0..219", CellParser.parse(gsm(ta = 220), "2G", OP_MCC, OP_MNC).timingAdvance)
        assertNull(CellParser.parse(gsm(ta = U), "2G", OP_MCC, OP_MNC).timingAdvance)
        val neighbour = CellParser.parse(gsm(registered = false, cid = U, mcc = null, mnc = null), "2G", OP_MCC, OP_MNC)
        assertEquals("N/A", neighbour.cellId); assertEquals("N/A", neighbour.mcc); assertEquals(false, neighbour.isRegistered)
    }

    @Test fun `the network type string is kept as given and never decides the technology`() {
        val c = CellParser.parse(lte(), "5G NSA", OP_MCC, OP_MNC)
        assertEquals("5G NSA", c.networkType)
        assertEquals(RadioTech.LTE, c.radioTech)
    }
}
