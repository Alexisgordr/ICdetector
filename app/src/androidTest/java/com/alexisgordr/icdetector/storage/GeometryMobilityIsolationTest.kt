package com.alexisgordr.icdetector.storage

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.alexisgordr.icdetector.core.*
import com.alexisgordr.icdetector.ui.GeometryScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GeometryMobilityIsolationTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CellDbHelper

    @Before fun before() { context.deleteDatabase(NAME); db = CellDbHelper(context); db.writableDatabase }
    @After fun after() { db.close(); context.deleteDatabase(NAME) }

    @Test fun destroyingGeometryDoesNotCloseOrMutateServiceOwnedTrip() {
        val engine = MobilityFamiliarityEngine(db, MobilityFamiliarityConfig(), true) { "service-trip" }
        engine.observe("A", MotionEvidence(MotionState.MOVING), 1)
        val showGeometry = mutableStateOf(true)
        compose.setContent {
            if (showGeometry.value) GeometryScreen(db) else EmptyContent()
        }
        compose.waitForIdle()
        compose.runOnIdle { showGeometry.value = false }
        compose.waitForIdle()
        engine.observe("B", MotionEvidence(MotionState.MOVING), 2)
        engine.observe("C", MotionEvidence(MotionState.MOVING), 3)
        assertEquals("service-trip", db.openTrip()?.id)
        assertEquals(setOf(MobilityEdge("A", "B"), MobilityEdge("B", "C")), db.tripEdges("service-trip"))
    }

    @Test fun deleteHistoryAlsoClearsMobilityTrips() {
        val engine = MobilityFamiliarityEngine(db, MobilityFamiliarityConfig(), true) { "pre-reset-trip" }
        engine.observe("A", MotionEvidence(MotionState.MOVING), 1)
        engine.observe("B", MotionEvidence(MotionState.MOVING), 2)
        db.clear()
        assertEquals(null, db.openTrip())
        assertEquals(emptySet<String>(), db.tripCells("pre-reset-trip"))
        assertEquals(emptySet<MobilityEdge>(), db.tripEdges("pre-reset-trip"))
    }

    @Composable private fun EmptyContent() = Unit
    private companion object { const val NAME = "icdetector_history.db" }
}
