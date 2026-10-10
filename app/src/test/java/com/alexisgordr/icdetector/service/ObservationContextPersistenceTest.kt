package com.alexisgordr.icdetector.service

import com.alexisgordr.icdetector.core.ObservationContext
import com.alexisgordr.icdetector.core.ObservedFix
import com.alexisgordr.icdetector.models.CellData
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.ServiceRegistrationState
import com.alexisgordr.icdetector.models.ServiceStateSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (A03) — Bug found during testing: la hora, la posición y el estado de servicio de una fila
 * se leían al guardarla, después del análisis. Con un análisis lento, la lectura de celdas quedaba
 * con el contexto de un momento posterior. Ahora se captura al recibir la lectura.
 */
class ObservationContextPersistenceTest {
    private var now = 1_791_540_000_000L
    private var gps: ObservedFix? = ObservedFix(40.4168, -3.7038, 8f)
    private var service: ServiceStateSnapshot? = state(ServiceRegistrationState.IN_SERVICE, "21407")
    private val rows = mutableListOf<ObservationRow>()
    private var mode = "CONTINUOUS"

    private val cell = CellData(
        isRegistered = true, networkType = "4G LTE", cellId = "100", mnc = "07", tac = "1", dbm = -80,
        mcc = "214", radioTech = RadioTech.LTE
    )

    private fun state(s: ServiceRegistrationState, operator: String) =
        ServiceStateSnapshot(timestampMs = now, state = s, operatorNumeric = operator)

    private fun controller() = ObservationPersistenceController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        insert = { rows += it; rows.size.toLong() },
        location = { gps },
        serviceState = { service },
        onWrite = {},
        onPeriodicMissingLocation = {},
        writeDispatcher = Dispatchers.Unconfined,
        clock = { now },
        locationMode = { mode }
    )

    /** La lectura llega, el análisis se retiene y, mientras, cambian el GPS, el servicio y la hora. */
    private fun deliverThenChangeEverything(persistence: ObservationPersistenceController): ObservationContext {
        val atDelivery = persistence.capture()
        now += 45_000L
        gps = ObservedFix(41.3874, 2.1686, 30f)
        service = state(ServiceRegistrationState.OUT_OF_SERVICE, "21401")
        return atDelivery
    }

    private fun assertInitialContext(row: ObservationRow) {
        assertEquals(1_791_540_000_000L, row.context.observedAtMs)
        assertEquals(40.4168, row.context.fix!!.latitude, 0.0)
        assertEquals(-3.7038, row.context.fix!!.longitude, 0.0)
        assertEquals(8f, row.context.fix!!.accuracyM!!, 0f)
        assertEquals(ServiceRegistrationState.IN_SERVICE, row.context.service!!.state)
        assertEquals("21407", row.context.service!!.operatorNumeric)
    }

    @Test fun `a delayed periodic row keeps the context of its delivery`() {
        val persistence = controller()
        val context = deliverThenChangeEverything(persistence)
        persistence.recordPeriodicIfDue(cell, screenOn = true, context = context)
        assertInitialContext(rows.single())
    }

    @Test fun `a delayed handover row keeps the context of its delivery`() {
        val persistence = controller()
        val context = deliverThenChangeEverything(persistence)
        persistence.recordHandover(cell, context)
        assertInitialContext(rows.single())
    }

    @Test fun `a delayed confirmed alarm keeps the context of its delivery`() {
        val persistence = controller()
        val context = deliverThenChangeEverything(persistence)
        persistence.recordConfirmedAlarm(cell.copy(isSuspicious = true, suspiciousReason = "H6"), context)
        assertInitialContext(rows.single())
        assertEquals("H6", rows.single().reason)
    }

    @Test fun `each row keeps the location mode of its delivery`() {
        val persistence = controller()
        val context = persistence.capture()
        mode = "ADAPTIVE"                       // el usuario cambia de modo durante el análisis
        persistence.recordHandover(cell, context)
        assertEquals("CONTINUOUS", rows.single().context.locationMode)
        persistence.recordConfirmedAlarm(cell, persistence.capture())
        assertEquals("ADAPTIVE", rows.last().context.locationMode)
    }

    @Test fun `no fix at delivery stays unknown even if one arrives during the analysis`() {
        gps = null
        val persistence = controller()
        val context = persistence.capture()
        gps = ObservedFix(40.0, -3.0, 5f)
        persistence.recordHandover(cell, context)
        assertNull(rows.single().context.fix)
    }

    @Test fun `the periodic interval is measured in observation time`() {
        val persistence = controller()
        persistence.recordHandover(cell, persistence.capture())
        now += 60_000L
        persistence.recordPeriodicIfDue(cell, screenOn = true, context = persistence.capture())
        assertEquals("un minuto después no toca fila periódica", 1, rows.size)
        now += 5 * 60_000L
        persistence.recordPeriodicIfDue(cell, screenOn = true, context = persistence.capture())
        assertEquals(2, rows.size)
    }

    @Test fun `the service captures the context on delivery, before the analysis`() {
        val service = listOf(
            File("src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt"),
            File("app/src/main/java/com/alexisgordr/icdetector/service/MiniICService.kt")
        ).first { it.exists() }.readText()
        val capture = service.indexOf("val observationContext = observationPersistence.capture(deliveryLocation?.toObservedFix())")
        assertTrue(capture > 0)
        // El ciclo de análisis de esta entrega empieza después de capturar.
        val cycle = service.indexOf("scope.launch(Dispatchers.IO) {", capture)
        assertTrue(cycle > capture)
        assertTrue(service.indexOf("cellProcessingMutex.withLock {", cycle) > cycle)
        // El estado de servicio se sondea al principio de la entrega, antes de capturar.
        assertTrue(service.indexOf("telephonyController.pollServiceState()") < capture)
        // El análisis usa la misma posición que la fila.
        assertTrue(service.contains("val currentLocation = deliveryLocation"))
        assertTrue(service.contains("confirmed = true, context = observationContext)"))
        listOf("recordHandover(cell, context)", "recordPeriodicIfDue(cell, isScreenOn, context)",
            "securityAlerts.evaluate(cell, confirmed, context)").forEach { assertTrue(it, service.contains(it)) }
    }
}
