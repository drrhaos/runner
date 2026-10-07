package com.runner.academy.util

import com.runner.academy.data.ElevationSource
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ElevationGainTest {

    /** One fix a second from [fromSec] to [toSec], altitude by [altitude] (seconds from the start). */
    private fun points(fromSec: Int = 0, toSec: Int, altitude: (Int) -> Double?): List<TrackPoint> =
        (fromSec..toSec).map { sec ->
            TrackPoint(55.75, 37.60 + sec * 0.00005, START + sec * 1_000L, 5f, 3f, altitude(sec))
        }

    private fun gps(points: List<TrackPoint>) = ElevationGain.compute(points, ElevationSource.GPS)

    /** Three hills of [height] m, each up and down over [hillSec] s, on a 150 m base. */
    private fun hills(height: Double, hillSec: Int = 600): (Int) -> Double = { sec ->
        val phase = (sec % hillSec).toDouble() / hillSec
        150.0 + height * (1 - kotlin.math.cos(2 * PI * phase)) / 2
    }

    @Test
    fun `three clean hills of 40 m gain and lose 120 m`() {
        val result = gps(points(toSec = 1_800, altitude = hills(40.0)))!!

        assertEquals(120.0, result.gainM.toDouble(), 120 * 0.05)
        assertEquals(120.0, result.lossM.toDouble(), 120 * 0.05)
    }

    @Test
    fun `a flat track has no gain`() {
        val result = gps(points(toSec = 1_000) { 150.0 })!!

        assertEquals(0f, result.gainM)
        assertEquals(0f, result.lossM)
    }

    @Test
    fun `wobbling below the threshold adds nothing`() {
        // ±2 m every 30 s: well inside the 5 m hysteresis
        val result = gps(points(toSec = 1_200) { 150.0 + 2 * sin(2 * PI * it / 30.0) })!!

        assertEquals(0f, result.gainM)
        assertEquals(0f, result.lossM)
    }

    @Test
    fun `false fixes of 859 and 987 m mid-track add nothing`() {
        val clean = points(toSec = 1_800, altitude = hills(40.0))
        val withOutliers = clean.mapIndexed { i, p ->
            when (i) {
                700 -> p.copy(altitude = 859.0)
                701 -> p.copy(altitude = 987.0)
                1_200 -> p.copy(altitude = 1_189.0)
                1_201, 1_202 -> p.copy(altitude = 1_314.0)
                else -> p
            }
        }

        val expected = gps(clean)!!
        val result = gps(withOutliers)!!

        assertEquals(expected.gainM, result.gainM, 1f)
        assertEquals(expected.lossM, result.lossM, 1f)
    }

    @Test
    fun `a step across a gap counts nothing`() {
        val before = points(toSec = 300) { 150.0 }
        val after = points(fromSec = 400, toSec = 700) { 200.0 }
        val track = before + after.mapIndexed { i, p -> if (i == 0) p.copy(afterGap = true) else p }

        val result = gps(track)!!

        assertEquals(0f, result.gainM)
        assertEquals(0f, result.lossM)
    }

    @Test
    fun `a step across a bridge counts nothing`() {
        val before = points(toSec = 300) { 150.0 }
        val after = points(fromSec = 301, toSec = 600) { 120.0 }
        val track = before + after.mapIndexed { i, p -> if (i == 0) p.copy(afterGap = true, bridgeMeters = 400f) else p }

        val result = gps(track)!!

        assertEquals(0f, result.gainM)
        assertEquals(0f, result.lossM)
    }

    @Test
    fun `each piece keeps its own climb`() {
        val up = points(toSec = 300) { 150.0 + it / 10.0 } // +30 m
        val down = points(fromSec = 400, toSec = 700) { 300.0 - (it - 400) / 10.0 } // -30 m, 120 m higher
        val track = up + down.mapIndexed { i, p -> if (i == 0) p.copy(afterGap = true) else p }

        val result = gps(track)!!

        assertEquals(30.0, result.gainM.toDouble(), 3.0)
        assertEquals(30.0, result.lossM.toDouble(), 3.0)
    }

    @Test
    fun `a point without altitude breaks the piece, the climbs on both sides still count`() {
        // +20 m over 600 s, +20 m again after one fix without altitude
        val result = gps(points(toSec = 1_201) { sec ->
            when {
                sec < 600 -> 150.0 + sec / 30.0
                sec == 600 -> null
                else -> 170.0 + (sec - 601) / 30.0
            }
        })!!

        assertEquals(40.0, result.gainM.toDouble(), 4.0)
        assertEquals(0f, result.lossM)
    }

    @Test
    fun `a step across fixes without altitude counts nothing`() {
        val result = gps(points(toSec = 700) { sec ->
            when {
                sec < 300 -> 150.0
                sec < 400 -> null
                else -> 200.0
            }
        })!!

        assertEquals(0f, result.gainM)
        assertEquals(0f, result.lossM)
    }

    /** No smoothing and no outlier window: one fix every 5 min, so each value is taken as it is. */
    private val raw = ElevationConfig(smoothingWindowMs = 0L, hysteresisM = 10.0)

    private fun sparse(vararg altitudes: Double) = altitudes.mapIndexed { i, alt ->
        TrackPoint(55.75, 37.60 + i * 0.001, START + i * 300_000L, 5f, 3f, alt)
    }

    @Test
    fun `before the first turn the anchor follows the lowest and the highest value`() {
        // Down 5 m (inside the band), then up to 112: the climb is from 95, not from 100
        val up = ElevationGain.compute(sparse(100.0, 95.0, 112.0), ElevationSource.GPS, raw)!!
        assertEquals(17f, up.gainM)
        assertEquals(0f, up.lossM)

        val down = ElevationGain.compute(sparse(100.0, 105.0, 88.0), ElevationSource.GPS, raw)!!
        assertEquals(0f, down.gainM)
        assertEquals(17f, down.lossM)
    }

    @Test
    fun `many pieces each keep their own anchor`() {
        // 20 pieces of +12 m, each starting 50 m above the end of the one before
        val track = (0 until 20).flatMap { piece ->
            val base = 150.0 + piece * 62.0
            sparse(base, base - 3.0, base + 9.0).mapIndexed { i, p ->
                p.copy(timestamp = START + piece * 1_000_000L + i * 300_000L, afterGap = i == 0 && piece > 0)
            }
        }

        val result = ElevationGain.compute(track, ElevationSource.GPS, raw)!!

        assertEquals(20 * 12f, result.gainM)
        assertEquals(0f, result.lossM)
    }

    @Test
    fun `old zeros mean no altitude`() {
        val result = gps(points(toSec = 600) { sec -> if (sec in 200..260) 0.0 else 150.0 })!!

        assertEquals(0f, result.gainM)
        assertEquals(0f, result.lossM)
    }

    @Test
    fun `no altitudes at all is no result`() {
        assertNull(gps(points(toSec = 100) { null }))
        assertNull(gps(points(toSec = 100) { 0.0 }))
        assertNull(gps(points(toSec = 100) { Double.NaN }))
        assertNull(gps(emptyList()))
    }

    @Test
    fun `a single point with altitude is a zero result`() {
        val result = gps(points(toSec = 0) { 150.0 })
        assertNotNull(result)
        assertEquals(0f, result!!.gainM)
    }

    @Test
    fun `a file is smoothed like GPS, with the tuned GPS parameters`() {
        assertEquals(ElevationConfig.GPS, ElevationConfig.forSource(ElevationSource.GPS))
        assertEquals(ElevationConfig.GPS, ElevationConfig.forSource(ElevationSource.FILE))
        assertEquals(ElevationConfig.GPS, ElevationConfig.forSource(ElevationSource.NONE))
        assertEquals(60_000L, ElevationConfig.GPS.smoothingWindowMs)
        assertEquals(10.0, ElevationConfig.GPS.hysteresisM, 0.0)
    }

    // --- ElevationSource.of ---

    private fun track(points: List<TrackPoint>, declared: ElevationSource? = null) =
        TrackData(points, 0f, 0L, 0f, 0f, START, null, elevationSource = declared)

    @Test
    fun `the source the track declares wins`() {
        assertEquals(ElevationSource.FILE, ElevationSource.of(track(points(toSec = 10) { 150.0 }, ElevationSource.FILE)))
    }

    @Test
    fun `an older track with altitudes is GPS`() {
        assertEquals(ElevationSource.GPS, ElevationSource.of(track(points(toSec = 10) { 150.0 })))
    }

    @Test
    fun `a track with only zeros or nulls has no source`() {
        assertEquals(ElevationSource.NONE, ElevationSource.of(track(points(toSec = 10) { 0.0 })))
        assertEquals(ElevationSource.NONE, ElevationSource.of(track(points(toSec = 10) { null })))
        assertEquals(ElevationSource.NONE, ElevationSource.of(track(points(toSec = 10) { null }, ElevationSource.FILE)))
        assertEquals(ElevationSource.NONE, ElevationSource.of(track(emptyList())))
    }

    private companion object {
        const val START = 1_700_000_000_000L
    }
}
