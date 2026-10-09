package com.alexisgordr.icdetector.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (#24) — Un ciclo de análisis lento no publica ni alerta si mientras analizaba se perdió la
 * señal o cambió la celda servidora.
 */
class CollectionGenerationTest {
    private val generation = CollectionGeneration()

    @Test fun `a cycle with nothing newer publishes`() {
        val ticket = generation.deliver("214-07-1-100-LTE")
        assertTrue(generation.isLatest(ticket))
        assertTrue(generation.mayPublish(ticket))
    }

    @Test fun `signal lost while analysing - the cycle does not publish`() {
        val ticket = generation.deliver("214-07-1-100-LTE")
        generation.lose()                               // lista vacía o abstención
        assertFalse(generation.mayPublish(ticket))
    }

    @Test fun `serving cell changed while analysing - the cycle does not publish`() {
        val ticket = generation.deliver("214-07-1-100-LTE")
        generation.deliver("214-07-1-200-LTE")
        assertFalse(generation.mayPublish(ticket))
    }

    @Test fun `a newer delivery of the same cell does not starve publication`() {
        // Si las entregas llegan más rápido que el análisis, cada ciclo tendría siempre uno más
        // nuevo detrás. Con la misma celda se publica: si no, la app se quedaría sin publicar.
        val first = generation.deliver("214-07-1-100-LTE")
        val second = generation.deliver("214-07-1-100-LTE")
        assertTrue(generation.mayPublish(first))
        assertTrue(generation.mayPublish(second))
        // Pero al empezar, el ciclo anterior se salta: el nuevo correrá después.
        assertFalse(generation.isLatest(first))
        assertTrue(generation.isLatest(second))
    }

    @Test fun `the latest cycle always publishes after a loss`() {
        val stale = generation.deliver("214-07-1-100-LTE")
        generation.lose()
        val fresh = generation.deliver("214-07-1-200-LTE")
        assertFalse(generation.mayPublish(stale))
        assertTrue(generation.mayPublish(fresh))
    }

    @Test fun `the service checks before every side effect and on every loss`() {
        val service = listOf(
            File("src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt")
        ).first { it.exists() }.readText()
        assertTrue(service.contains("if (!collectionGeneration.isLatest(cycleTicket)) return@withLock"))
        // La comprobación es lo primero del bloque que publica, alerta y guarda.
        val publish = service.substringAfter("withContext(Dispatchers.Main) {\n                    // 3.0 (#24)")
        val firstCode = publish.lineSequence().drop(1).map { it.trim() }.first { it.isNotEmpty() && !it.startsWith("//") }
        assertTrue(firstCode, firstCode == "if (!collectionGeneration.mayPublish(cycleTicket)) return@withContext")
        assertTrue(publish.indexOf("checkAlerts(") > 0)
        // La lista vacía y la abstención invalidan los ciclos en curso.
        assertTrue(Regex("collectionGeneration\\.lose\\(\\)").findAll(service).count() >= 2)
    }
}
