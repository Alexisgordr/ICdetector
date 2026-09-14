package com.alexisgordr.icdetector.telephony

import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La tecnología de radio sale de la clase de `CellInfo`, y cada una de las cuatro tiene la suya.
 *
 * Este test existe por un fallo real: al introducir [RadioTech] el mapeo se escribió a mano en cada
 * rama del parser y dos de las cuatro quedaron mal — GSM etiquetado como `UMTS` y WCDMA sin
 * tecnología. Consecuencia: una consulta GSM salía hacia OpenCellID pidiendo `radio=UMTS`, y una
 * WCDMA salía sin filtrar. Justo lo contrario de lo que el cambio pretendía.
 *
 * Se comprueba sobre la CLASE y no sobre instancias porque las clases de telefonía de Android no se
 * pueden construir en un test de JVM. La clase es lo único que decide el resultado, así que es
 * exactamente lo que hay que fijar.
 */
class CellParserRadioTechTest {

    @Test
    fun `LTE`() {
        assertEquals(RadioTech.LTE, CellParser.radioTechForClass(CellInfoLte::class.java))
    }

    @Test
    fun `NR`() {
        assertEquals(RadioTech.NR, CellParser.radioTechForClass(CellInfoNr::class.java))
    }

    @Test
    fun `WCDMA es UMTS, no desconocida`() {
        assertEquals(RadioTech.UMTS, CellParser.radioTechForClass(CellInfoWcdma::class.java))
    }

    @Test
    fun `GSM es GSM, no UMTS`() {
        assertEquals(RadioTech.GSM, CellParser.radioTechForClass(CellInfoGsm::class.java))
    }

    @Test
    fun `una clase desconocida no inventa tecnologia`() {
        // CDMA y cualquier futura clase: sin tecnología reconocida no se envía el filtro `radio`,
        // que es mejor que enviarlo equivocado.
        assertEquals(RadioTech.UNKNOWN, CellParser.radioTechForClass(String::class.java))
    }

    @Test
    fun `las cuatro tecnologias son distintas entre si`() {
        val mapeo = listOf(
            CellInfoLte::class.java,
            CellInfoNr::class.java,
            CellInfoWcdma::class.java,
            CellInfoGsm::class.java
        ).map { CellParser.radioTechForClass(it) }
        assertEquals("ninguna clase puede compartir tecnología con otra", 4, mapeo.toSet().size)
    }
}
