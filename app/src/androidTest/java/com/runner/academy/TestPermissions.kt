package com.runner.academy

import android.Manifest
import android.os.Build
import androidx.test.rule.GrantPermissionRule

/**
 * Location (and, on Android 13+, notification) permissions for tests that start the tracking
 * screen or the location foreground service: without them a system dialog blocks Espresso and
 * Android 14+ refuses to start a location foreground service.
 */
fun grantTrackingPermissions(): GrantPermissionRule = GrantPermissionRule.grant(
    *buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
)
