package com.runner.academy.ui.tracking

import android.content.Context
import android.location.Location
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider

/**
 * Feeds [org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay] from the workout
 * session location stream so the person icon and track tip stay aligned.
 *
 * Before the session publishes fixes, falls back to [GpsMyLocationProvider] so the
 * map can still show the user position on the tracking screen.
 */
class SessionLocationProvider(
    context: Context
) : IMyLocationProvider, IMyLocationConsumer {

    private val fallback = GpsMyLocationProvider(context)
    private var consumer: IMyLocationConsumer? = null
    private var lastLocation: Location? = null
    private var sessionDriven = false

    /** Invoked for every fix (fallback or session) so the map can center on first signal. */
    var onLocationUpdated: ((Location) -> Unit)? = null

    override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer?): Boolean {
        consumer = myLocationConsumer
        lastLocation?.let { location ->
            myLocationConsumer?.onLocationChanged(location, this)
        }
        // Bootstrap marker before workout service publishes session locations
        fallback.startLocationProvider(this)
        return true
    }

    override fun stopLocationProvider() {
        fallback.stopLocationProvider()
        consumer = null
    }

    override fun getLastKnownLocation(): Location? = lastLocation ?: fallback.lastKnownLocation

    override fun destroy() {
        stopLocationProvider()
        fallback.destroy()
        lastLocation = null
        sessionDriven = false
        onLocationUpdated = null
    }

    /** Session / service fix — preferred source while tracking. */
    fun publish(location: Location) {
        sessionDriven = true
        dispatch(location)
    }

    override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
        if (location == null) return
        // Ignore fallback once session stream is active
        if (sessionDriven) return
        dispatch(location)
    }

    private fun dispatch(location: Location) {
        lastLocation = location
        consumer?.onLocationChanged(location, this)
        onLocationUpdated?.invoke(location)
    }
}
