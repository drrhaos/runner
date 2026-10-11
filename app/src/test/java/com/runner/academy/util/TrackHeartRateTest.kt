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
        assertNull(TrackHeartRate.max(fixes(0, 600) { null }))
        assertNull(TrackHeartRate.max(emptyList()))
    }

    // --- Maximum ---

    @Test
    fun `a single spike does not reach the maximum`() {
        // 140 up to 160, a dry strap gives 220 once
        val points = fixes(0, 80) { sec -> if (sec == 60) 220 else 140 + minOf(sec, 40) / 2 }

        assertEquals(160, TrackHeartRate.max(points))
    }

    @Test
    fun `two values in a row are a real maximum`() {
        val points = fixes(0, 80) { sec -> if (sec == 40 || sec == 42) 190 else 150 }

        assertEquals(190, TrackHeartRate.max(points))
    }

    @Test
    fun `points without a value between two known ones are skipped, not a break`() {
        val points = listOf(point(0, 150), point(2, 190), point(4, null), point(6, 190), point(8, 150))

        assertEquals(190, TrackHeartRate.max(points))
    }

    @Test
    fun `fewer than three known values give no maximum`() {
        assertNull(TrackHeartRate.max(listOf(point(0, 150), point(2, 220), point(4, null))))
        assertEquals(150, TrackHeartRate.max(listOf(point(0, 150), point(2, 220), point(4, 150))))
    }
}
