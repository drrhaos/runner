package com.runner.academy.util

import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import com.runner.academy.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackCadenceTest {

    private val start = 1_000_000L
    private fun at(sec: Int) = start + sec * 1_000L

    private fun point(sec: Int, steps: Int?) =
        TrackPoint(55.75, 37.60, at(sec), 5f, 3f, null, steps = steps, cadence = steps?.let { 170f })

    /** Fixes every [everySec] s over [fromSec]..[toSec], the steps given by [stepsAt]. */
    private fun fixes(fromSec: Int, toSec: Int, everySec: Int = 2, stepsAt: (Int) -> Int?) =
        (fromSec..toSec step everySec).map { point(it, stepsAt(it)) }

    /** 170 steps/min from [fromSec] (with [base] steps there). */
    private fun at170(fromSec: Int, base: Int = 0): (Int) -> Int = { sec -> base + (sec - fromSec) * 170 / 60 }

    /** 300 s at 170, 60 s standing (steps stay), 300 s at 170. */
    private val standInTheMiddle: List<TrackPoint> = run {
        val atStand = 300 * 170 / 60
        fixes(0, 298, stepsAt = at170(0)) +
            fixes(300, 358) { atStand } +
            fixes(360, 660, stepsAt = at170(360, atStand))
    }

    private fun assertCadence(expected: Float, actual: Float?, tolerance: Float = 1f) {
        assertNotNull(actual)
        assertEquals(expected, actual!!, tolerance)
    }

    @Test
    fun `an auto-pause in the middle is left out of the average`() {
        val pauses = listOf(PauseInterval(at(300), at(360), PauseKind.AUTO))

        assertCadence(170f, TrackCadence.average(standInTheMiddle, pauses))
    }

    @Test
    fun `standing without an auto-pause lowers the average`() {
        val withStand = TrackCadence.average(standInTheMiddle, pauses = null)

        // 1 700 steps over 11 minutes
        assertCadence(1_700f / 11f, withStand)
        assertTrue(withStand!! < 165f)
    }

    @Test
    fun `a manual pause is left out like an auto one`() {
        // No fixes and no steps on a manual pause: one pair spans it
        val atPause = 300 * 170 / 60
        val points = fixes(0, 300, stepsAt = at170(0)) + fixes(360, 660, stepsAt = at170(360, atPause))
        val pauses = listOf(PauseInterval(at(300), at(360), PauseKind.MANUAL))

        assertCadence(170f, TrackCadence.average(points, pauses))
    }

    @Test
    fun `no steps give no cadence`() {
        assertNull(TrackCadence.average(fixes(0, 600) { null }, null))
        assertNull(TrackCadence.average(emptyList(), null))
        assertNull(TrackCadence.average(listOf(point(0, 10)), null))
    }

    @Test
    fun `under a minute of moving time gives no cadence`() {
        assertNull(TrackCadence.average(fixes(0, 58, stepsAt = at170(0)), null))
        assertCadence(170f, TrackCadence.average(fixes(0, 60, stepsAt = at170(0)), null), tolerance = 3f)
        // A minute of track, but most of it paused
        val pauses = listOf(PauseInterval(at(10), at(40), PauseKind.AUTO))
        assertNull(TrackCadence.average(fixes(0, 80, stepsAt = at170(0)), pauses))
    }

    @Test
    fun `steps lost mid-run drop out of both sums`() {
        // The permission is revoked at 300 s and granted again at 400 s (steps go on from there)
        val points = fixes(0, 298, stepsAt = at170(0)) +
            fixes(300, 398) { null } +
            fixes(400, 700, stepsAt = at170(400, base = 5_000))

        assertCadence(170f, TrackCadence.average(points, null))
    }

    @Test
    fun `a counter reset is skipped, not counted backwards`() {
        val points = fixes(0, 298, stepsAt = at170(0)) + fixes(300, 600, stepsAt = at170(300, base = 0))

        assertCadence(170f, TrackCadence.average(points, null))
    }

    @Test
    fun `steps before the first fix are not counted`() {
        // The sensor counted 400 steps before the first fix: they have no time
        assertCadence(170f, TrackCadence.average(fixes(0, 300, stepsAt = at170(0, base = 400)), null))
    }
}
