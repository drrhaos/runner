package com.runner.academy.util

import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import com.runner.academy.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackHeartRateTest {

    private val start = 1_000_000L
    private fun at(sec: Int) = start + sec * 1_000L

    private fun point(sec: Int, hr: Int?) = TrackPoint(55.75, 37.60, at(sec), 5f, 3f, null, heartRate = hr)

    /** Fixes every [everySec] s over [fromSec]..[toSec], the heart rate given by [hrAt]. */
    private fun fixes(fromSec: Int, toSec: Int, everySec: Int = 2, hrAt: (Int) -> Int?) =
        (fromSec..toSec step everySec).map { point(it, hrAt(it)) }

    private fun assertBpm(expected: Int, actual: Int?, tolerance: Int = 1) {
        assertNotNull(actual)
        assertEquals(expected.toFloat(), actual!!.toFloat(), tolerance.toFloat())
    }

    // --- Valid values ---

    @Test
    fun `zero and values out of 25 to 250 are no data`() {
        assertNull(HeartRates.valid(null))
        assertNull(HeartRates.valid(0))
        assertNull(HeartRates.valid(24))
        assertNull(HeartRates.valid(251))
        assertEquals(25, HeartRates.valid(25))
        assertEquals(250, HeartRates.valid(250))
        assertEquals(152, HeartRates.valid(152))
    }

    // --- Average ---

    @Test
    fun `an auto-pause in the middle is left out of the average`() {
        // 150 for 5 min, 2 min standing on an auto-pause at 100, 150 again for 5 min
        val points = fixes(0, 720) { sec -> if (sec in 301..419) 100 else 150 }
        val pauses = listOf(PauseInterval(at(300), at(420), PauseKind.AUTO))

        assertBpm(150, TrackHeartRate.average(points, pauses))
    }

    @Test
    fun `without the pause the stand lowers the average`() {
        val points = fixes(0, 720) { sec -> if (sec in 301..419) 100 else 150 }

        // 10 min at 150, 2 min at 100
        assertBpm((150 * 10 + 100 * 2) / 12, TrackHeartRate.average(points, null))
    }

    @Test
    fun `the average is weighted by time, not by the number of points`() {
        // 2 min of fixes every second at 160, then 2 min of fixes every 10 s at 140
        val points = fixes(0, 120, everySec = 1) { 160 } + fixes(130, 250, everySec = 10) { 140 }

        // The pair 120..130 is half and half: the halves are 160 and 140 for 2 min each
        assertBpm(150, TrackHeartRate.average(points, null))
    }

    @Test
    fun `under a minute of covered time gives no average`() {
        assertNull(TrackHeartRate.average(fixes(0, 58) { 150 }, null))
        assertBpm(150, TrackHeartRate.average(fixes(0, 60) { 150 }, null))
        // A minute of track, but most of it paused
        val pauses = listOf(PauseInterval(at(10), at(40), PauseKind.AUTO))
        assertNull(TrackHeartRate.average(fixes(0, 80) { 150 }, pauses))
    }

    @Test
    fun `a pair longer than 10 s or with a missing value is not counted`() {
        // Fixes every 12 s: no pair is short enough
        assertNull(TrackHeartRate.average(fixes(0, 600, everySec = 12) { 150 }, null))
        // The strap lost contact for a minute in the middle: those points have no value
        val points = fixes(0, 300) { sec -> if (sec in 120..180) null else 150 }
        assertBpm(150, TrackHeartRate.average(points, null))
    }

    @Test
    fun `no heart rate gives no average and no maximum`() {
        assertNull(TrackHeartRate.average(fixes(0, 600) { null }, null))
        assertNull(TrackHeartRate.average(emptyList(), null))
        assertNull(TrackHeartRate.max(fixes(0, 600) { null }, null))
        assertNull(TrackHeartRate.max(emptyList(), null))
    }

    // --- Maximum ---

    @Test
    fun `a single spike does not reach the maximum`() {
        // 140 up to 160, a dry strap gives 220 once
        val points = fixes(0, 80) { sec -> if (sec == 60) 220 else 140 + minOf(sec, 40) / 2 }

        assertEquals(160, TrackHeartRate.max(points, null))
    }

    @Test
    fun `two values in a row are a real maximum`() {
        val points = fixes(0, 80) { sec -> if (sec == 40 || sec == 42) 190 else 150 }

        assertEquals(190, TrackHeartRate.max(points, null))
    }

    @Test
    fun `a point without a value breaks the stretch, like the line`() {
        // As one sequence 150, 190, 190 would make a window of 190
        val points = fixes(0, 60) { 150 } +
            listOf(point(62, 190), point(64, null), point(66, 190), point(68, 150))

        assertEquals(150, TrackHeartRate.max(points, null))
    }

    @Test
    fun `fewer than three known values in a stretch give no maximum`() {
        // A minute of 150 so the average exists; the windows are only inside it
        val minute = fixes(0, 60) { 150 }
        assertEquals(150, TrackHeartRate.max(minute, null))
        assertEquals(150, TrackHeartRate.max(minute + listOf(point(62, 220), point(64, 150)), null))
    }

    @Test
    fun `the window never joins values across a break`() {
        // 150 for a minute, then a lone 200 at the end of one stretch and another 200 after a
        // 60 s break: as one sequence they would make a window 200, 200, x
        val points = fixes(0, 60) { 150 } + point(62, 200) + point(122, 200) + fixes(124, 200) { 150 }

        assertEquals(150, TrackHeartRate.max(points, null))
    }

    @Test
    fun `the window never joins values across a pause`() {
        // Two spikes 4 s apart, a manual pause between them
        val points = fixes(0, 60) { 150 } + point(62, 200) + point(66, 200) + fixes(68, 140) { 150 }
        val pauses = listOf(PauseInterval(at(63), at(65), PauseKind.MANUAL))

        assertEquals(150, TrackHeartRate.max(points, pauses))
        assertEquals(200, TrackHeartRate.max(points, null))
    }

    @Test
    fun `no average means no maximum`() {
        // Under a minute covered: a real 190 plateau is still not shown anywhere
        val points = fixes(0, 50) { sec -> if (sec in 20..30) 190 else 150 }

        assertNull(TrackHeartRate.average(points, null))
        assertNull(TrackHeartRate.max(points, null))
    }

    // --- Time in zones (HRmax 190: Z1 95, Z2 114, Z3 133, Z4 152, Z5 171) ---

    private val zones190 = HrZones(190)

    @Test
    fun `an auto-paused stand is left out of the zones`() {
        // 150 (Z3) for 5 min, 2 min standing on an auto-pause at 100 (Z1), 150 again for 5 min
        val points = fixes(0, 720) { sec -> if (sec in 301..419) 100 else 150 }
        val pauses = listOf(PauseInterval(at(300), at(420), PauseKind.AUTO))

        val ms = TrackHeartRate.timeInZones(points, pauses, zones190)!!

        assertEquals(6, ms.size)
        assertEquals(600_000L, ms[3])
        assertEquals(600_000L, ms.sum())
    }

    @Test
    fun `a pair counts in the zone of its mean`() {
        // 151 and 153: the mean 152 is Z4; 150 and 153: 151 (floored) is Z3
        val points = fixes(0, 120) { sec -> if ((sec / 2) % 2 == 0) 151 else 153 }
        val floored = fixes(0, 120) { sec -> if ((sec / 2) % 2 == 0) 150 else 153 }

        assertEquals(120_000L, TrackHeartRate.timeInZones(points, null, zones190)!![4])
        assertEquals(120_000L, TrackHeartRate.timeInZones(floored, null, zones190)!![3])
    }

    @Test
    fun `below Z1 has its own bucket`() {
        val points = fixes(0, 120) { 80 }

        val ms = TrackHeartRate.timeInZones(points, null, zones190)!!

        assertEquals(120_000L, ms[0])
        assertEquals(120_000L, ms.sum())
    }

    @Test
    fun `a gap over 10 s and points without a value are not counted`() {
        // 0..60 s at 150, a 30 s gap, 90..150 s at 175 with a no-contact point at 120 s
        val points = fixes(0, 60) { 150 } + fixes(90, 150) { sec -> if (sec == 120) null else 175 }

        val ms = TrackHeartRate.timeInZones(points, null, zones190)!!

        assertEquals(60_000L, ms[3])
        assertEquals(56_000L, ms[5])
        assertEquals(116_000L, ms.sum())
    }

    @Test
    fun `the zones sum up to the time the average covers`() {
        val points = fixes(0, 600) { sec -> 90 + sec / 6 }

        val ms = TrackHeartRate.timeInZones(points, null, zones190)!!

        assertEquals(600_000L, ms.sum())
        assertNotNull(TrackHeartRate.average(points, null))
    }

    @Test
    fun `under a minute of heart rate gives no zones`() {
        assertNull(TrackHeartRate.timeInZones(fixes(0, 58) { 150 }, null, zones190))
        assertNull(TrackHeartRate.timeInZones(fixes(0, 600) { null }, null, zones190))
        assertNull(TrackHeartRate.timeInZones(emptyList(), null, zones190))
    }
}
