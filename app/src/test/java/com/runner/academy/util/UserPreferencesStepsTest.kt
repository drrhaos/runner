package com.runner.academy.util

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UserPreferencesStepsTest {

    private lateinit var context: Context
    private lateinit var prefs: UserPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = UserPreferences(context)
        prefs.resetToDefaults()
        prefs.stepPermissionAsked = false
        prefs.saveStrideModel(StrideModel.fromHeight(prefs.userHeight))
    }

    @Test
    fun `step defaults`() {
        assertTrue(prefs.stepsForDistanceEnabled)
        assertFalse(prefs.stepPermissionAsked)
    }

    @Test
    fun `stride model persists between runs`() {
        val model = prefs.loadStrideModel()
        repeat(5) { model.learn(1.3f * 200, 200, 170f) }
        prefs.saveStrideModel(model)
        val again = UserPreferences(context).loadStrideModel()
        assertEquals(model.strideMeters(170f), again.strideMeters(170f), 1e-5f)
        assertEquals(5, again.sampleCount)
    }

    @Test
    fun `untrained model follows height`() {
        prefs.userHeight = 190f
        assertEquals(
            StrideModel.fromHeight(190f).strideMeters(165f),
            prefs.loadStrideModel().strideMeters(165f),
            1e-5f
        )
    }

    @Test
    fun `reset keeps the asked-once flag and the learned stride`() {
        val model = prefs.loadStrideModel()
        repeat(5) { model.learn(1.3f * 200, 200, 170f) }
        prefs.saveStrideModel(model)
        prefs.stepPermissionAsked = true
        prefs.stepsForDistanceEnabled = false

        prefs.resetToDefaults()

        assertTrue(prefs.stepPermissionAsked)
        assertTrue(prefs.stepsForDistanceEnabled)
        assertEquals(5, prefs.loadStrideModel().sampleCount)
    }
}
