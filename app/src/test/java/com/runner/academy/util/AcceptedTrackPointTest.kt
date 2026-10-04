package com.runner.academy.util

import android.location.Location
import com.runner.academy.data.LocationSource
import com.runner.academy.data.TrackPoint
import com.runner.academy.util.TrackFilter.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [Verdict.Accepted.toTrackPoint]: the one mapping the live and the save path share. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AcceptedTrackPointTest {

    private val location = Location("test").apply {
        latitude = 55.1
        longitude = 37.2
        time = 5_000L
        accuracy = 4f
        speed = 3f
        altitude = 140.0
    }
    private val raw = TrackPoint(55.0, 37.0, 5_000L, 9f, 1f, 100.0, steps = 300, cadence = 170f)

    @Test
    fun `position comes from the filter, steps from the raw point`() {
        val point = Verdict.Accepted(location, 3f, afterGap = false).toTrackPoint(raw, firstPoint = false)

        assertEquals(55.1, point.latitude, 0.0)
        assertEquals(4f, point.accuracy!!, 0f)
        assertEquals(300, point.steps)
        assertEquals(170f, point.cadence!!, 0f)
        assertNull(point.bridgeMeters)
        assertEquals(LocationSource.GPS.name, point.source)
    }

    @Test
    fun `a bridge from steps is a pedometer point`() {
        val point = Verdict.Accepted(location, 400f, afterGap = true, bridgeMeters = 400f, bridgeFromSteps = true)
            .toTrackPoint(raw, firstPoint = false)

        assertEquals(400f, point.bridgeMeters!!, 0f)
        assertEquals(LocationSource.PEDOMETER.name, point.source)
    }

    @Test
    fun `a lead-in rides on the first point only`() {
        val accepted = Verdict.Accepted(location, 0f, afterGap = false, leadInMeters = 120f)
        assertEquals(120f, accepted.toTrackPoint(raw, firstPoint = true).bridgeMeters!!, 0f)
        assertEquals(LocationSource.GPS.name, accepted.toTrackPoint(raw, firstPoint = true).source)
        assertNull(accepted.toTrackPoint(raw, firstPoint = false).bridgeMeters)
    }

    @Test
    fun `a stored bridge or lead-in survives a re-sanitize`() {
        val storedBridge = raw.copy(afterGap = true, bridgeMeters = 500f, source = LocationSource.PEDOMETER.name)
        val gap = Verdict.Accepted(location, 0f, afterGap = true).toTrackPoint(storedBridge, firstPoint = false)
        assertEquals(500f, gap.bridgeMeters!!, 0f)
        assertEquals(LocationSource.PEDOMETER.name, gap.source)

        val storedLeadIn = raw.copy(bridgeMeters = 80f)
        val first = Verdict.Accepted(location, 0f, afterGap = false).toTrackPoint(storedLeadIn, firstPoint = true)
        assertEquals(80f, first.bridgeMeters!!, 0f)
    }
}
