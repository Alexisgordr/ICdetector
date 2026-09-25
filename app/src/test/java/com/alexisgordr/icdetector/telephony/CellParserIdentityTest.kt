package com.alexisgordr.icdetector.telephony

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Solo la celda registrada hereda el MCC/MNC del operador. Rellenar las vecinas con él hacía que
 * H3 (MCC) y H4 (MNC) comparasen nuestra red consigo misma y salieran siempre superadas.
 */
class CellParserIdentityTest {

    @Test
    fun `la celda registrada sin MCC hereda el del operador`() {
        assertEquals("214", CellParser.identityOrFallback(null, registered = true, operatorValue = "214"))
    }

    @Test
    fun `una vecina sin MCC se queda en N-A`() {
        assertEquals("N/A", CellParser.identityOrFallback(null, registered = false, operatorValue = "214"))
    }

    @Test
    fun `el valor informado por el modem siempre gana`() {
        assertEquals("208", CellParser.identityOrFallback("208", registered = false, operatorValue = "214"))
        assertEquals("208", CellParser.identityOrFallback("208", registered = true, operatorValue = "214"))
    }
}
