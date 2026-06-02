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
    fun `EARFCN fuera de rango o invalido devuelve null`() {
        assertNull(BandPlan.earfcnToBandLte(0))         // 0 no es válido
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
}
