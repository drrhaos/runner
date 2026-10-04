package com.runner.academy.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationManagerCompat
import com.runner.academy.util.GpsDiagnostics
import com.runner.academy.util.GpsDiagnostics.DiagFix
import com.runner.academy.util.GpsDiagnostics.DiagSatellite
import com.runner.academy.util.GpsDiagnostics.Event
import com.runner.academy.util.GpsDiagnostics.FixResult
import com.runner.academy.util.GpsDiagnostics.GnssSummary
import com.runner.academy.data.GpsDiagnosticsStore
import com.runner.academy.util.GpsLocationClient
import com.runner.academy.util.PowerSaveCheck
import java.io.BufferedWriter
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Writes a workout's raw GPS fixes, satellite summaries, session events and battery saver
 * state to a [GpsDiagnostics] file (see [GpsDiagnosticsStore]). Enabled in settings;
 * off by default.
 *
 * All file I/O runs on one background thread, so callers on the main thread never block.
 * Lines are flushed in small batches: after a process kill at most a few seconds are lost.
 */
class GpsDiagnosticsRecorder(
    context: Context,
    private val store: GpsDiagnosticsStore
) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "gps-diagnostics") }

    // Touched only on [io]
    private var writer: BufferedWriter? = null
    private var bytesWritten = 0L
    private var linesSinceFlush = 0
    private var limitReached = false
    private var lastGnssLineAt = 0L

    @Volatile
    private var isRecording: Boolean = false

    private val gnssCallback = object : GnssStatusCompat.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastGnssLineAt < GNSS_MIN_INTERVAL_MS) return // runs on [io]
            lastGnssLineAt = now
            val satellites = (0 until status.satelliteCount).map { i ->
                DiagSatellite(status.getConstellationType(i), status.getCn0DbHz(i), status.usedInFix(i))
            }
            writeNow(GpsDiagnostics.gnssLine(now, GnssSummary.of(satellites)))
        }
    }

    /** Battery saver can cut GPS with the screen off: record every change. */
    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isRecording) recordPowerState()
        }
    }

    /**
     * Starts (or, after a process restart, continues with [resume]) the recording for the
     * session that began at [startTimeMs].
     */
    @SuppressLint("MissingPermission")
    fun start(startTimeMs: Long, resume: Boolean) {
        if (isRecording) return
        isRecording = true
        val file = store.sessionFile(startTimeMs)
        io.execute {
            try {
                file.parentFile?.mkdirs()
                val continuing = resume && file.isFile
                bytesWritten = if (continuing) file.length() else 0L
                limitReached = bytesWritten >= MAX_FILE_BYTES
                // A kill mid-flush can leave half a line: start on a fresh line so the
                // "restored" marker is not glued to it and dropped by the parser
                val needsNewline = continuing && file.length() > 0 && lastByte(file) != '\n'.code
                writer = FileOutputStream(file, continuing).bufferedWriter().also {
                    if (needsNewline) it.newLine()
                }
                if (!continuing) {
                    writeNow(GpsDiagnostics.headerLine(startTimeMs, appVersion(), Build.VERSION.SDK_INT))
                }
                writeNow(
                    GpsDiagnostics.eventLine(
                        SystemClock.elapsedRealtime(),
                        if (continuing) Event.RESTORED else Event.START
                    )
                )
                writeNow(GpsDiagnostics.powerLine(SystemClock.elapsedRealtime(), PowerSaveCheck.read(appContext)))
            } catch (e: IOException) {
                android.util.Log.w(TAG, "Cannot open diagnostics file", e)
                writer = null
            }
        }
        if (GpsLocationClient.hasPrecisePermission(appContext)) {
            try {
                LocationManagerCompat.registerGnssStatusCallback(locationManager, io, gnssCallback)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "GNSS status unavailable", e)
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * [reason] is the filter's reject reason name; [steps] / [cadence] the workout's at this fix.
     */
    fun recordFix(
        location: Location,
        result: FixResult,
        reason: String? = null,
        steps: Int? = null,
        cadence: Float? = null
    ) {
        if (!isRecording) return
        val fix = DiagFix(
            elapsedMs = location.elapsedRealtimeNanos / 1_000_000L,
            timeMs = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else null,
            speed = if (location.hasSpeed()) location.speed else null,
            bearing = if (location.hasBearing()) location.bearing else null,
            altitude = if (location.hasAltitude()) location.altitude else null,
            provider = location.provider ?: "unknown",
            isMock = LocationCompat.isMock(location),
            steps = steps,
            cadence = cadence
        )
        io.execute { writeNow(GpsDiagnostics.fixLine(fix, result, reason)) }
    }

    fun recordEvent(event: Event) {
        if (!isRecording) return
        val at = SystemClock.elapsedRealtime()
        io.execute { writeNow(GpsDiagnostics.eventLine(at, event)) }
    }

    private fun recordPowerState() {
        val at = SystemClock.elapsedRealtime()
        val status = PowerSaveCheck.read(appContext)
        io.execute { writeNow(GpsDiagnostics.powerLine(at, status)) }
    }

    /**
     * Ends the recording; the file stays as `session-*` until the workout is saved.
     * [reason] tells a real stop apart from the system destroying the service mid-workout.
     */
    fun stop(reason: Event = Event.STOP) {
        if (!isRecording) return
        recordEvent(reason)
        isRecording = false
        try {
            LocationManagerCompat.unregisterGnssStatusCallback(locationManager, gnssCallback)
        } catch (_: Exception) {
            // never registered
        }
        try {
            appContext.unregisterReceiver(powerSaveReceiver)
        } catch (_: IllegalArgumentException) {
            // never registered
        }
        io.execute {
            try {
                writer?.close()
            } catch (_: IOException) {
                // nothing left to save
            }
            writer = null
        }
    }

    /** Runs on [io]. */
    private fun writeNow(line: String) {
        val out = writer ?: return
        if (limitReached) return
        try {
            out.write(line)
            out.newLine()
            bytesWritten += line.length + 1
            if (bytesWritten >= MAX_FILE_BYTES) {
                limitReached = true
                out.write(GpsDiagnostics.eventLine(SystemClock.elapsedRealtime(), Event.SIZE_LIMIT))
                out.newLine()
            }
            if (++linesSinceFlush >= FLUSH_EVERY_LINES || limitReached) {
                out.flush()
                linesSinceFlush = 0
            }
        } catch (e: IOException) {
            android.util.Log.w(TAG, "Diagnostics write failed", e)
        }
    }

    /** Stops the I/O thread after pending writes; call once when the owner is destroyed. */
    fun release() {
        stop(Event.SERVICE_DESTROYED)
        io.shutdown()
    }

    private fun lastByte(file: java.io.File): Int =
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(raf.length() - 1)
            raf.read()
        }

    private fun appVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    companion object {
        private const val TAG = "GpsDiagnostics"
        private const val GNSS_MIN_INTERVAL_MS = 1_000L
        private const val FLUSH_EVERY_LINES = 20

        /** About 2–3 hours of 1 Hz fixes plus satellite summaries. */
        const val MAX_FILE_BYTES = 30L * 1024 * 1024
    }
}
