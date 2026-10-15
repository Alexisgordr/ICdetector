package com.alexisgordr.icdetector.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — Bug found during testing: el indicador GPS de la pantalla principal tenía su propia
 * comprobación (hora de pared, cada 30 s) y podía decir "GPS OK" con una posición que el análisis
 * ya no usaba, o al revés. Ahora pregunta al servicio, que aplica los mismos filtros que el análisis.
 */
class GpsIndicatorTest {

    private fun read(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
    private fun source(path: String) = read("src/main/java/com/alexisgordr/icdetector/$path")

    @Test fun `the indicator asks the service and never reads LocationManager itself`() {
        val screen = source("ui/MainScreen.kt")
        assertTrue(screen.contains("service?.hasUsableGpsFix() == true"))
        assertFalse(screen.contains("getLastKnownLocation"))
        assertTrue(screen.contains("repeatOnLifecycle(Lifecycle.State.STARTED)"))
    }

    @Test fun `the service check uses the same validated position as the analysis`() {
        val controller = source("service/LocationCollectionController.kt")
        assertTrue(controller.contains("fun hasUsableGpsFix(): Boolean = gpsAvailable() && currentLocation() != null"))
        val service = source("service/MiniICService.kt")
        assertTrue(service.contains("locationController.hasUsableGpsFix()"))
    }

    @Test fun `the indicator text is translated`() {
        val screen = source("ui/MainScreen.kt")
        assertTrue(screen.contains("R.string.gps_state_ok else R.string.gps_state_unavailable"))
        listOf("values" to "NO GPS", "values-es" to "SIN GPS").forEach { (dir, text) ->
            val strings = read("src/main/res/$dir/strings.xml")
            assertTrue(strings.contains("<string name=\"gps_state_ok\">● GPS OK</string>"))
            assertTrue(strings.contains("<string name=\"gps_state_unavailable\">● $text</string>"))
        }
    }
}
