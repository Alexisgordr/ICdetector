package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellLocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la geometría que alimenta la pestaña GEOMETRÍA y, desde v2.5, también a H16.
 *
 * El punto que más importa aquí no es la aritmética —esa es estándar— sino que la partición del
 * Cell ID en emplazamiento y sector se haga SOLO donde el estándar la define, y que se abstenga
 * en cuanto hay la menor duda. Una agrupación falsa produciría "incoherencias" inventadas.
 */
class CellGeometryTest {

    private fun cluster(lat: Double, lon: Double, n: Int = 6) =
        (0 until n).map { CellLocationSample(lat + it * 0.00002, lon + it * 0.00002) }

    // ── Perfil ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `el centro es la mediana y resiste un fix disparatado`() {
        val samples = cluster(42.8270, -1.6458, 8) +
            CellLocationSample(52.3676, 4.9041)   // el fix de Ámsterdam visto en campo
        val p = CellGeometry.profile(samples)
        assertTrue("La mediana no debe irse a Holanda", p.latitude in 42.82..42.83)
        assertTrue(p.longitude in -1.65..-1.64)
    }

    @Test
    fun `el radio P90 ignora la cola pero no la borra`() {
        val p = CellGeometry.profile(cluster(42.8270, -1.6458, 10))
        assertTrue("Un grupo apretado tiene radio pequeño", p.radiusP90 < 100.0)
        assertEquals(10, p.sampleCount)
    }

    @Test
    fun `no se publica perfil sin muestras suficientes`() {
        assertNull(CellGeometry.profileOrNull(cluster(42.8270, -1.6458, 2)))
        assertNotNull(CellGeometry.profileOrNull(cluster(42.8270, -1.6458, 3)))
    }

    // ── Partición de la identidad ────────────────────────────────────────────────────────────

    @Test
    fun `el eNodeB y el sector salen del ECI segun 3GPP`() {
        // 79362070 = 310008 * 256 + 22
        assertEquals(310008L, CellGeometry.enodebOf("214-07-31601-79362070-LTE"))
        assertEquals(22, CellGeometry.sectorOf("214-07-31601-79362070-LTE"))
        // Dos CID consecutivos son dos sectores del mismo emplazamiento.
        assertEquals(
            CellGeometry.enodebOf("214-07-31601-79362070-LTE"),
            CellGeometry.enodebOf("214-07-31601-79362069-LTE")
        )
    }

    @Test
    fun `no se parte el identificador fuera de LTE`() {
        // En NR el reparto gNB/celda lo elige el operador: dividir por 256 inventaría torres.
        assertNull(CellGeometry.enodebOf("214-07-31601-79362070-NR"))
        assertNull(CellGeometry.enodebOf("214-07-31601-12345-UMTS"))
        assertNull(CellGeometry.enodebOf("214-07-31601-12345-GSM"))
    }

    @Test
    fun `una identidad malformada no produce un emplazamiento inventado`() {
        assertNull(CellGeometry.enodebOf("214-07-31601-N/A-LTE"))
        assertNull(CellGeometry.enodebOf("basura"))
        assertNull(CellGeometry.enodebOf("214-07-31601-LTE"))
        // Fuera de los 28 bits del ECI ya no es un ECI, sea lo que sea.
        assertNull(CellGeometry.enodebOf("214-07-31601-999999999999-LTE"))
    }

    // ── Coherencia de emplazamiento ──────────────────────────────────────────────────────────

    @Test
    fun `los sectores de una misma torre observados juntos son coherentes`() {
        val profiles = mapOf(
            "214-07-31601-79362069-LTE" to CellGeometry.profile(cluster(42.8270, -1.6458)),
            "214-07-31601-79362070-LTE" to CellGeometry.profile(cluster(42.8272, -1.6460))
        )
        val sites = CellGeometry.siteCoherence(profiles)
        assertEquals(1, sites.size)
        assertTrue(sites[0].coherent)
        assertTrue("Sectores del mismo sitio caen muy cerca", sites[0].maxSpreadM < 100.0)
    }

    @Test
    fun `un sector que dice ser de una torre lejana se marca incoherente`() {
        val profiles = mapOf(
            "214-07-31601-79362069-LTE" to CellGeometry.profile(cluster(42.8270, -1.6458)),
            // Mismo eNodeB por aritmética, pero observado a ~110 km.
            "214-07-31601-79362070-LTE" to CellGeometry.profile(cluster(43.8270, -1.6458))
        )
        val site = CellGeometry.siteCoherence(profiles).single()
        assertFalse(site.coherent)
        assertTrue(site.maxSpreadM > CellGeometry.MAX_SITE_SPREAD_M)
    }

    @Test
    fun `un emplazamiento con un solo sector no se informa`() {
        val profiles = mapOf("214-07-31601-79362069-LTE" to CellGeometry.profile(cluster(42.8270, -1.6458)))
        assertTrue(CellGeometry.siteCoherence(profiles).isEmpty())
    }

    // ── Coherencia de ruta ───────────────────────────────────────────────────────────────────

    @Test
    fun `un handover entre celdas solapadas no deja hueco sin explicar`() {
        val profiles = mapOf(
            "A" to CellGeometry.profile(cluster(42.8270, -1.6458)),
            "B" to CellGeometry.profile(cluster(42.8290, -1.6470))
        )
        val check = CellGeometry.routeCoherence(listOf(Triple("A", "B", 10 to 4)), profiles).single()
        assertTrue(check.coherent)
        // El hueco no es exactamente cero —estos grupos de prueba son mucho más apretados que una
        // celda real, así que sus radios P90 casi no descuentan—, pero queda tres órdenes de
        // magnitud por debajo del umbral. Lo que se comprueba es eso, no un cero artificial.
        assertTrue(
            "Hueco de ${check.unexplainedGapM} m en un salto de barrio",
            check.unexplainedGapM < CellGeometry.MIN_ROUTE_GAP_M / 10.0
        )
    }

    @Test
    fun `un handover entre zonas incompatibles deja un hueco enorme`() {
        val profiles = mapOf(
            "A" to CellGeometry.profile(cluster(40.4168, -3.7038)),   // Madrid
            "B" to CellGeometry.profile(cluster(41.3874, 2.1686))     // Barcelona
        )
        val check = CellGeometry.routeCoherence(listOf(Triple("A", "B", 1 to 0)), profiles).single()
        assertFalse(check.coherent)
        assertTrue(check.unexplainedGapM > 100_000.0)
    }

    @Test
    fun `una ruta sin perfil en algun extremo no se evalua`() {
        val profiles = mapOf("A" to CellGeometry.profile(cluster(42.8270, -1.6458)))
        assertTrue(CellGeometry.routeCoherence(listOf(Triple("A", "B", 3 to 0)), profiles).isEmpty())
    }

    // ── Proyección ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `la proyeccion coloca los extremos en los bordes y corrige la longitud`() {
        val profiles = listOf(
            CellGeometry.profile(cluster(42.8200, -1.6500)),
            CellGeometry.profile(cluster(42.8400, -1.6300))
        )
        val bounds = CellGeometry.boundsOf(profiles)!!
        assertEquals(0f, bounds.relativeY(bounds.maxLat), 0.001f)
        assertEquals(1f, bounds.relativeY(bounds.minLat), 0.001f)
        assertEquals(0f, bounds.relativeX(bounds.minLon), 0.001f)
        assertTrue("A 43° un grado de longitud mide menos que uno de latitud", bounds.lonScale < 0.8)
    }

    @Test
    fun `todas las celdas en un punto no revientan la proyeccion`() {
        val p = CellGeometry.profile(cluster(42.8270, -1.6458, 4))
        val bounds = CellGeometry.boundsOf(listOf(p, p))!!
        val x = bounds.relativeX(p.longitude)
        val y = bounds.relativeY(p.latitude)
        assertTrue("Sin NaN ni infinitos al dividir por una extensión nula", x.isFinite() && y.isFinite())
    }

    @Test
    fun `sin perfiles no hay rectangulo`() {
        assertNull(CellGeometry.boundsOf(emptyList()))
    }
}
