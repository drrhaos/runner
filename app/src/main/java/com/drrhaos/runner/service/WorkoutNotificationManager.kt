package com.drrhaos.runner.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.drrhaos.runner.MainActivity
import com.drrhaos.runner.R
import com.drrhaos.runner.data.WorkoutSession
import com.drrhaos.runner.util.FormatUtils

/**
 * Manages the foreground notification for workout tracking.
 *
 * Responsibilities:
 *  - Create and manage the notification channel
 *  - Build notification with current workout stats (time, distance)
 *  - Throttle notification updates to save battery
 */
class WorkoutNotificationManager(private val service: Service) {

    companion object {
        const val NOTIFICATION_UPDATE_INTERVAL_MS = 5000L
    }

    private var lastNotificationUpdateTime: Long = 0

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

            val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Build a notification with the current workout statistics.
     */
    fun buildNotification(session: WorkoutSession): Notification {
        val intent = Intent(service, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TRACKING, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            service, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeText = FormatUtils.formatTime(session.currentTime)
        val distanceText = String.format("%.2f %s", session.distance, service.getString(R.string.unit_km))

        return NotificationCompat.Builder(service, WorkoutTrackingService.CHANNEL_ID)
            .setContentTitle(service.getString(R.string.app_name))
            .setContentText(service.getString(R.string.notification_workout_format, timeText, distanceText))
            .setSmallIcon(R.drawable.ic_menu_run)
            .setContentIntent(pendingIntent)
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
            if (now - lastNotificationUpdateTime < NOTIFICATION_UPDATE_INTERVAL_MS) {
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
}
