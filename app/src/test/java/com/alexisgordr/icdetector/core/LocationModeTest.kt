package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — Modos de ubicación: continuo (predeterminado), inteligente y adaptativo. */
class LocationModeTest {

    @Test fun `continuous is the default, also for unknown stored values`() {
        assertEquals(LocationMode.CONTINUOUS, LocationMode.fromStored(null))
        assertEquals(LocationMode.CONTINUOUS, LocationMode.fromStored(""))
        assertEquals(LocationMode.CONTINUOUS, LocationMode.fromStored("SOMETHING_ELSE"))
        assertEquals(LocationMode.CONTINUOUS, LocationMode.fromStored("CONTINUOUS"))
        assertEquals(LocationMode.ADAPTIVE, LocationMode.fromStored("ADAPTIVE"))
        assertEquals(LocationMode.INTELLIGENT, LocationMode.fromStored("INTELLIGENT"))
    }

    @Test fun `continuous keeps the GPS stream on with the screen on or off`() {
        assertTrue(LocationPolicy.streamWanted(LocationMode.CONTINUOUS, screenOn = true))
        assertTrue(LocationPolicy.streamWanted(LocationMode.CONTINUOUS, screenOn = false))
    }

    @Test fun `adaptive keeps the stream only while the screen is on`() {
        assertTrue(LocationPolicy.streamWanted(LocationMode.ADAPTIVE, screenOn = true))
        assertFalse(LocationPolicy.streamWanted(LocationMode.ADAPTIVE, screenOn = false))
    }

    @Test fun `smart uses bounded probes rather than a permanent GPS stream`() {
        assertFalse(LocationPolicy.streamWanted(LocationMode.INTELLIGENT, screenOn = true))
        assertFalse(LocationPolicy.streamWanted(LocationMode.INTELLIGENT, screenOn = false))
    }

    @Test fun `continuous never limits on-demand fixes beyond the existing waits`() {
        val gate = GpsPowerPolicy()
        repeat(5) { assertTrue(gate.tryStartProbe(LocationMode.CONTINUOUS, 1_000L + it)) }
    }

    @Test fun `adaptive grants at most one on-demand fix per minimum interval`() {
        val gate = GpsPowerPolicy()
        assertTrue(gate.tryStartProbe(LocationMode.ADAPTIVE, 0L))
        // Cambios de celda repetidos o una sospecha persistente: no piden GPS sin parar.
        assertFalse(gate.tryStartProbe(LocationMode.ADAPTIVE, 5_000L))
        assertFalse(gate.tryStartProbe(LocationMode.ADAPTIVE, LocationMode.ADAPTIVE_MIN_FIX_INTERVAL_MS - 1))
        assertTrue(gate.tryStartProbe(LocationMode.ADAPTIVE, LocationMode.ADAPTIVE_MIN_FIX_INTERVAL_MS))
    }

    @Test fun `the minimum interval is a minute`() {
        assertEquals(60_000L, LocationMode.ADAPTIVE_MIN_FIX_INTERVAL_MS)
    }

    @Test fun `a service restarted with the screen off in adaptive mode starts without the GPS stream`() {
        // El estado inicial sale de PowerManager.isInteractive, no de un true por defecto.
        val screenOnAtStart = false
        assertFalse(LocationPolicy.streamWanted(LocationMode.ADAPTIVE, screenOnAtStart))
        assertTrue(LocationPolicy.streamWanted(LocationMode.CONTINUOUS, screenOnAtStart))
        val service = source("service/MiniICService.kt")
        val register = service.substringAfter("private fun registerScreenReceiver() {")
        assertTrue(register.substringBefore("screenReceiver = object").contains(
            "isScreenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true"))
        // Y se registra (leyendo la pantalla) antes de aplicar el modo al arrancar.
        val start = service.indexOf("registerScreenReceiver()\n")
        assertTrue(start > 0)
        assertTrue(start < service.indexOf("applyLocationMode()", start))
    }

    private fun source(path: String) = listOf(
        File("src/main/java/com/alexisgordr/icdetector/$path"),
        File("app/src/main/java/com/alexisgordr/icdetector/$path")
    ).first { it.exists() }.readText()

    @Test fun `the setting, the service, the GPS controller and the export are wired together`() {
        val settings = source("ui/SettingsScreen.kt")
        assertTrue(settings.contains("putString(LocationMode.PREF_KEY, selectedMode.storedValue)"))
        assertTrue(settings.contains("service?.locationMode = selectedMode"))
        assertTrue(settings.contains("LocationMode.INTELLIGENT -> R.string.location_intelligent_description"))

        val service = source("service/MiniICService.kt")
        // El GPS continuo se enciende o apaga siempre según la política, nunca a mano.
        assertFalse(service.contains("locationController.startContinuousUpdates()\n                        if (collectionPausedForCriticalBattery)"))
        assertTrue(service.contains("locationController.updateCollection(isScreenOn, enabled = !collectionPausedForCriticalBattery)"))
        assertTrue(service.contains("locationMode = { locationMode.storedValue }"))

        val gps = source("service/LocationCollectionController.kt")
        assertTrue(gps.contains("powerPolicy.tryStartProbe(mode, now)"))
        assertTrue(gps.contains("powerPolicy.periodicProbeDue(mode, now)"))

        val notifications = source("service/ServiceNotificationController.kt")
        assertTrue(notifications.contains("R.string.notif_monitoring_title_adaptive"))
        assertTrue(notifications.contains("R.string.notif_monitoring_title_intelligent"))

        val persistence = source("service/ObservationPersistenceController.kt")
        assertTrue(persistence.contains("locationMode = row.context.locationMode"))
    }
}
