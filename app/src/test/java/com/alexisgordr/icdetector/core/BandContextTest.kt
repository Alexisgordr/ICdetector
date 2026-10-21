package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.1.0 (#37) — H14 conserva la banda conocida (y su estación) mientras la identidad completa de la
 * servidora no cambia, aunque una lectura llegue sin canal. Se reinicia en el handover y, en el
 * servicio, tras una pérdida o un hueco.
 */
class BandContextTest {
    // B7 (2600 MHz, alta) en el eNB 310002; B20 (800 MHz, baja) en el eNB 310100.
    private fun lte(cid: String, arfcn: Int?, dbm: Int = -80) = CellData(isRegistered = true, networkType = "4G LTE",
        cellId = cid, mnc = "07", tac = "100", dbm = dbm, mcc = "214", arfcn = arfcn, radioTech = RadioTech.LTE)
    private val high = lte("79360544", 3350)          // B7
    private val highNoChannel = lte("79360544", null)
    private val low = lte("79385601", 6300, dbm = -70) // B20, otra estación

    @Test fun `a reading without channel keeps the band and site of the same cell`() {
        val first = BandContext.next(null, high)
        assertEquals(7, first.lteBand)
        val kept = BandContext.next(first, highNoChannel)
        assertEquals(7, kept.lteBand)
        assertEquals(LteSite.key(high), kept.site)
    }

    @Test fun `a handover takes the new cell's context, even without channel`() {
        val ctx = BandContext.next(BandContext.next(null, high), lte("79385601", null))
        assertNull("la banda de la anterior ya no describe a la servidora", ctx.lteBand)
        assertNull(ctx.site)
        assertEquals(20, BandContext.next(ctx, low).lteBand)
    }

    @Test fun `an unreadable Cell ID is not a handover and keeps the context`() {
        val ctx = BandContext.next(BandContext.next(null, high), lte("N/A", null))
        assertEquals(7, ctx.lteBand)
    }

    @Test fun `NR keeps its ARFCN the same way and a technology change resets it`() {
        val nr = CellData(isRegistered = true, networkType = "5G NR", cellId = "5000", mnc = "07", tac = "100", dbm = -80,
            mcc = "214", arfcn = 636666, radioTech = RadioTech.NR)
        val kept = BandContext.next(BandContext.next(null, nr), nr.copy(arfcn = null))
        assertEquals(636666, kept.nrArfcn)
        assertNull(BandContext.next(kept, high).nrArfcn)
    }

    /** El caso del issue: alta, una lectura sin canal de la misma celda y handover a baja. */
    @Test fun `H14 is evaluated after a reading without channel (it was N-A)`() {
        val before = BandContext.next(null, high)
        val oldRule = null                                   // antes: la lectura sin canal ponía la banda a null
        val ctx = BandContext.next(before, highNoChannel)
        fun h14(previousBand: Int?, site: String?) = ThreatAnalyzer.analyzeThreats(
            low, emptyList(), emptyList(), null, previousBand = previousBand, previousDbm = -80,
            previousBandSite = site, recentRegisteredDbm = listOf(-80, -80, -80)
        ).heuristicReport.bandDowngrade
        assertEquals(HeuristicStatus.NOT_EVALUATED, h14(oldRule, null))
        assertEquals(HeuristicStatus.FAILED, h14(ctx.lteBand, ctx.site))
    }

    @Test fun `the service uses it and drops it after a gap`() {
        val service = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        assertTrue(service.contains("val nextBand = com.alexisgordr.icdetector.core.BandContext.next(bandContext, active)"))
        assertTrue(service.contains("prevBand = nextBand.lteBand"))
        assertTrue(service.substringAfter("if (continuityBroken) {").substringBefore("}").contains("bandContext = null"))
    }
}
