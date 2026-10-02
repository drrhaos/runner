package com.runner.academy.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat

/**
 * Thin wrapper over the platform [LocationManager] GPS provider — no Google Play Services,
 * no network location (it is backed by Google on stock phones).
 *
 * Batching ([LocationRequestCompat.getMaxUpdateDelayMillis]) and [flush] only take effect on
 * API 31+ with GNSS HAL batching; older devices deliver every fix live, so [flush] then
 * completes immediately.
 *
 * Callers check [hasPrecisePermission]; methods throw [SecurityException] when it is missing.
 */
class GpsLocationClient(context: Context) {

    private val locationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val hasGpsProvider: Boolean
        get() = locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)

    /** Registers or replaces (same [listener]) the GPS request. */
    @SuppressLint("MissingPermission")
    fun requestUpdates(request: LocationRequestCompat, listener: LocationListenerCompat, looper: Looper) {
        LocationManagerCompat.requestLocationUpdates(
            locationManager,
            LocationManager.GPS_PROVIDER,
            request,
            listener,
            looper
        )
    }

    /** Removing a listener needs no permission; lint flags the compat wrapper regardless. */
    @SuppressLint("MissingPermission")
    fun removeUpdates(listener: LocationListenerCompat) {
        LocationManagerCompat.removeUpdates(locationManager, listener)
    }

    /**
     * Asks the provider to deliver fixes batched for [listener]; they arrive through the
     * listener before [LocationListenerCompat.onFlushComplete] with [requestCode].
     * Returns false when there is nothing to flush (API < 31): the caller continues at once.
     */
    @SuppressLint("MissingPermission")
    fun flush(listener: LocationListenerCompat, requestCode: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        locationManager.requestFlush(LocationManager.GPS_PROVIDER, listener, requestCode)
        return true
    }

    /** Cached GPS fix (may be stale — callers check its age). */
    @SuppressLint("MissingPermission")
    fun lastKnownLocation(): Location? =
        if (hasGpsProvider) locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null

    companion object {
        /**
         * GPS_PROVIDER delivers fixes only with precise location: approximate alone is
         * not enough to track a run.
         */
        fun hasPrecisePermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }
}
