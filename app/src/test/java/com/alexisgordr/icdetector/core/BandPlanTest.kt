package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del mapeo EARFCN -> banda LTE (3GPP TS 36.101) y de la clasificación
 * alta/baja frecuencia. BandPlan es lógica pura (sin Android), corre en la JVM.
 *
 * Ubicación:  app/src/test/java/com/alexisgordr/icdetector/core/BandPlanTest.kt
 * Ejecutar:   ./gradlew testDebugUnitTest
 */
class BandPlanTest {

    @Test
    fun `mapea EARFCN a la banda LTE correcta`() {
        assertEquals(20, BandPlan.earfcnToBandLte(6300)) // 6150-6449 -> B20 (800 MHz)
        assertEquals(7,  BandPlan.earfcnToBandLte(3000)) // 2750-3449 -> B7  (2600 MHz)
        assertEquals(3,  BandPlan.earfcnToBandLte(1500)) // 1200-1949 -> B3  (1800 MHz)
        assertEquals(1,  BandPlan.earfcnToBandLte(300))  // 0-599     -> B1  (2100 MHz)
        assertEquals(28, BandPlan.earfcnToBandLte(9400)) // 9210-9659 -> B28 (700 MHz)
        assertEquals(8,  BandPlan.earfcnToBandLte(3600)) // 3450-3799 -> B8  (900 MHz)
    }

    @Test
    fun `EARFCN 0 mapea a Banda 1`() {
        assertEquals(1, BandPlan.earfcnToBandLte(0))     // 0 es el primer canal de Banda 1 (2110 MHz)
    }

    @Test
    fun `EARFCN fuera de rango o invalido devuelve null`() {
        assertNull(BandPlan.earfcnToBandLte(-10))       // negativo
        assertNull(BandPlan.earfcnToBandLte(9_999_999)) // fuera de toda la tabla
        assertNull(BandPlan.earfcnToBandLte(5000))      // hueco entre B4 y B12
    }

    @Test
    fun `las bandas sub-GHz se clasifican como bajas`() {
        // B20 (791), B28 (758), B8 (925), B5 (869), B71 (617) -> < 1000 MHz
        assertTrue(BandPlan.isLowBand(20))
        assertTrue(BandPlan.isLowBand(28))
        assertTrue(BandPlan.isLowBand(8))
        assertTrue(BandPlan.isLowBand(5))
        assertTrue(BandPlan.isLowBand(71))
    }

    @Test
    fun `las bandas urbanas altas se clasifican como altas`() {
        // B1 (2110), B3 (1805), B7 (2620) -> >= 1000 MHz
        assertTrue(BandPlan.isHighBand(1))
        assertTrue(BandPlan.isHighBand(3))
        assertTrue(BandPlan.isHighBand(7))
    }

    @Test
    fun `alta y baja son mutuamente excluyentes`() {
        assertFalse(BandPlan.isHighBand(20)) // B20 es baja, no alta
        assertFalse(BandPlan.isLowBand(7))   // B7 es alta, no baja
    }

    @Test
    fun `null nunca es ni alta ni baja`() {
        assertFalse(BandPlan.isLowBand(null))
        assertFalse(BandPlan.isHighBand(null))
    }

    @Test
    fun `approxFreqMhz devuelve la frecuencia esperada`() {
        assertEquals(791, BandPlan.approxFreqMhz(20)) // B20 ~ 800 MHz
        assertEquals(2620, BandPlan.approxFreqMhz(7)) // B7 ~ 2600 MHz
        assertNull(BandPlan.approxFreqMhz(999))       // banda inexistente
    }

    // ---------- 3.0 (#32) ----------

    @Test
    fun `bandas antes omitidas se resuelven y clasifican`() {
        assertEquals(66, BandPlan.earfcnToBandLte(66_500)); assertTrue(BandPlan.isHighBand(66))
        assertEquals(25, BandPlan.earfcnToBandLte(8_100)); assertTrue(BandPlan.isHighBand(25))
        assertEquals(14, BandPlan.earfcnToBandLte(5_300)); assertTrue(BandPlan.isLowBand(14))
        assertEquals(42, BandPlan.earfcnToBandLte(42_000)); assertTrue(BandPlan.isHighBand(42))
        assertEquals(44, BandPlan.earfcnToBandLte(46_000)); assertTrue(BandPlan.isLowBand(44))
    }

    @Test
    fun `limites de EARFCN de cada banda`() {
        BandPlan.BANDS.filter { it.earfcnMin != null }.forEach { band ->
            assertEquals("primer canal B${band.number}", band.number, BandPlan.earfcnToBandLte(band.earfcnMin!!))
            assertEquals("ultimo canal B${band.number}", band.number, BandPlan.earfcnToBandLte(band.earfcnMax!!))
            val after = BandPlan.earfcnToBandLte(band.earfcnMax!! + 1)
            assertTrue("canal tras B${band.number}", after != band.number)
        }
    }

    @Test
    fun `la B71 ya no se come los EARFCN de las bandas 72 a 74`() {
        assertEquals(71, BandPlan.earfcnToBandLte(68_935))
        assertEquals(72, BandPlan.earfcnToBandLte(68_936))   // B72 (450 MHz)
        assertEquals(74, BandPlan.earfcnToBandLte(69_465))   // B74 (1475 MHz): antes se daba por B71, baja
        assertTrue(BandPlan.isHighBand(74))
    }

    @Test
    fun `los rangos no se solapan y cada banda es alta o baja, nunca las dos`() {
        val ranges = BandPlan.BANDS.filter { it.earfcnMin != null }.sortedBy { it.earfcnMin }
        ranges.zipWithNext().forEach { (a, b) -> assertTrue("B${a.number}/B${b.number}", a.earfcnMax!! < b.earfcnMin!!) }
        BandPlan.BANDS.forEach { band ->
            assertTrue("B${band.number}", BandPlan.isLowBand(band.number) != BandPlan.isHighBand(band.number))
        }
    }

    @Test
    fun `el EARFCN de la tabla manda sobre la banda declarada`() {
        assertEquals(3, BandPlan.resolveLteBand(1_500, listOf(3)))
        assertEquals(3, BandPlan.resolveLteBand(1_500, listOf(20)))     // discrepancia: gana la medida
        assertEquals(3, BandPlan.resolveLteBand(1_500, emptyList()))    // API 29: solo EARFCN
    }

    @Test
    fun `fuera de tabla se usa la banda declarada`() {
        assertEquals(252, BandPlan.resolveLteBand(255_500, listOf(252)))   // LAA: fuera de tabla
        assertEquals(74, BandPlan.resolveLteBand(null, listOf(74)))
        assertNull(BandPlan.resolveLteBand(255_500, emptyList()))
        assertNull(BandPlan.resolveLteBand(null, listOf(0, -1)))
    }

    @Test
    fun `varias bandas declaradas solo valen si son de la misma clase`() {
        assertEquals(72, BandPlan.resolveLteBand(null, listOf(73, 72)))   // las dos bajas
        assertNull(BandPlan.resolveLteBand(null, listOf(72, 74)))         // baja y alta: N/A
        assertEquals(252, BandPlan.resolveLteBand(null, listOf(255, 252))) // las dos altas
        assertNull(BandPlan.resolveLteBand(null, listOf(74, 999)))        // una desconocida: N/A
    }

    @Test
    fun `una celda en banda antes omitida ya permite evaluar H14`() {
        val b66 = com.alexisgordr.icdetector.models.CellData(
            isRegistered = true, networkType = "4G LTE", cellId = "1", mnc = "07", tac = "1", dbm = -75,
            mcc = "214", radioTech = com.alexisgordr.icdetector.models.RadioTech.LTE, arfcn = 6_300
        )
        val report = ThreatAnalyzer.analyzeThreats(
            active = b66, neighbors = emptyList(), isHardwareCipheringActive = false,
            cellChangeHistory = emptyList(), currentLocation = null,
            previousBand = BandPlan.earfcnToBandLte(66_500), previousDbm = -80, recentRegisteredDbm = emptyList()
        ).heuristicReport
        assertEquals(com.alexisgordr.icdetector.models.HeuristicStatus.FAILED, report.bandDowngrade)
    }
}

