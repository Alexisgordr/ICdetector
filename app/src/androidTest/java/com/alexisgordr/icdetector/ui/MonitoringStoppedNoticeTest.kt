package com.alexisgordr.icdetector.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.alexisgordr.icdetector.R
import com.alexisgordr.icdetector.core.MonitoringStop
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 3.1.0 (#42) — La pantalla principal enseña el monitoreo detenido y deja reanudarlo. */
class MonitoringStoppedNoticeTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun showsTheStoppedStateWithTheExceptionClassAndResumes() {
        var resumed = 0
        compose.setContent {
            MonitoringStoppedNotice(MonitoringStop.Stopped(1L, "SecurityException"), onResume = { resumed++ })
        }
        compose.onNodeWithText(context.getString(R.string.monitoring_stopped_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.monitoring_stopped_body, "SecurityException")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.monitoring_stopped_resume)).performClick()
        assertEquals(1, resumed)
    }

    @Test fun showsNothingWhileMonitoringRuns() {
        compose.setContent { MonitoringStoppedNotice(null) }
        compose.onNodeWithText(context.getString(R.string.monitoring_stopped_title)).assertDoesNotExist()
    }
}
