package com.runner.academy.service

import android.app.NotificationManager
import android.content.Context
import com.runner.academy.MainActivity
import com.runner.academy.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutNotificationManagerTest {

    private lateinit var service: WorkoutTrackingService
    private lateinit var system: NotificationManager
    private lateinit var manager: WorkoutNotificationManager

    @Before
    fun setUp() {
        // get() without create(): only the Service context is needed
        service = Robolectric.buildService(WorkoutTrackingService::class.java).get()
        system = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager = WorkoutNotificationManager(service)
        manager.createNotificationChannel()
    }

    @Test
    fun interruptedAlert_hasItsOwnChannelWithDefaultImportance() {
        val alerts = system.getNotificationChannel(WorkoutNotificationManager.INTERRUPTED_CHANNEL_ID)
        assertNotNull(alerts)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, alerts.importance)
        assertEquals(service.getString(R.string.notification_interrupted_channel_name), alerts.name)
        // The ongoing tracking notification stays quiet
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            system.getNotificationChannel(WorkoutTrackingService.CHANNEL_ID).importance
        )
    }

    @Test
    fun showInterrupted_postsAlertThatOpensTracking() {
        manager.showInterruptedNotification()

        val posted = shadowOf(system).getNotification(WorkoutNotificationManager.INTERRUPTED_NOTIFICATION_ID)
        assertNotNull(posted)
        assertEquals(WorkoutNotificationManager.INTERRUPTED_CHANNEL_ID, posted.channelId)
        val shadow = shadowOf(posted)
        assertEquals(service.getString(R.string.notification_interrupted_title), shadow.contentTitle)
        assertEquals(service.getString(R.string.notification_interrupted_text), shadow.contentText)
        val opened = shadowOf(posted.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, opened.component?.className)
        assertTrue(opened.getBooleanExtra(MainActivity.EXTRA_OPEN_TRACKING, false))
    }

    @Test
    fun cancelInterrupted_removesAlert() {
        manager.showInterruptedNotification()
        manager.cancelInterruptedNotification()

        assertNull(shadowOf(system).getNotification(WorkoutNotificationManager.INTERRUPTED_NOTIFICATION_ID))
    }

    @Test
    fun showInterrupted_withNotificationsDisabled_postsNothing() {
        shadowOf(system).setNotificationsEnabled(false)

        manager.showInterruptedNotification()

        assertNull(shadowOf(system).getNotification(WorkoutNotificationManager.INTERRUPTED_NOTIFICATION_ID))
    }
}
