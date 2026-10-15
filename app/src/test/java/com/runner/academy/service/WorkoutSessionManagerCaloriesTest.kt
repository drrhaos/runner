package com.runner.academy.service

import com.runner.academy.data.TrackPoint
import com.runner.academy.util.CalorieProfile
import com.runner.academy.util.Calories
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutSessionManagerCaloriesTest {

    private val start = 1_000_000L
    private val profile = CalorieProfile(weightKg = 70f, sex = CalorieProfile.Sex.MALE, ageYears = 35)
    private val manager = WorkoutSessionManager().apply { startNewSession(now = start) }

    private fun point(sec: Int, hr: Int?) =
        TrackPoint(55.0, 37.0 + sec * 1e-5, start + sec * 1000L, 5f, 3f, null, heartRate = hr)

    @Test
    fun `a distance update no longer sets the calories, the tick does`() {
        manager.updateMetricsFromLocation(1000f, emptyList(), emptyList(), emptyList())
        assertEquals(0, manager.getSession().calories)

        manager.tickElapsedTime(broadcast = false, now = start + 300_000L)
        manager.updateCalories(profile)

        val expected = Calories.estimate(profile, 1000f, 300_000L, climbM = null, avgHeartRate = null)
        assertEquals(expected, manager.getSession().calories)
    }

    @Test
    fun `the heart rate of the points so far switches to Keytel`() {
        val points = (0..120).map { point(it, 150) }
        manager.updateMetricsFromLocation(400f, emptyList(), points, points)
        manager.tickElapsedTime(broadcast = false, now = start + 120_000L)
        manager.updateCalories(profile)

        val expected = Calories.estimate(profile, 400f, 120_000L, climbM = null, avgHeartRate = 150)
        assertEquals(expected, manager.getSession().calories)
    }

    @Test
    fun `a pause stops the calories with the moving time`() {
        manager.updateMetricsFromLocation(500f, emptyList(), emptyList(), emptyList())
        manager.tickElapsedTime(broadcast = false, now = start + 60_000L)
        manager.updateCalories(profile)
        val before = manager.getSession().calories

        manager.pause(now = start + 60_000L)
        manager.tickElapsedTime(broadcast = false, now = start + 600_000L)
        manager.updateCalories(profile)

        assertEquals(before, manager.getSession().calories)
    }
}
