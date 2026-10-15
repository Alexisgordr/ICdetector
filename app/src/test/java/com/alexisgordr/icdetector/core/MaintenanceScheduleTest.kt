package com.alexisgordr.icdetector.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0 (corte de campaña) — El mantenimiento corre al arrancar el servicio y luego cada 24 h. */
class MaintenanceScheduleTest {
    private val day = MaintenanceSchedule.INTERVAL_MS

    @Test fun `the first request runs even with the phone booted minutes ago`() {
        var now = 5L * 60_000                       // 5 min desde el arranque del móvil
        val schedule = MaintenanceSchedule { now }
        assertTrue(schedule.claim())
        now += 60_000
        assertFalse("no repite en el mismo día", schedule.claim())
        now += day
        assertTrue("al día siguiente vuelve", schedule.claim())
    }

    @Test fun `force always runs`() {
        val schedule = MaintenanceSchedule { 1_000L }
        assertTrue(schedule.claim())
        assertTrue(schedule.claim(force = true))
    }

    @Test fun `the service asks for maintenance from its scan loop`() {
        val main = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        val maintenance = main.resolve("com/alexisgordr/icdetector/service/DatabaseMaintenance.kt").readText()
        assertTrue(maintenance.contains("MaintenanceSchedule"))
        assertFalse(maintenance.contains("lastRunElapsedMs"))
    }
}
