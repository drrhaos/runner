package com.runner.academy.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Pure decisions for the step permission (`ACTIVITY_RECOGNITION`, a runtime permission since
 * API 29 / Android 10; below that step sensors need nothing).
 */
object StepPermissionPolicy {

    const val RUNTIME_PERMISSION_SDK = Build.VERSION_CODES.Q

    fun needsRuntimePermission(sdkInt: Int): Boolean = sdkInt >= RUNTIME_PERMISSION_SDK

    /** Ask once, at the first workout start, and only if the user has not turned steps off. */
    fun shouldPromptAtWorkoutStart(
        sdkInt: Int,
        enabled: Boolean,
        askedBefore: Boolean,
        granted: Boolean
    ): Boolean = needsRuntimePermission(sdkInt) && enabled && !askedBefore && !granted

    fun isAllowed(sdkInt: Int, enabled: Boolean, granted: Boolean): Boolean =
        enabled && (granted || !needsRuntimePermission(sdkInt))
}

/** Android-facing checks for using the step sensor for distance when GPS is lost. */
object StepTrackingAccess {

    /** Steps may be used: enabled in settings and the permission is granted or not needed. */
    fun isStepTrackingAllowed(context: Context): Boolean = StepPermissionPolicy.isAllowed(
        Build.VERSION.SDK_INT,
        UserPreferences(context).stepsForDistanceEnabled,
        hasPermission(context)
    )

    fun needsRuntimePermission(): Boolean =
        StepPermissionPolicy.needsRuntimePermission(Build.VERSION.SDK_INT)

    /** True when `ACTIVITY_RECOGNITION` is granted, or not a runtime permission on this API. */
    fun hasPermission(context: Context): Boolean =
        !needsRuntimePermission() || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED

    fun shouldPromptAtWorkoutStart(context: Context): Boolean {
        val prefs = UserPreferences(context)
        return StepPermissionPolicy.shouldPromptAtWorkoutStart(
            Build.VERSION.SDK_INT,
            prefs.stepsForDistanceEnabled,
            prefs.stepPermissionAsked,
            hasPermission(context)
        )
    }

    /** The device has a step counter or step detector. */
    fun hasStepSensor(context: Context): Boolean {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null ||
            manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
    }
}
