package com.alexisgordr.icdetector.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.icdetector.core.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 3.0 (G2) — Familiaridad de ruta con el helper real: directo y Geometry dicen lo mismo. */
@RunWith(AndroidJUnit4::class)
class MobilityFamiliarityStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CellDbHelper
    private val moving = MotionEvidence(MotionState.MOVING)
    private val still = MotionEvidence(MotionState.STATIC_CONFIRMED)

    @Before fun before() { context.deleteDatabase(CellDbHelper.DATABASE_NAME); db = CellDbHelper(context); db.writableDatabase }
    @After fun after() { db.close(); context.deleteDatabase(CellDbHelper.DATABASE_NAME) }

    /** Un viaje A→B→C confirmado (quieto 3 min en C). */
    private fun trip(id: String, base: Long) {
        val e = MobilityFamiliarityEngine(db, MobilityFamiliarityConfig(), true) { id }
        e.recover(base)
        e.observe("A", moving, base); e.observe("B", moving, base + 1_000); e.observe("C", moving, base + 2_000)
        e.observe("C", still, base + 3_000); e.observe("C", still, base + 3_000 + 180_000)
    }

    @Test fun historicalCountsAndScreenMatchTheLiveEvaluation() {
        repeat(3) { trip("t$it", it * 1_000_000L) }
        assertEquals(mapOf(MobilityEdge("A", "B") to 3, MobilityEdge("B", "C") to 3), db.historicalEdgeCounts("B"))
        val live = MobilityFamiliarityEngine(db, MobilityFamiliarityConfig(), true) { "live" }
        live.recover(9_000_000L)
        live.observe("A", moving, 9_000_000L)
        val atB = live.observe("B", moving, 9_001_000L)
        assertEquals(MobilityFamiliarity.KNOWN_ON_ROUTE, atB.familiarity)
        assertEquals(3, atB.arrivalPriorTrips)
        val snapshot = db.getMobilityGeometrySnapshot()
        assertEquals(atB.familiarity, snapshot.currentCell?.familiarity)
        assertEquals(atB.goodEdges, snapshot.currentCell?.goodEdges)
        assertEquals(atB.familiarity, snapshot.cells.single { it.identity == "B" }.familiarity)
        assertEquals("el viaje abierto no cuenta", 3, db.historicalEdgeCounts("B")[MobilityEdge("A", "B")])
    }
}
