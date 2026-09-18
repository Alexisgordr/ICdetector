package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.storage.CellDbHelper
import com.alexisgordr.icdetector.utils.ExportUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de las defensas de la CAMPAÑA, no del motor de detección.
 *
 * Cada una corresponde a una forma concreta en la que una recolección de tres meses podía morir
 * en silencio: retención más corta que la campaña, escrituras que fallan sin avisar, casos
 * forenses encadenados que llenan el disco, y un export parcial presentado como completo.
 */
class CampaignSafetyTest {

    private val campaignDays = 90

    // ── Retención ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `la retencion cubre la campana completa con margen`() {
        assertTrue(
            "La poda borraría datos de la campaña: ${CellDbHelper.DEFAULT_RETENTION_DAYS} días " +
                "de retención para $campaignDays días de recolección",
            CellDbHelper.DEFAULT_RETENTION_DAYS > campaignDays
        )
    }

    @Test
    fun `el tope de muestras forenses es finito y razonable`() {
        assertTrue(CellDbHelper.MAX_FORENSIC_SAMPLES in 1_000..200_000)
    }

    // ── Salud de la escritura ────────────────────────────────────────────────────────────────

    @Test
    fun `una escritura fallida aislada no dispara la alarma`() {
        val health = CollectionHealth(failuresBeforeAlarm = 3)
        health.noteWrite(-1L, 1_000L)
        health.noteWrite(-1L, 2_000L)
        assertFalse(health.isFailing)
    }

    @Test
    fun `fallos sostenidos declaran la recoleccion rota y avisan una sola vez`() {
        val health = CollectionHealth(failuresBeforeAlarm = 3)
        assertFalse(health.noteWrite(-1L, 1_000L))
        assertFalse(health.noteWrite(-1L, 2_000L))
        assertTrue("El tercer fallo debe cambiar el estado visible", health.noteWrite(-1L, 3_000L))
        assertTrue(health.isFailing)
        assertFalse("Un cuarto fallo no vuelve a repintar la notificación", health.noteWrite(-1L, 4_000L))
    }

    @Test
    fun `una escritura correcta restablece el estado`() {
        val health = CollectionHealth(failuresBeforeAlarm = 2)
        health.noteWrite(-1L, 1_000L)
        health.noteWrite(-1L, 2_000L)
        assertTrue(health.isFailing)
        assertTrue("Volver a escribir debe cambiar el estado visible", health.noteWrite(42L, 3_000L))
        assertFalse(health.isFailing)
        assertEquals(3_000L, health.lastSuccessMs)
    }

    // ── Interrupciones de la recolección ─────────────────────────────────────────────────────

    @Test
    fun `un hueco largo entre arranques se reconoce como interrupcion`() {
        val health = CollectionHealth(interruptionGapMs = 30L * 60_000L)
        health.restore(lastSuccessMs = 1_000_000L)
        assertNull("Cinco minutos no son una interrupción", health.interruptionBefore(1_300_000L))
        assertEquals(3_600_000L, health.interruptionBefore(4_600_000L))
    }

    @Test
    fun `sin escrituras previas no se inventa una interrupcion`() {
        val health = CollectionHealth()
        assertNull(health.interruptionBefore(5_000_000L))
    }

    @Test
    fun `un reloj que retrocede no cuenta como hueco`() {
        val health = CollectionHealth()
        health.restore(lastSuccessMs = 5_000_000L)
        // Cambio de hora de octubre: el reloj de pared retrocede una hora.
        assertNull(health.gapSince(1_400_000L))
        assertNull(health.interruptionBefore(1_400_000L))
    }

    // ── Casos forenses ───────────────────────────────────────────────────────────────────────

    @Test
    fun `un caso cerrado por tiempo no se reabre sobre la misma celda`() {
        val identidad = "214-07-31601-123456-LTE"
        val bloqueo = ForensicCasePolicy.nextLock(
            previousLock = null, identity = identidad, phase = 2, closedByTimeout = true
        )
        assertEquals(identidad, bloqueo)
        assertFalse(
            "La anomalía sigue activa, pero ya está documentada por el caso anterior",
            ForensicCasePolicy.shouldOpenCase(phase = 2, hasOpenCase = false, lockedIdentity = bloqueo, identity = identidad)
        )
    }

    @Test
    fun `una recuperacion real levanta el bloqueo`() {
        val identidad = "214-07-31601-123456-LTE"
        val trasRecuperar = ForensicCasePolicy.nextLock(
            previousLock = identidad, identity = identidad, phase = 0, closedByTimeout = false
        )
        assertNull(trasRecuperar)
        assertTrue(
            ForensicCasePolicy.shouldOpenCase(phase = 1, hasOpenCase = false, lockedIdentity = trasRecuperar, identity = identidad)
        )
    }

    @Test
    fun `cambiar de celda levanta el bloqueo`() {
        val bloqueada = "214-07-31601-123456-LTE"
        val otra = "214-07-31601-999999-LTE"
        assertTrue(
            ForensicCasePolicy.shouldOpenCase(phase = 1, hasOpenCase = false, lockedIdentity = bloqueada, identity = otra)
        )
        assertNull(ForensicCasePolicy.nextLock(bloqueada, otra, phase = 1, closedByTimeout = false))
    }

    @Test
    fun `no se abre un caso sin fase ni habiendo ya uno abierto`() {
        val id = "214-07-31601-123456-LTE"
        assertFalse(ForensicCasePolicy.shouldOpenCase(phase = 0, hasOpenCase = false, lockedIdentity = null, identity = id))
        assertFalse(ForensicCasePolicy.shouldOpenCase(phase = 2, hasOpenCase = true, lockedIdentity = null, identity = id))
    }

    // ── Export ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `un export incompleto no se da por bueno`() {
        var fallo: Throwable? = null
        try {
            ExportUtils.verifyRowCount(expected = 22_000, written = 9_000)
        } catch (e: Throwable) {
            fallo = e
        }
        assertTrue("Una discrepancia de filas debe fallar", fallo is IllegalStateException)
        assertTrue(fallo!!.message!!.contains("22000") || fallo.message!!.contains("22.000") ||
            fallo.message!!.contains("22"))
    }

    @Test
    fun `un export completo pasa la verificacion`() {
        ExportUtils.verifyRowCount(expected = 1_234, written = 1_234)
    }

    @Test
    fun `cada fila del csv tiene tantas columnas como la cabecera`() {
        val fila = ExportUtils.csvRow(
            HistoryRecord(
                timestamp = "2026-10-25 02:30:00", netType = "4G LTE", cid = "123456",
                mnc = "07", tac = "31601", mcc = "214", dbm = -85, radio = RadioTech.LTE
            )
        )
        assertEquals(ExportUtils.CSV_HEADER.split(",").size, fila.split(",").size)
    }

    @Test
    fun `una razon con comas no descuadra la fila`() {
        val fila = ExportUtils.csvRow(
            HistoryRecord(
                timestamp = "2026-10-25 02:30:00", netType = "4G LTE", cid = "123456",
                mnc = "07", tac = "31601", mcc = "214", dbm = -85, radio = RadioTech.LTE,
                failedHeuristics = "Celda aislada | Salto potencia (>35dB), sospechoso"
            )
        )
        // El campo va entrecomillado, así que la coma interna no cuenta como separador.
        assertEquals(ExportUtils.CSV_HEADER.split(",").size + 1, fila.split(",").size)
        assertTrue(fila.contains("\"Celda aislada | Salto potencia (>35dB), sospechoso\""))
    }
}
