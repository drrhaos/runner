package com.runner.academy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.runner.academy.MainActivity
import com.runner.academy.R
import com.runner.academy.data.WorkoutSession
import com.runner.academy.util.FormatUtils

/**
 * Manages the foreground notification for workout tracking.
 *
 * Responsibilities:
 *  - Create and manage the notification channel
 *  - Build notification with current workout stats (time, distance)
 *  - Throttle notification updates to save battery
 *  - Alert the user when a restore after process death could not resume tracking
 */
class WorkoutNotificationManager(private val service: Service) {

    companion object {
        const val NOTIFICATION_UPDATE_INTERVAL_MS = 5_000L
        const val NOTIFICATION_UPDATE_INTERVAL_SCREEN_OFF_MS = 15_000L
        /** Separate from the quiet tracking channel: this alert must be noticed. */
        const val INTERRUPTED_CHANNEL_ID = "WorkoutInterruptedChannel"
        const val INTERRUPTED_NOTIFICATION_ID = 2
        private const val TAG = "WorkoutNotificationMgr"
    }

    private var lastNotificationUpdateTime: Long = 0
    private var minUpdateIntervalMs: Long = NOTIFICATION_UPDATE_INTERVAL_MS

    fun setScreenInteractive(interactive: Boolean) {
        minUpdateIntervalMs = if (interactive) {
            NOTIFICATION_UPDATE_INTERVAL_MS
        } else {
            NOTIFICATION_UPDATE_INTERVAL_SCREEN_OFF_MS
        }
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                WorkoutTrackingService.CHANNEL_ID,
                service.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = service.getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val interruptedChannel = NotificationChannel(
                INTERRUPTED_CHANNEL_ID,
                service.getString(R.string.notification_interrupted_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = service.getString(R.string.notification_interrupted_channel_description)
            }

            val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            notificationManager.createNotificationChannel(interruptedChannel)
        }
    }

    /** Opens the tracking screen; binding to the service there restores the workout. */
    private fun openTrackingIntent(): PendingIntent {
        val intent = Intent(service, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TRACKING, true)
        }
        return PendingIntent.getActivity(
            service, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Build a notification with the current workout statistics.
     */
    fun buildNotification(session: WorkoutSession): Notification {
        // The big timer's time: moving (equal to the elapsed time without auto-pause)
        val timeText = FormatUtils.formatTime(session.movingTime)
        val distanceText = String.format("%.2f %s", session.distance, service.getString(R.string.unit_km))
        val format = when {
            session.isPaused -> R.string.notification_workout_paused_format
            session.autoPaused -> R.string.notification_workout_auto_paused_format
            else -> R.string.notification_workout_format
        }

        return NotificationCompat.Builder(service, WorkoutTrackingService.CHANNEL_ID)
            .setContentTitle(service.getString(R.string.app_name))
            .setContentText(service.getString(format, timeText, distanceText))
            .setSmallIcon(R.drawable.ic_menu_run)
            .setContentIntent(openTrackingIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .build()
    }

    /**
     * Update the foreground notification with optional throttling.
     *
     * @param session Current workout session data for display.
     * @param force If true, updates immediately regardless of throttling.
     *              Use for state changes (start, pause, resume, stop).
     */
    fun updateNotification(session: WorkoutSession, force: Boolean = false) {
        if (!force) {
            val now = System.currentTimeMillis()
            if (now - lastNotificationUpdateTime < minUpdateIntervalMs) {
                return
            }
            lastNotificationUpdateTime = now
        } else {
            lastNotificationUpdateTime = System.currentTimeMillis()
        }
        val notification = buildNotification(session)
        val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(WorkoutTrackingService.NOTIFICATION_ID, notification)
    }

    /**
     * Tells the user that tracking stopped after a failed restore (the workout itself is kept
     * on disk) and that opening the app continues it. Only logs if notifications are off.
     */
    fun showInterruptedNotification() {
        val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!NotificationManagerCompat.from(service).areNotificationsEnabled() ||
            isInterruptedChannelBlocked(notificationManager)
        ) {
            android.util.Log.w(TAG, "Notifications disabled: cannot tell the user the workout was interrupted")
            return
        }
        val notification = NotificationCompat.Builder(service, INTERRUPTED_CHANNEL_ID)
            .setContentTitle(service.getString(R.string.notification_interrupted_title))
            .setContentText(service.getString(R.string.notification_interrupted_text))
            .setSmallIcon(R.drawable.ic_menu_run)
            .setContentIntent(openTrackingIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()
        try {
            notificationManager.notify(INTERRUPTED_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "Cannot post interrupted-workout notification", e)
        }
    }

    /** The workout runs in the foreground again: the "interrupted" alert is stale. */
    fun cancelInterruptedNotification() {
        val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(INTERRUPTED_NOTIFICATION_ID)
    }

    private fun isInterruptedChannelBlocked(notificationManager: NotificationManager): Boolean =
        notificationManager.getNotificationChannel(INTERRUPTED_CHANNEL_ID)?.importance ==
            NotificationManager.IMPORTANCE_NONE
}
