package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellConnectionState
import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.models.NetworkTypeNames
import com.alexisgordr.icdetector.models.RadioTech
import com.alexisgordr.icdetector.models.ServiceRegistrationState
import com.alexisgordr.icdetector.models.ServiceStateSnapshot
import com.alexisgordr.icdetector.models.ServiceStateSource
import com.alexisgordr.icdetector.utils.ExportUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.10.4 — Mapeos de la API, deduplicación de ServiceState y columnas nuevas del export. */
class RadioContextTest {

    private fun snapshot(
        state: ServiceRegistrationState = ServiceRegistrationState.IN_SERVICE,
        operator: String? = "21407",
        sim: String? = "21407",
        roaming: Boolean = false,
        bandwidths: List<Int> = listOf(20_000),
        channel: Int? = 1301
    ) = ServiceStateSnapshot(
        timestampMs = 1_790_000_000_000,
        state = state,
        dataRegistered = true,
        voiceRegistered = true,
        searching = false,
        roaming = roaming,
        operatorNumeric = operator,
        operatorAlphaLong = "Movistar",
        simOperator = sim,
        channelNumber = channel,
        cellBandwidthsKhz = bandwidths,
        dataNetworkType = "LTE",
        voiceNetworkType = "LTE",
        source = ServiceStateSource.CALLBACK
    )

    @Test
    fun `estado de conexion sigue los valores publicos de CellInfo`() {
        assertEquals(CellConnectionState.NONE, CellConnectionState.fromAndroid(0))
        assertEquals(CellConnectionState.PRIMARY_SERVING, CellConnectionState.fromAndroid(1))
        assertEquals(CellConnectionState.SECONDARY_SERVING, CellConnectionState.fromAndroid(2))
        assertEquals(CellConnectionState.UNKNOWN, CellConnectionState.fromAndroid(Int.MAX_VALUE))
    }

    @Test
    fun `estado de servicio sigue los valores publicos de ServiceState`() {
        assertEquals(ServiceRegistrationState.IN_SERVICE, ServiceRegistrationState.fromAndroid(0))
        assertEquals(ServiceRegistrationState.OUT_OF_SERVICE, ServiceRegistrationState.fromAndroid(1))
        assertEquals(ServiceRegistrationState.EMERGENCY_ONLY, ServiceRegistrationState.fromAndroid(2))
        assertEquals(ServiceRegistrationState.POWER_OFF, ServiceRegistrationState.fromAndroid(3))
        assertEquals(ServiceRegistrationState.UNKNOWN, ServiceRegistrationState.fromAndroid(-1))
    }

    @Test
    fun `nombres de tecnologia de acceso`() {
        assertEquals("LTE", NetworkTypeNames.name(13))
        assertEquals("NR", NetworkTypeNames.name(20))
        assertEquals("GSM", NetworkTypeNames.name(16))
        assertNull(NetworkTypeNames.name(0))
    }

    @Test
    fun `el tracker ignora repeticiones y cambios solo de ancho de banda`() {
        val tracker = ServiceStateTracker()
        assertNotNull(tracker.observe(snapshot()))
        assertNull(tracker.observe(snapshot()))
        assertNull(tracker.observe(snapshot(bandwidths = listOf(20_000, 10_000), channel = 6400)))
        val change = tracker.observe(snapshot(state = ServiceRegistrationState.EMERGENCY_ONLY))
        assertNotNull(change)
        assertEquals(ServiceRegistrationState.IN_SERVICE, change!!.previous!!.state)
    }

    @Test
    fun `estado de servicio se notifica solo despues de insertar y una sola vez`() {
        val persistence = ServiceStatePersistence()
        val rows = mutableListOf<ServiceStateSnapshot>()
        var observedRows = -1
        val event = snapshot()

        assertTrue(persistence.persist(event, { rows += it; rows.size.toLong() }) {
            observedRows = rows.size
        })
        assertEquals(1, observedRows)
        assertEquals(listOf(event), rows)
        assertFalse(persistence.persist(event, { rows += it; rows.size.toLong() }) {
            observedRows = rows.size
        })
        assertEquals(1, rows.size)
    }

    @Test
    fun `fallo de escritura no publica ni consume el cambio`() {
        val persistence = ServiceStatePersistence()
        var notifications = 0
        val event = snapshot()

        assertFalse(persistence.persist(event, { -1L }) { notifications++ })
        assertEquals(0, notifications)
        assertTrue(persistence.persist(event, { 1L }) { notifications++ })
        assertEquals(1, notifications)
    }

    @Test
    fun `lectura anterior a la ultima confirmada se descarta`() {
        val persistence = ServiceStatePersistence()
        val newer = snapshot().copy(timestampMs = 200L)
        val older = snapshot(state = ServiceRegistrationState.EMERGENCY_ONLY).copy(timestampMs = 100L)
        val rows = mutableListOf<ServiceStateSnapshot>()

        assertTrue(persistence.persist(newer, { rows += it; rows.size.toLong() }) {})
        assertFalse(persistence.persist(older, { rows += it; rows.size.toLong() }) {})
        assertEquals(listOf(newer), rows)
    }

    @Test
    fun `linea de terminal de un cambio`() {
        val line = ServiceStateTracker.terminalLine(
            ServiceStateChange(snapshot(), snapshot(state = ServiceRegistrationState.EMERGENCY_ONLY))
        )
        assertTrue(line.contains("EN SERVICIO → SOLO EMERGENCIAS"))
        assertTrue(line.contains("red=21407 (Movistar)"))
        assertTrue(line.contains("datos=LTE registrado"))
        assertFalse(line.contains("distinta de la SIM"))
    }

    @Test
    fun `red distinta de la SIM solo sin roaming`() {
        assertTrue(snapshot(operator = "21401", sim = "21407").operatorDiffersFromSim)
        assertFalse(snapshot(operator = "20801", sim = "21407", roaming = true).operatorDiffersFromSim)
        assertFalse(snapshot(operator = null).operatorDiffersFromSim)
    }

    @Test
    fun `csv de eventos de servicio tiene tantas columnas como su cabecera`() {
        val row = ExportUtils.serviceStateCsvRow(snapshot(bandwidths = listOf(20_000, 10_000)))
        assertEquals(ExportUtils.SERVICE_STATE_CSV_HEADER.split(",").size, row.split(",").size)
        assertTrue(row.contains("20000;10000"))
    }

    @Test
    fun `csv de historial incluye el contexto de radio al final`() {
        val row = ExportUtils.csvRow(
            HistoryRecord(
                timestamp = "2026-09-25 10:00:00", netType = "4G LTE", cid = "100", mnc = "07",
                tac = "31601", mcc = "214", dbm = -90, radio = RadioTech.LTE,
                connectionState = CellConnectionState.PRIMARY_SERVING, bandwidthKhz = 20_000,
                bands = "3", secondaryCarriers = "LTE:6400:200", serviceState = "IN_SERVICE",
                networkOperator = "21407", simOperator = "21407", networkRoaming = false
            )
        )
        val header = ExportUtils.CSV_HEADER.split(",")
        val cols = row.split(",")
        assertEquals(header.size, cols.size)
        assertEquals("Radio", header[22])
        assertEquals("PRIMARY_SERVING", cols[header.indexOf("ServingConnection")])
        assertEquals("LTE:6400:200", cols[header.indexOf("SecondaryCarriers")])
        assertEquals("0", cols[header.indexOf("NetworkRoaming")])
    }
}
