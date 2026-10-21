package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.MonitoringNotificationText.TextKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 — Presentación del estado de salud: prioridad persistente del aviso en la notificación,
 * recuperación forzada a pesar del límite de 2 s, y resultado visible coherente con su fecha.
 */
class AnalysisPresentationTest {
    private val unavailable = "Análisis temporalmente no disponible"

    /** Notificación simulada: aplica la política igual que el servicio y guarda lo último mostrado. */
    private class Notification {
        val policy = MonitoringNotificationText()
        var shown: String? = null
        fun update(text: String, unavailable: Boolean, now: Long, force: Boolean = false, kind: TextKind = TextKind.OTHER) {
            policy.next(text, kind, force, unavailable, "Análisis temporalmente no disponible", now)?.let { shown = it }
        }
    }

    @Test fun `the notice keeps priority over any other text while the analysis is unavailable`() {
        val n = Notification()
        n.update(unavailable, unavailable = true, now = 0, force = true)
        n.update("VERIFIED | 4G LTE | CID: 100 | -80 dBm", unavailable = true, now = 5_000)
        assertEquals(unavailable, n.shown)
        n.update("Batería crítica: GPS continuo en pausa", unavailable = true, now = 10_000)
        assertEquals(unavailable, n.shown)
        n.update("Monitorización activa", unavailable = true, now = 15_000, force = true)
        assertEquals(unavailable, n.shown)
    }

    @Test fun `missing signal is shown over the notice, it is the current state`() {
        val n = Notification()
        n.update("Sin señal / Modo Avión", unavailable = true, now = 0, force = true, kind = TextKind.NO_SIGNAL)
        assertEquals("Sin señal / Modo Avión", n.shown)
        n.update("Buscando red…", unavailable = true, now = 5_000, kind = TextKind.NO_SIGNAL)
        assertEquals("Buscando red…", n.shown)
    }

    @Test fun `recovery is not held back by the two-second limit`() {
        val n = Notification()
        n.update(unavailable, unavailable = true, now = 10_000, force = true)
        // Ciclo válido 500 ms después: sin forzar, el límite lo dejaría pasar y quedaría el aviso.
        n.update("VERIFIED | 4G LTE | CID: 100 | -80 dBm", unavailable = false, now = 10_500)
        assertEquals(unavailable, n.shown)
        n.update("VERIFIED | 4G LTE | CID: 100 | -80 dBm", unavailable = false, now = 10_600, force = true)
        assertEquals("VERIFIED | 4G LTE | CID: 100 | -80 dBm", n.shown)
    }

    @Test fun `the two-second limit still applies to ordinary updates`() {
        val policy = MonitoringNotificationText()
        assertEquals("a", policy.next("a", TextKind.OTHER, false, false, unavailable, 0))
        assertNull(policy.next("b", TextKind.OTHER, false, false, unavailable, 1_000))
        assertEquals("c", policy.next("c", TextKind.OTHER, false, false, unavailable, 2_000))
    }

    @Test fun `the service resolves every update, keeps write failure first and forces recovery`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val service = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val update = service.substringAfter("private fun updateNotificationText(").substringBefore("\n    }\n")
        assertTrue(update.contains("analysisUnavailable = _analysisHealth.value.unavailable"))
        assertTrue("escritura fallida por encima de todo",
            update.indexOf("monitoringNotificationText.next(") < update.indexOf("collectionHealthController.visibleNotification(resolved, now)"))
        assertFalse(service.contains("lastNotificationTime"))
        assertEquals("falta de señal en las cuatro llamadas", 4,
            Regex("""R\.string\.(no_signal_airplane|searching_network)\)?,?\s*(force = recovered, )?kind = com\.alexisgordr\.icdetector\.core\.MonitoringNotificationText\.TextKind\.NO_SIGNAL""")
                .findAll(service).count())
        assertTrue(service.contains("updateNotification(confirmedActive, force = recovered)"))
        assertTrue(service.contains("updateNotificationText(getString(R.string.searching_network), force = recovered, kind ="))
    }

    @Test fun `the visible result and its validity date are published together, notification apart`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val service = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val publish = service.substringAfter("if (!collectionGeneration.mayPublish(cycleTicket)) return@withContext")
            .substringBefore("private fun onAnalysisFailed(")
        // Rama con servidora: estado de banda, pantalla, fecha de validez, notificación; en ese orden.
        val band = publish.indexOf("prevRegisteredDbm = active.dbm")
        val screen = publish.indexOf("_cellFlow.value = sorted.mapIndexed")
        val health = publish.indexOf("val recovered = onAnalysisPublished(cycleTicket)")
        val notify = publish.indexOf("publishNotification { updateNotification(")
        assertTrue(band in 0 until screen && screen < health && health < notify)
        val between = publish.substring(screen, health)
        assertFalse("nada que pueda fallar entre la pantalla y su fecha",
            listOf("updateNotification", "checkAlerts", "verify(", "dbHelper").any { between.contains(it) })
        // Rama sin servidora: igual.
        val empty = publish.substringAfter("stableSiteIntensiveActive = false")
        assertTrue(empty.indexOf("_cellFlow.value = emptyList()") < empty.indexOf("onAnalysisPublished(cycleTicket)"))
        assertTrue(empty.indexOf("onAnalysisPublished(cycleTicket)") < empty.indexOf("publishNotification {"))
        // Un fallo de la notificación no es un fallo del análisis.
        val helper = service.substringAfter("private inline fun publishNotification(").substringBefore("\n    }\n")
        assertTrue(helper.contains("catch (e: CancellationException)") && helper.contains("throw e"))
        assertFalse(helper.contains("onAnalysisFailed") || helper.contains("analysisHealthTracker"))
    }
}
