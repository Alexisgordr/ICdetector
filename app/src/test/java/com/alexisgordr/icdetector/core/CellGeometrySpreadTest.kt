package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellLocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0 — Muestras del perfil repartidas entre días distintos, no las 40 más recientes. */
class CellGeometrySpreadTest {
    private fun s(lat: Double) = CellLocationSample(lat, -1.6)

    @Test fun `each day gets a turn, newest day first`() {
        val rows = listOf(
            "2026-10-03" to s(3.0), "2026-10-03" to s(3.1), "2026-10-03" to s(3.2),
            "2026-10-02" to s(2.0),
            "2026-10-01" to s(1.0), "2026-10-01" to s(1.1)
        )
        assertEquals(listOf(3.0, 3.1, 2.0, 1.0, 1.1), CellGeometry.spreadAcrossDays(rows, 5).map { it.latitude })
        assertEquals(6, CellGeometry.spreadAcrossDays(rows, 40).size)
        assertEquals(listOf(3.0, 2.0), CellGeometry.spreadAcrossDays(rows, 2).map { it.latitude })
        assertTrue(CellGeometry.spreadAcrossDays(emptyList(), 40).isEmpty())
    }

    @Test fun `inside a day the samples are spaced along it, not the newest ones`() {
        val day = (0 until 10).map { "2026-10-01" to s(it.toDouble()) }
        assertEquals(listOf(0.0, 5.0), CellGeometry.spreadAcrossDays(day, 2).map { it.latitude })
        assertEquals(listOf(0.0, 3.0, 6.0), CellGeometry.spreadAcrossDays(day, 3).map { it.latitude })
    }

    @Test fun `one long recent stay elsewhere no longer takes over the profile`() {
        // 20 días en casa (5 filas al día) y hoy una tarde larga a 2 km (60 filas). Con las 40
        // más recientes, el centro se iba a ese sitio y la casa desaparecía del perfil.
        val home = 42.0
        val away = 42.0 + 2_000.0 / 111_195.0
        val rows = buildList {
            repeat(60) { add("2026-10-21" to s(away)) }
            for (d in 20 downTo 1) repeat(5) { add("2026-10-%02d".format(d) to s(home)) }
        }
        val newest = CellGeometry.profile(rows.take(40).map { it.second })
        val spread = CellGeometry.profile(CellGeometry.spreadAcrossDays(rows, 40))
        assertEquals(away, newest.latitude, 1e-9)
        assertEquals(home, spread.latitude, 1e-9)
    }

    @Test fun `both profile queries spread across days with the same limit`() {
        val db = listOf(File("app/src/main/java"), File("src/main/java")).first { it.exists() }
            .resolve("com/alexisgordr/icdetector/storage/CellDbHelper.kt").readText()
        val perCell = db.substringAfter("fun getCellLocationSamples(").substringBefore("\n    }\n")
        val all = db.substringAfter("fun getAllCellLocationSamples(").substringBefore("\n    }\n")
        listOf(perCell, all).forEach { body ->
            assertTrue(body.contains("substr(\$COLUMN_TIMESTAMP,1,10)"))
            assertTrue(body.contains("CellGeometry.spreadAcrossDays("))
            assertTrue(body.contains("CellGeometry.PROFILE_SCAN_ROWS"))
        }
        assertEquals(2_000, CellGeometry.PROFILE_SCAN_ROWS)
    }
}
