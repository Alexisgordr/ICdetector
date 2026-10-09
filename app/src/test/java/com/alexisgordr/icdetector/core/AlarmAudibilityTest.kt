package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.AlarmAudibility.AlarmSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (#35) — Bug found during testing: la notificación fija pitaba en cada actualización, el
 * usuario silenciaba la app y las alarmas confirmadas dejaban de sonar sin que nada lo dijera.
 */
class AlarmAudibilityTest {

    private fun source(path: String) = listOf(
        File("src/main/java/com/alexisgordr/icdetector/$path"),
        File("app/src/main/java/com/alexisgordr/icdetector/$path")
    ).first { it.exists() }.readText()

    @Test fun `alert channel with sound is audible`() {
        assertEquals(AlarmSound.AUDIBLE, AlarmAudibility.evaluate(true, 4))   // IMPORTANCE_HIGH
        assertEquals(AlarmSound.AUDIBLE, AlarmAudibility.evaluate(true, 3))   // IMPORTANCE_DEFAULT
    }

    @Test fun `a channel not created yet is audible, because it is created with sound`() {
        assertEquals(AlarmSound.AUDIBLE, AlarmAudibility.evaluate(true, null))
    }

    @Test fun `a silent or minimal alert channel is reported as silenced`() {
        assertEquals(AlarmSound.SILENCED, AlarmAudibility.evaluate(true, 2))  // IMPORTANCE_LOW
        assertEquals(AlarmSound.SILENCED, AlarmAudibility.evaluate(true, 1))  // IMPORTANCE_MIN
    }

    @Test fun `disabled notifications or a blocked channel are reported as blocked`() {
        assertEquals(AlarmSound.BLOCKED, AlarmAudibility.evaluate(false, 4))
        assertEquals(AlarmSound.BLOCKED, AlarmAudibility.evaluate(false, null))
        assertEquals(AlarmSound.BLOCKED, AlarmAudibility.evaluate(true, 0))   // IMPORTANCE_NONE
    }

    @Test fun `the monitoring notification never alerts on its updates`() {
        val controller = source("service/ServiceNotificationController.kt")
        val foreground = controller.substringAfter("fun foregroundNotification(")
            .substringBefore("fun notifyForeground(")
        assertTrue(foreground.contains(".setOnlyAlertOnce(true)"))
        assertTrue(foreground.contains(".setSilent(true)"))
        // Las alarmas confirmadas siguen en su propio canal, que nace con sonido.
        assertTrue(Regex("ALERT_CHANNEL_ID,\\s*context.getString\\(R.string.security_alerts_channel\\),\\s*NotificationManager.IMPORTANCE_HIGH")
            .containsMatchIn(controller))
        val alarm = controller.substringAfter("fun showConfirmedAlarm(").substringBefore("companion object")
        assertTrue(alarm.contains("NotificationCompat.Builder(context, ALERT_CHANNEL_ID)"))
    }

    @Test fun `the main screen warns when alarms are muted and rechecks on resume`() {
        val screen = source("ui/MainScreen.kt")
        assertTrue(screen.contains("AlarmMutedWarning()"))
        val warning = screen.substringAfter("private fun AlarmMutedWarning()")
        assertTrue(warning.contains("LifecycleResumeEffect"))
        assertTrue(warning.contains("ServiceNotificationController.alarmSettingsIntent(context)"))
    }
}
