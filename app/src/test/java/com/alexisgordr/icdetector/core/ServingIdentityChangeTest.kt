package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.ServingIdentityChange.Identity
import com.alexisgordr.icdetector.models.RadioTech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 (#19) — Un handover es un cambio de la identidad completa de la servidora. */
class ServingIdentityChangeTest {
    private val lte = Identity("214", "07", "31601", "100", RadioTech.LTE)

    @Test fun `the first serving cell is a handover`() {
        assertTrue(ServingIdentityChange.isHandover(null, lte))
    }

    @Test fun `the same identity is not a handover`() {
        assertFalse(ServingIdentityChange.isHandover(lte, lte.copy()))
    }

    @Test fun `same cell id on another operator is a handover`() {
        assertTrue(ServingIdentityChange.isHandover(lte, lte.copy(mnc = "01")))
        assertTrue(ServingIdentityChange.isHandover(lte, lte.copy(mcc = "208")))
    }

    @Test fun `same cell id in another tracking area or technology is a handover`() {
        assertTrue(ServingIdentityChange.isHandover(lte, lte.copy(tac = "31602")))
        assertTrue(ServingIdentityChange.isHandover(lte, lte.copy(radio = RadioTech.NR)))
    }

    @Test fun `a field the modem did not fill is not a change`() {
        assertFalse(ServingIdentityChange.isHandover(lte, lte.copy(mcc = "N/A", mnc = "N/A")))
        assertFalse(ServingIdentityChange.isHandover(lte.copy(tac = ""), lte))
        assertFalse(ServingIdentityChange.isHandover(lte, lte.copy(radio = RadioTech.UNKNOWN)))
    }

    @Test fun `a different known cell id is always a handover`() {
        assertTrue(ServingIdentityChange.isHandover(lte, lte.copy(cellId = "101")))
    }

    @Test fun `h10 history stores the full identity`() {
        assertEquals("214-07-31601-100-LTE", lte.key)
        val service = listOf(
            File("src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt")
        ).first { it.exists() }.readText()
        assertTrue(service.contains("ServingIdentityChange.step(prevServingIdentity, servingIdentity)"))
        assertTrue(service.contains("cellChangeHistory.add(Pair(servingIdentity.key, currentTime))"))
        assertFalse(service.contains("if (cid != prevCid)"))
    }

    // ── 3.0 — Cell ID «N/A» transitorio ──────────────────────────────────────────────────

    /** Recorre las lecturas como el servicio: devuelve en qué lecturas hubo handover. */
    private fun handovers(vararg readings: Identity): List<Int> {
        var prev: Identity? = null
        return readings.withIndex().filter { (_, cur) ->
            val step = ServingIdentityChange.step(prev, cur)
            prev = step.next
            step.handover
        }.map { it.index }
    }

    @Test fun `a transient N-A Cell ID gives no handover in either direction`() {
        val na = lte.copy(cellId = "N/A")
        assertFalse(ServingIdentityChange.isHandover(lte, na))
        assertEquals("X, N/A, X: solo la primera lectura", listOf(0), handovers(lte, na, lte))
        assertEquals(listOf(0), handovers(lte, na, na, lte.copy(cellId = ""), lte))
    }

    @Test fun `a real change after an N-A reading is still a handover`() {
        val other = lte.copy(cellId = "999")
        assertEquals(listOf(0, 2), handovers(lte, lte.copy(cellId = "N/A"), other))
    }

    @Test fun `a first reading with N-A does not block later detection`() {
        val na = lte.copy(cellId = "N/A")
        assertEquals("la primera lectura legible abre el primer handover", listOf(1, 2), handovers(na, lte, lte.copy(cellId = "999")))
    }

    @Test fun `unknown fields of the previous identity are filled, known ones never overwritten`() {
        val partial = lte.copy(tac = "N/A", radio = RadioTech.UNKNOWN)
        val step = ServingIdentityChange.step(partial, lte)
        assertFalse(step.handover)
        assertEquals(lte, step.next)
        assertEquals(lte, ServingIdentityChange.step(lte, lte.copy(mcc = "N/A", cellId = "N/A")).next)
        // Rellenada la TAC, un cambio real de TAC después sí cuenta.
        assertEquals(listOf(0, 2), handovers(partial, lte, lte.copy(tac = "31602")))
    }

    @Test fun `the service follows step and does not reset the alarm episode without a handover`() {
        val src = listOf("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt", "src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt")
            .map(::File).first { it.exists() }.readText()
        val alerts = src.substringAfter("private fun checkAlerts(").substringBefore("private fun updateNotification(")
        assertTrue(alerts.contains("ServingIdentityChange.step(prevServingIdentity, servingIdentity)"))
        val handover = alerts.substringAfter("if (identityStep.handover) {").substringBefore("} else {")
        // 3.0 — onHandover() cierra el episodio salvo el de un ping-pong (AlarmEpisodeGate).
        assertTrue(handover.contains("securityAlerts.onHandover()"))
        assertFalse(alerts.substringBefore("if (identityStep.handover) {").contains("resetAlarmEpisode"))
        assertFalse(alerts.substringBefore("if (identityStep.handover) {").contains("onHandover"))
        assertFalse(alerts.contains("isHandover("))
    }

    @Test fun `a reading with an unreadable Cell ID fills nothing`() {
        val partial = lte.copy(tac = "N/A")
        val step = ServingIdentityChange.step(partial, lte.copy(cellId = "N/A", tac = "99999"))
        assertFalse(step.handover)
        assertEquals("el TAC de una lectura sin CID no se adopta", partial, step.next)
        // Así, la lectura legible siguiente con otro TAC no cuenta como handover inventado.
        assertEquals(listOf(0), handovers(partial, lte.copy(cellId = "N/A", tac = "99999"), lte))
    }

    @Test fun `latency follows the same identity rule`() {
        val src = listOf("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt", "src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt")
            .map(::File).first { it.exists() }.readText()
        val reset = src.substringAfter("private fun resetLatencyIfCellChanged(").substringBefore("private fun checkLatencyAnomaly(")
        assertTrue(reset.contains("ServingIdentityChange.step("))
        assertTrue(reset.contains("if (step.handover) {"))
        assertTrue("misma celda con un campo rellenado: se renombra", reset.contains("latencyMonitor.renameActiveCell(previousKey, nextKey)"))
        assertFalse("ya no compara la clave completa con el CID", reset.contains("cell.identityKey"))
        val check = src.substringAfter("private fun checkLatencyAnomaly(").substringBefore("private fun showLatencyAlert(")
        assertTrue(check.contains("hasReadableCellId(") && check.contains("latencyServingIdentity?.key ?: return"))
    }
}
