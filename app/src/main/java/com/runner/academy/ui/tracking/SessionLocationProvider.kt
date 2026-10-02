package com.runner.academy.ui.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import androidx.core.location.LocationListenerCompat
import com.runner.academy.util.GpsLocationClient
import com.runner.academy.util.GpsConfig
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import android.os.SystemClock

/**
 * Feeds [org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay] from the workout
 * session location stream so the person icon and track tip stay aligned.
 *
 * Before the session publishes fixes, requests GPS with [GpsConfig.createPreWorkoutLocationRequest]
 * so the map centers and Start readiness is clear. GPS only — no network location, which is
 * backed by Google on stock phones; until the first fix the map shows a fresh cached GPS fix.
 */
class SessionLocationProvider(
    context: Context
) : IMyLocationProvider {

    private val appContext = context.applicationContext
    private val gpsClient = GpsLocationClient(appContext)
    private var consumer: IMyLocationConsumer? = null
    private var lastLocation: Location? = null
    private var sessionDriven = false
    private var fallbackActive = false

    /** Invoked for every fix (fallback or session) so the map can center on first signal. */
    var onLocationUpdated: ((Location) -> Unit)? = null

    /** Last fix from fallback or session (may be null). */
    fun peekLastLocation(): Location? = lastLocation

    private val fallbackListener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            if (!sessionDriven) dispatch(location)
        }

        override fun onLocationChanged(locations: MutableList<Location>) {
            if (sessionDriven) return
            // Prefer the most accurate fix in a batch for accuracy convergence
            locations.maxByOrNull { loc -> if (loc.hasAccuracy()) 1000f - loc.accuracy else 0f }
                ?.let { dispatch(it) }
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

    /** Call when returning to idle after stop so pre-start warm-up resumes. */
    fun resumePreWorkoutUpdates() {
        sessionDriven = false
        startFallbackUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startFallbackUpdates() {
        if (fallbackActive || sessionDriven) return
        if (!hasLocationPermission()) return
        try {
            gpsClient.requestUpdates(
                GpsConfig.createPreWorkoutLocationRequest(),
                fallbackListener,
                Looper.getMainLooper()
            )
            fallbackActive = true
            gpsClient.lastKnownLocation()?.let { location ->
                if (isFreshEnough(location)) dispatch(location)
            }
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "Fallback location denied", e)
        } catch (e: IllegalArgumentException) {
            // Device without a GPS provider
            android.util.Log.w(TAG, "GPS provider unavailable", e)
        }
    }

    private fun isFreshEnough(location: Location, maxAgeMs: Long = 30_000L): Boolean {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) /
            1_000_000L
        return ageMs in 0..maxAgeMs
    }

    private fun stopFallbackUpdates() {
        if (!fallbackActive) return
        try {
            gpsClient.removeUpdates(fallbackListener)
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

    private fun hasLocationPermission(): Boolean = GpsLocationClient.hasPrecisePermission(appContext)

    companion object {
        private const val TAG = "SessionLocationProvider"
    }
}
