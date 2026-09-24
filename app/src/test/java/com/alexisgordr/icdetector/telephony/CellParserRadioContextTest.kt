package com.alexisgordr.icdetector.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v2.10.4 — El ancho de banda "no disponible" de Android nunca llega al historial como número. */
class CellParserRadioContextTest {
    @Test
    fun `ancho de banda valido se conserva en kHz`() {
        assertEquals(20_000, CellParser.validBandwidth(20_000))
        assertEquals(1_400, CellParser.validBandwidth(1_400))
    }

    @Test
    fun `UNAVAILABLE cero y negativos son nulos`() {
        assertNull(CellParser.validBandwidth(Int.MAX_VALUE))
        assertNull(CellParser.validBandwidth(0))
        assertNull(CellParser.validBandwidth(-1))
    }
}
