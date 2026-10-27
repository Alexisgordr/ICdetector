package com.alexisgordr.icdetector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.1.6 (#49) — Una lista de celdas nula del sistema no cierra la app: sigue el flujo normal como
 * lista vacía, no cuenta como entrega para el watchdog y deja una sola línea en el terminal.
 */
class CellDeliveryTest {

    @Test fun `a null list becomes an empty list`() {
        assertEquals(emptyList<String>(), CellDelivery.orEmpty<String>(null))
    }

    @Test fun `a list with cells goes through unchanged`() {
        val cells = listOf("lte-1", "lte-2")
        assertEquals(cells, CellDelivery.orEmpty(cells))
    }

    @Test fun `only a list with cells counts as a delivery for the watchdog`() {
        assertFalse(CellDelivery.countsForWatchdog(null))
        assertFalse(CellDelivery.countsForWatchdog(emptyList<String>()))
        assertTrue(CellDelivery.countsForWatchdog(listOf("lte-1")))
    }

    @Test fun `a null delivery is announced once until a non-null one arrives`() {
        val notice = NullCellDeliveryNotice()
        assertEquals(NullCellDeliveryNotice.MESSAGE, notice.onDelivery(rawWasNull = true))
        assertNull("no se repite en cada entrega", notice.onDelivery(rawWasNull = true))
        assertNull(notice.onDelivery(rawWasNull = true))
        assertNull("una entrega no nula no escribe nada", notice.onDelivery(rawWasNull = false))
        assertEquals("tras una entrega no nula vuelve a avisar", NullCellDeliveryNotice.MESSAGE, notice.onDelivery(rawWasNull = true))
    }

    @Test fun `non-null deliveries never write a line`() {
        val notice = NullCellDeliveryNotice()
        repeat(5) { assertNull(notice.onDelivery(rawWasNull = false)) }
    }

    /**
     * El mismo recorrido que hace TelephonyCollectionController.handleCellDelivery con una entrega,
     * sin Android: lo que llega a deliver(), si cuenta para el watchdog y qué se escribe.
     */
    private class FakeDelivery {
        var now = 0L
        val notice = NullCellDeliveryNotice()
        val watchdog = CallbackWatchdog(timeoutMs = 1_000L, maxRetries = 1, clock = { now })
        val delivered = mutableListOf<List<String>>()
        val logs = mutableListOf<String>()

        fun deliver(raw: List<String>?) {
            if (CellDelivery.countsForWatchdog(raw)) watchdog.onDelivery()
            notice.onDelivery(raw == null)?.let(logs::add)
            delivered += CellDelivery.orEmpty(raw)
        }
    }

    @Test fun `null and empty deliveries reach the app as empty and leave the watchdog alone`() {
        val fake = FakeDelivery()
        fake.deliver(null)
        fake.deliver(emptyList())
        assertEquals(listOf(emptyList<String>(), emptyList()), fake.delivered)
        assertEquals(listOf(NullCellDeliveryNotice.MESSAGE), fake.logs)
        // Sin ninguna entrega con celdas, el watchdog sigue viendo silencio y reinicia los callbacks.
        fake.now = 5_000L
        assertTrue(fake.watchdog.onRefreshRequested() is CallbackWatchdog.Decision.Restart)
    }

    @Test fun `a list with cells still works as before`() {
        val fake = FakeDelivery()
        fake.deliver(listOf("lte-1"))
        assertEquals(listOf(listOf("lte-1")), fake.delivered)
        assertTrue(fake.logs.isEmpty())
        fake.now = 500L
        assertEquals("una entrega reciente con celdas: el watchdog no reinicia", CallbackWatchdog.Decision.Wait, fake.watchdog.onRefreshRequested())
    }
}
