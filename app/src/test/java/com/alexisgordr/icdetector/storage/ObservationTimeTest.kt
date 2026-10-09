package com.alexisgordr.icdetector.storage

import com.alexisgordr.icdetector.models.HistoryRecord
import com.alexisgordr.icdetector.utils.ExportUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * 3.0 (#23) — El instante epoch no depende de la zona ni del cambio de hora; el texto local sí.
 */
class ObservationTimeTest {
    private val madrid = TimeZone.getTimeZone("Europe/Madrid")
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")

    // 25-10-2026: en Madrid las 02:30 locales ocurren dos veces (CEST y luego CET).
    private val firstHalfPast2 = 1_792_888_200_000L   // 2026-10-25T00:30:00Z
    private val secondHalfPast2 = 1_792_891_800_000L  // 2026-10-25T01:30:00Z

    @Test fun `the repeated autumn hour has the same local text but two instants`() {
        assertEquals("2026-10-25 02:30:00", ObservationTime.localText(firstHalfPast2, madrid))
        assertEquals("2026-10-25 02:30:00", ObservationTime.localText(secondHalfPast2, madrid))
        assertEquals("2026-10-25T00:30:00.000Z", ObservationTime.utcIso(firstHalfPast2))
        assertEquals("2026-10-25T01:30:00.000Z", ObservationTime.utcIso(secondHalfPast2))
        assertNotEquals(ObservationTime.utcIso(firstHalfPast2), ObservationTime.utcIso(secondHalfPast2))
    }

    @Test fun `a cutoff inside the repeated hour separates rows by their instant`() {
        val cutoff = firstHalfPast2 + 30 * 60_000L    // 01:00Z, entre las dos 02:30 locales
        assertFalse(ObservationTime.isAfter(firstHalfPast2, "2026-10-25 02:30:00", cutoff, madrid))
        assertTrue(ObservationTime.isAfter(secondHalfPast2, "2026-10-25 02:30:00", cutoff, madrid))
    }

    @Test fun `ages computed from the instant do not change when travelling across zones`() {
        val observed = 1_791_540_000_000L
        val now = observed + 6 * 3_600_000L
        val fromMadrid = now - ObservationTime.bestEffortMs(observed, ObservationTime.localText(observed, madrid), madrid)!!
        val fromTokyo = now - ObservationTime.bestEffortMs(observed, ObservationTime.localText(observed, madrid), tokyo)!!
        assertEquals(6 * 3_600_000L, fromMadrid)
        assertEquals(fromMadrid, fromTokyo)
    }

    @Test fun `rows before 3_0 keep the previous approximate comparison on their text`() {
        // Sin instante: misma lectura del texto local que antes de 3.0, nada inventado.
        val legacy = "2026-09-01 10:00:00"
        val parsed = ObservationTime.parseLegacy(legacy, madrid)!!
        assertEquals(parsed, ObservationTime.bestEffortMs(null, legacy, madrid))
        assertTrue(ObservationTime.isAfter(null, legacy, parsed - 1_000, madrid))
        assertFalse(ObservationTime.isAfter(null, legacy, parsed + 1_000, madrid))
        assertNull(ObservationTime.bestEffortMs(null, null, madrid))
        assertNull(ObservationTime.parseLegacy("not a date", madrid))
    }

    @Test fun `window clauses take the instant first and the text only for legacy rows`() {
        assertEquals(
            "(observed_at_ms>=? OR (observed_at_ms IS NULL AND timestamp>=?))",
            ObservationTime.sinceClause("observed_at_ms", "timestamp")
        )
        assertEquals(
            "(observed_at_ms<? OR (observed_at_ms IS NULL AND timestamp<?))",
            ObservationTime.beforeClause("observed_at_ms", "timestamp")
        )
        val args = ObservationTime.cutoffArgs(firstHalfPast2, madrid)
        assertEquals(listOf(firstHalfPast2.toString(), "2026-10-25 02:30:00"), args.toList())
    }

    @Test fun `the csv exports the instant and leaves it empty for legacy rows`() {
        val header = ExportUtils.CSV_HEADER.split(",")
        val column = header.indexOf("ObservedAtUtc")
        assertTrue(column > header.indexOf("NetworkRoaming"))
        val base = HistoryRecord(
            timestamp = "2026-10-25 02:30:00", netType = "4G", cid = "1", mnc = "07",
            tac = "1", mcc = "214", dbm = -90
        )
        val withInstant = ExportUtils.csvRow(base.copy(observedAtMs = secondHalfPast2)).split(",")
        val legacy = ExportUtils.csvRow(base).split(",")
        assertEquals(header.size, withInstant.size)
        assertEquals(header.size, legacy.size)
        assertEquals("2026-10-25T01:30:00.000Z", withInstant[column])
        assertEquals("", legacy[column])
    }
}
