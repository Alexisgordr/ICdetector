package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3.1.0 (#47) — Un solo haversine: [CellGeometry.distanceMeters]. MotionClassifier (Stable-Site) y
 * GpsFixContinuity tenían su propia copia; ahora delegan. Los dos puntos de llamada dan el mismo
 * valor (±1 m) en pares de coordenadas variados, y el mismo que la fórmula que usaba Stable-Site.
 */
class HaversineUnificationTest {
    /** La copia que tenía MotionClassifier hasta 3.0, para comprobar que nada cambia. */
    private fun previousMotionFormula(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6_371_000.0; val p1 = Math.toRadians(aLat); val p2 = Math.toRadians(bLat)
        val dp = p2 - p1; val dl = Math.toRadians(bLon - aLon)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    private val pairs = listOf(
        doubleArrayOf(40.4168, -3.7038, 40.4169, -3.7038),     // 11 m, Madrid
        doubleArrayOf(40.4168, -3.7038, 40.4530, -3.6883),     // ~4 km
        doubleArrayOf(41.3874, 2.1686, 40.4168, -3.7038),      // Barcelona–Madrid, ~505 km
        doubleArrayOf(28.1235, -15.4363, 43.3623, -8.4115),    // Canarias–A Coruña
        doubleArrayOf(-33.8688, 151.2093, 51.5074, -0.1278),   // Sídney–Londres
        doubleArrayOf(0.0, 179.9999, 0.0, -179.9999),          // a través del antimeridiano
        doubleArrayOf(89.9, 0.0, 89.9, 180.0),                 // cerca del polo
        doubleArrayOf(37.0, -5.0, 37.0, -5.0)                  // mismo punto
    )

    @Test fun `both call sites give the same distance within 1 m`() {
        pairs.forEach { (aLat, aLon, bLat, bLon) ->
            val geometry = CellGeometry.distanceMeters(aLat, aLon, bLat, bLon)
            val motion = MotionClassifier.distanceMeters(aLat, aLon, bLat, bLon).toDouble()
            val label = "$aLat,$aLon → $bLat,$bLon"
            // Float pierde precisión a miles de km (~1 m por 16 000 km): margen ±1 m o 1e-7 relativo.
            assertEquals(label, geometry, motion, maxOf(1.0, geometry * 1e-7))
            assertEquals("igual que la fórmula anterior de Stable-Site: $label", previousMotionFormula(aLat, aLon, bLat, bLon), geometry, maxOf(1.0, geometry * 1e-7))
        }
        assertEquals(0.0, CellGeometry.distanceMeters(37.0, -5.0, 37.0, -5.0), 1e-9)
        assertEquals(11.1, CellGeometry.distanceMeters(40.4168, -3.7038, 40.4169, -3.7038), 0.2)
    }

    @Test fun `there is no other haversine in the engine`() {
        val core = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/core")
        val copies = core.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""6_?371_?000""").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(listOf("CellGeometry.kt"), copies)
        val stableSite = core.resolve("StableSite.kt").readText()
        assertTrue(stableSite.contains("CellGeometry.distanceMeters(aLat, aLon, bLat, bLon).toFloat()"))
        assertFalse(core.resolve("GpsFixContinuity.kt").readText().contains("atan2("))
    }
}
