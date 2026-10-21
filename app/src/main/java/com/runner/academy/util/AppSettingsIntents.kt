package com.runner.academy.util

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.fragment.app.Fragment

private const val TAG = "AppSettingsIntents"

/** The app's page in system settings: where a permission refused for good can be granted. */
fun Fragment.openAppSystemSettings() {
    openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null)))
}

/** The system location switch (Bluetooth scans need it on Android 11 and below). */
fun Fragment.openLocationSettings() {
    openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
}

private fun Fragment.openSettings(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: Exception) {
        Log.w(TAG, "Settings unavailable (${intent.action}): ${e.message}")
    }
}
