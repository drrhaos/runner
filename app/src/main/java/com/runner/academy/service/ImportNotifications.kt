package com.runner.academy.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.navigation.NavDeepLinkBuilder
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.runner.academy.MainActivity
import com.runner.academy.R
import com.runner.academy.data.ImportResult
import com.runner.academy.ui.workout.ImportResultText
import java.util.UUID

/** The import's notifications: its progress (with Stop) while it runs, and how it ended. */
class ImportNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun progress(workId: UUID, done: Int, total: Int): ForegroundInfo {
        createChannel()
        val text = if (total == 0) {
            context.getString(R.string.workout_list_import_progress)
        } else {
            context.getString(R.string.workout_list_import_progress_count, done, total)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.import_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_menu_import)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(deepLink(R.id.nav_workouts))
            .addAction(
                0,
                context.getString(R.string.import_notification_stop),
                WorkManager.getInstance(context).createCancelPendingIntent(workId)
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_ID, notification)
        }
    }

    /** Updates the foreground notification WorkManager shows for the running import. */
    fun updateProgress(workId: UUID, done: Int, total: Int) {
        notify(PROGRESS_ID) { progress(workId, done, total).notification }
    }

    /** Removes the progress, whatever the end: an ongoing notification nothing else would clear. */
    fun cancelProgress() {
        manager.cancel(PROGRESS_ID)
    }

    /** How it ended; with workouts waiting it leads to them. */
    fun finished(result: ImportResult) {
        val lines = ImportResultText.lines(context, result)
        notify(DONE_ID) {
            createChannel()
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.import_notification_done))
                .setContentText(lines.first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                .setSmallIcon(R.drawable.ic_menu_import)
                .setAutoCancel(true)
                .setContentIntent(deepLink(if (result.pending > 0) R.id.nav_import_conflicts else R.id.nav_workouts))
                .build()
        }
    }

    /** Posts when the user allows notifications; without, the screen shows the same. */
    private fun notify(id: Int, build: () -> android.app.Notification) {
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, build())
        } catch (_: SecurityException) {
        }
    }

    private fun deepLink(destination: Int): PendingIntent = NavDeepLinkBuilder(context)
        .setComponentName(MainActivity::class.java)
        .setGraph(R.navigation.mobile_navigation)
        .setDestination(destination)
        .createPendingIntent()

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.import_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "WorkoutImportChannel"

        // The tracking service uses 1 and 2
        const val PROGRESS_ID = 3
        const val DONE_ID = 4
    }
}
