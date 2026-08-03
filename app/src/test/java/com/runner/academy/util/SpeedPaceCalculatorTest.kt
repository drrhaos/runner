package com.runner.academy.util

import com.runner.academy.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpeedPaceCalculatorTest {

    private fun point(
        lat: Double,
        lon: Double,
        timeOffsetMs: Long,
        speedMs: Float? = null,
        altitude: Double? = null,
        baseTime: Long = 1_000_000L
    ) = TrackPoint(
        latitude = lat,
        longitude = lon,
        timestamp = baseTime + timeOffsetMs,
        accuracy = 5f,
        speed = speedMs,
        altitude = altitude
    )

    @Test
    fun `paceMinPerKmFromSpeedMs converts 10 kmh to 6 min per km`() {
        // 10 km/h = 10/3.6 m/s ≈ 2.777...
        val speedMs = 10f / 3.6f
        assertEquals(6f, SpeedPaceCalculator.paceMinPerKmFromSpeedMs(speedMs), 0.01f)
    }

    @Test
    fun `paceMinPerKmFromSpeedMs returns zero for invalid speed`() {
        assertEquals(0f, SpeedPaceCalculator.paceMinPerKmFromSpeedMs(0f), 0.001f)
        assertEquals(0f, SpeedPaceCalculator.paceMinPerKmFromSpeedMs(-1f), 0.001f)
    }

    @Test
    fun `paceMinPerKmToMinPerMile multiplies by km per mile`() {
        assertEquals(8.0467f, SpeedPaceCalculator.paceMinPerKmToMinPerMile(5f), 0.01f)
        assertEquals(0f, SpeedPaceCalculator.paceMinPerKmToMinPerMile(0f), 0.001f)
    }

    @Test
    fun `segmentPace metric is duration minutes over km`() {
        // 5 minutes for 1 km → 5:00 /km
        val pace = SpeedPaceCalculator.segmentPaceMetric(durationMs = 5 * 60_000L, distanceKm = 1f, metric = true)
        assertEquals(5f, pace, 0.01f)
    }

    @Test
    fun `segmentPace imperial converts to min per mile`() {
        // 5 minutes for 1.60934 km (1 mile) → 5:00 /mi
        val pace = SpeedPaceCalculator.segmentPaceMetric(
            durationMs = 5 * 60_000L,
            distanceKm = SpeedPaceCalculator.KM_PER_MILE,
            metric = false
        )
        assertEquals(5f, pace, 0.01f)
    }

    @Test
    fun `segmentPace imperial for 1 km is slower than metric`() {
        val metric = SpeedPaceCalculator.segmentPaceMetric(5 * 60_000L, 1f, metric = true)
        val imperial = SpeedPaceCalculator.segmentPaceMetric(5 * 60_000L, 1f, metric = false)
        // Same time over shorter mile-equivalent distance → higher (slower) pace number
        assertTrue(imperial > metric)
        assertEquals(metric * SpeedPaceCalculator.KM_PER_MILE, imperial, 0.01f)
    }

    @Test
    fun `segmentSpeed metric is kmh from distance and time`() {
        // 10 km in 1 hour → 10 km/h
        val speed = SpeedPaceCalculator.segmentSpeedMetric(3_600_000L, 10f, metric = true)
        assertEquals(10f, speed, 0.01f)
    }

    @Test
    fun `segmentSpeed imperial converts to mph`() {
        val speed = SpeedPaceCalculator.segmentSpeedMetric(3_600_000L, 10f, metric = false)
        assertEquals(6.21371f, speed, 0.01f)
    }

    @Test
    fun `averageSpeedMs uses distance over time not arithmetic mean`() {
        // 5000 m in 1000 s → 5 m/s
        assertEquals(5f, SpeedPaceCalculator.averageSpeedMs(5000f, 1_000_000L), 0.01f)
        assertEquals(0f, SpeedPaceCalculator.averageSpeedMs(0f, 1000L), 0.001f)
        assertEquals(0f, SpeedPaceCalculator.averageSpeedMs(1000f, 0L), 0.001f)
    }

    @Test
    fun `overallAveragePace uses total time over total distance`() {
        // 10 km in 50 minutes → 5:00 /km
        val pace = SpeedPaceCalculator.overallAveragePace(10_000.0, 50 * 60.0)
        assertEquals(5f, pace, 0.01f)
    }

    @Test
    fun `overallAveragePace is not average of average durations`() {
        // Bug regression: avgDuration/totalDistance would give wrong result
        // Two workouts: 5 km in 25 min and 5 km in 35 min → overall 6:00 /km
        val totalDistance = 10f
        val totalDuration = (25 + 35) * 60_000L
        assertEquals(6f, SpeedPaceCalculator.overallAveragePace(totalDistance * 1000.0, totalDuration / 1000.0), 0.01f)

        val wrongAvgDuration = ((25 + 35) / 2f) * 60_000f
        val wrongPace = (wrongAvgDuration / 60_000f) / totalDistance
        assertTrue(wrongPace < 6f) // would incorrectly show ~3:00 /km
    }

    @Test
    fun `buildSegments metric creates one km segments with correct pace`() {
        // ~1 km east at ~55.75 lat: 1 degree lon ≈ 62.6 km, so 0.016 deg lon ≈ 1 km
        val start = point(55.75, 37.60, 0L)
        val mid = point(55.75, 37.608, 5 * 60_000L) // ~0.5 km in 5 min
        val end = point(55.75, 37.616, 10 * 60_000L) // ~1 km total in 10 min

        val segments = SpeedPaceCalculator.buildSegments(listOf(start, mid, end), metric = true)
        assertTrue(segments.isNotEmpty())
        val first = segments.first()
        assertTrue("pace should be positive", first.paceMinPerUnit > 0f)
        assertEquals("full km segment distance", 1.0f, first.distanceKm, 0.001f)
    }

    @Test
    fun `buildSegments closes full km at exactly 1km with consistent pace formula`() {
        val points = mutableListOf<TrackPoint>()
        val baseLat = 55.75
        val baseLon = 37.60
        val totalMs = (6.07f * 60_000f).toLong()
        for (i in 0..11) {
            points.add(point(baseLat, baseLon + i * 0.0016, i * totalMs / 11))
        }

        val segments = SpeedPaceCalculator.buildSegments(points, metric = true)
        assertTrue(segments.isNotEmpty())

        val first = segments.first()
        assertEquals(1.0f, first.distanceKm, 0.001f)

        val expectedPace = (first.durationMs / SpeedPaceCalculator.MS_PER_MINUTE) / first.distanceKm
        assertEquals(expectedPace, first.paceMinPerUnit, 0.01f)
    }

    @Test
    fun `paceToMinutesSeconds avoids invalid seconds`() {
        val (minutes, seconds) = SpeedPaceCalculator.paceToMinutesSeconds(5.999f)
        assertEquals(5, minutes)
        assertTrue(seconds in 0..59)
    }

    @Test
    fun `maxDerivedSpeedMs uses distance over time not gps speed field`() {
        val p1 = point(55.75, 37.60, 0L, speedMs = 0f)
        val p2 = point(55.75, 37.6016, 10_000L, speedMs = 99f) // bogus GPS speed

        val max = SpeedPaceCalculator.maxDerivedSpeedMs(listOf(p1, p2))
        assertTrue(max > 0f)
        assertTrue(max < 20f) // ~10 m/s derived, not 99 m/s GPS
    }

    @Test
    fun `paceFromSpeedKmh and overallAveragePace are consistent`() {
        val pace = SpeedPaceCalculator.overallAveragePace(10_000.0, 60 * 60.0)
        val speed = SpeedPaceCalculator.averageSpeedKmh(10f, 60 * 60_000L)
        assertEquals(pace, SpeedPaceCalculator.paceFromSpeedKmh(speed), 0.01f)
    }

    @Test
    fun `formatPaceMmSs formats decimal minutes as mm colon ss`() {
        assertEquals("6:07", SpeedPaceCalculator.formatPaceMmSs(6.1167f))
        assertEquals("5:40", SpeedPaceCalculator.formatPaceMmSs(5.67f))
        assertEquals("--:--", SpeedPaceCalculator.formatPaceMmSs(0f))
    }

    @Test
    fun `buildSegments imperial pace is min per mile not min per km`() {
        val points = mutableListOf<TrackPoint>()
        val baseLat = 55.75
        val baseLon = 37.60
        // ~100 m per step; 20 points ≈ 1.9 km — enough for one full mile segment
        for (i in 0..20) {
            points.add(point(baseLat, baseLon + i * 0.0016, i * 30_000L))
        }

        val metricSegments = SpeedPaceCalculator.buildSegments(points, metric = true)
        val imperialSegments = SpeedPaceCalculator.buildSegments(points, metric = false)

        assertTrue(metricSegments.isNotEmpty())
        assertTrue(imperialSegments.isNotEmpty())

        assertEquals(1.0f, metricSegments.first().distanceKm, 0.001f)
        assertEquals(SpeedPaceCalculator.KM_PER_MILE, imperialSegments.first().distanceKm, 0.01f)

        val metricPace = metricSegments.first().paceMinPerUnit
        val imperialPace = imperialSegments.first().paceMinPerUnit
        assertTrue(
            "imperial pace ($imperialPace) should be ~1.6x metric ($metricPace)",
            imperialPace > metricPace * 1.4f
        )
    }

    @Test
    fun `buildPaceSpeedSeries uses derived speed not gps speed of zero`() {
        // GPS speed is 0, but points move ~100m in 10s → ~10 m/s
        val p1 = point(55.75, 37.60, 0L, speedMs = 0f)
        val p2 = point(55.75, 37.6016, 10_000L, speedMs = 0f)

        val series = SpeedPaceCalculator.buildPaceSpeedSeries(listOf(p1, p2), metric = true)
        assertEquals(1, series.size)
        assertTrue("derived speed should be > 0", series[0].speedDisplay > 0f)
        assertTrue("derived pace should be > 0", series[0].paceMinPerUnit > 0f)
    }

    @Test
    fun `buildPaceSpeedSeries imperial converts units`() {
        val p1 = point(55.75, 37.60, 0L, speedMs = 0f)
        val p2 = point(55.75, 37.6016, 10_000L, speedMs = 0f)

        val metric = SpeedPaceCalculator.buildPaceSpeedSeries(listOf(p1, p2), metric = true)
        val imperial = SpeedPaceCalculator.buildPaceSpeedSeries(listOf(p1, p2), metric = false)

        assertEquals(1, metric.size)
        assertEquals(1, imperial.size)
        assertTrue(imperial[0].paceMinPerUnit > metric[0].paceMinPerUnit)
        assertTrue(imperial[0].speedDisplay < metric[0].speedDisplay)
    }

    @Test
    fun `buildElevationSeries accumulates distance`() {
        val p1 = point(55.75, 37.60, 0L, altitude = 100.0)
        val p2 = point(55.75, 37.6016, 10_000L, altitude = 110.0)
        val p3 = point(55.75, 37.6032, 20_000L, altitude = 105.0)

        val series = SpeedPaceCalculator.buildElevationSeries(listOf(p1, p2, p3))
        assertEquals(3, series.size)
        assertEquals(0f, series[0].distanceKm, 0.001f)
        assertTrue(series[1].distanceKm > 0f)
        assertTrue(series[2].distanceKm > series[1].distanceKm)
        assertEquals(110f, series[1].altitudeMeters, 0.1f)
    }

    @Test
    fun `buildElevationSeries empty when no altitudes`() {
        val p1 = point(55.75, 37.60, 0L, altitude = null)
        val p2 = point(55.75, 37.6016, 10_000L, altitude = null)
        assertTrue(SpeedPaceCalculator.buildElevationSeries(listOf(p1, p2)).isEmpty())
    }
}
