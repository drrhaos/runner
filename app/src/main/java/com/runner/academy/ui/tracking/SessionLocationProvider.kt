package com.runner.academy.ui.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.runner.academy.util.GpsConfig
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider

/**
 * Feeds [org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay] from the workout
 * session location stream so the person icon and track tip stay aligned.
 *
 * Before the session publishes fixes, uses Fused [GpsConfig.createStatusLocationRequest]
 * (balanced power) instead of a raw continuous GPS provider.
 */
class SessionLocationProvider(
    context: Context
) : IMyLocationProvider {

    private val appContext = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
    private var consumer: IMyLocationConsumer? = null
    private var lastLocation: Location? = null
    private var sessionDriven = false
    private var fallbackActive = false

    /** Invoked for every fix (fallback or session) so the map can center on first signal. */
    var onLocationUpdated: ((Location) -> Unit)? = null

    private val fallbackCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (sessionDriven) return
            result.lastLocation?.let { dispatch(it) }
        }
    }

    override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer?): Boolean {
        consumer = myLocationConsumer
        lastLocation?.let { location ->
            myLocationConsumer?.onLocationChanged(location, this)
        }
        startFallbackUpdates()
        return true
    }

    override fun stopLocationProvider() {
        stopFallbackUpdates()
        consumer = null
    }

    override fun getLastKnownLocation(): Location? = lastLocation

    override fun destroy() {
        stopLocationProvider()
        lastLocation = null
        sessionDriven = false
        onLocationUpdated = null
    }

    /** Session / service fix — preferred source while tracking. */
    fun publish(location: Location) {
        sessionDriven = true
        stopFallbackUpdates()
        dispatch(location)
    }

    @SuppressLint("MissingPermission")
    private fun startFallbackUpdates() {
        if (fallbackActive || sessionDriven) return
        if (!hasLocationPermission()) return
        try {
            fusedClient.requestLocationUpdates(
                GpsConfig.createStatusLocationRequest(),
                fallbackCallback,
                Looper.getMainLooper()
            )
            fallbackActive = true
            fusedClient.lastLocation.addOnSuccessListener { location ->
                if (!sessionDriven && location != null) {
                    dispatch(location)
                }
            }
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "Fallback location denied", e)
        }
    }

    private fun stopFallbackUpdates() {
        if (!fallbackActive) return
        try {
            fusedClient.removeLocationUpdates(fallbackCallback)
        } catch (_: Exception) {
            // ignore
        }
        fallbackActive = false
    }

    private fun dispatch(location: Location) {
        lastLocation = location
        consumer?.onLocationChanged(location, this)
        onLocationUpdated?.invoke(location)
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    companion object {
        private const val TAG = "SessionLocationProvider"
    }
}
