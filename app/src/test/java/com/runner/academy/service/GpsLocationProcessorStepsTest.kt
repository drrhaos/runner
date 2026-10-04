package com.runner.academy.service

import android.location.Location
import com.runner.academy.data.LocationSource
import com.runner.academy.service.GpsLocationProcessor.ProcessResult
import com.runner.academy.util.TrackGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos

/** Steps through the live pipeline: points carry steps, distance arrives as counted deltas. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GpsLocationProcessorStepsTest {

    private val processor = GpsLocationProcessor().apply {
        reset(stepDistance = { steps, _ -> steps * 1f })
    }
    private var last: ProcessResult? = null
    private var shown = 0f

    private fun fix(eastM: Double, sec: Int, speed: Float = 3f): Location = Location("test").apply {
        latitude = LAT0
        longitude = LON0 + eastM / (M_PER_DEG * cos(Math.toRadians(LAT0)))
        time = T0 + sec * 1_000L
        accuracy = 6f
        this.speed = speed
    }

    private fun feed(location: Location, steps: Int?, cadence: Float? = 180f): ProcessResult {
        val result = processor.processLocation(
            location,
            last?.trackPoints ?: mutableListOf(),
            last?.trackDataPoints ?: mutableListOf(),
            last?.rawTrackDataPoints ?: mutableListOf(),
            steps = steps,
            cadence = cadence
        )
        assertTrue("shown distance never goes down", result.distanceDeltaMeters >= 0f)
        shown += result.distanceDeltaMeters
        last = result
        return result
    }

    @Test
    fun `raw and kept points carry steps and cadence`() {
        val result = feed(fix(0.0, 0), steps = 12, cadence = 170f)

        assertEquals(12, result.rawTrackDataPoints.single().steps)
        assertEquals(170f, result.rawTrackDataPoints.single().cadence!!, 0f)
        assertEquals(12, result.trackDataPoints.single().steps)
    }

    @Test
    fun `an episode is shown live and the bridge is stored once`() {
        for (sec in 0..10) feed(fix(sec * 3.0, sec), steps = sec * 3)
        val frozen = fix(30.0, 0)
        for (sec in 11..60) feed(Location(frozen).apply { time = T0 + sec * 1_000L }, steps = sec * 3)
        assertTrue("counted live from steps", shown > 100f)

        val back = feed(fix(90.0, 61), steps = 183) as ProcessResult.Accepted

        val bridge = back.trackDataPoints.last()
        assertEquals(LocationSource.PEDOMETER.name, bridge.source)
        assertEquals(153f, bridge.bridgeMeters!!, 0.5f)
        assertEquals(TrackGeometry.totalDistanceMeters(back.trackDataPoints), shown, 0.5f)
    }

    @Test
    fun `a false start is stored as a lead-in on the first point`() {
        for (sec in 0..40) feed(fix(15_000.0, sec, speed = 0f), steps = sec * 3)
        assertEquals("steps shown before any position", 120f, shown, 0.5f)
        val first = feed(fix(0.0, 41), steps = 123) as ProcessResult.Accepted
        assertEquals("only the rest is added", 3f, first.distanceDeltaMeters, 0.5f)
        feed(fix(3.0, 42), steps = 126)

        val start = last!!.trackDataPoints.first()
        assertEquals(123f, start.bridgeMeters!!, 0.5f)
        assertEquals(LocationSource.GPS.name, start.source)
        assertEquals(TrackGeometry.totalDistanceMeters(last!!.trackDataPoints), shown, 0.5f)
    }

    @Test
    fun `without steps nothing changes`() {
        val plain = GpsLocationProcessor().apply { reset() }
        val result = plain.processLocation(fix(0.0, 0), mutableListOf(), mutableListOf(), mutableListOf())
        assertNull(result.rawTrackDataPoints.single().steps)
        assertEquals(0f, result.distanceDeltaMeters, 0f)
    }

    private companion object {
        const val LAT0 = 55.75
        const val LON0 = 37.6
        const val M_PER_DEG = 111_320.0
        const val T0 = 1_700_000_000_000L
    }
}
