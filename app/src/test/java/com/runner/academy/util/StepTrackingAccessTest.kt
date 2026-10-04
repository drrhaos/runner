package com.runner.academy.util

import android.Manifest
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class StepPermissionPolicyTest {

    @Test
    fun `prompts once on API 29+ when enabled, not asked and not granted`() {
        assertTrue(StepPermissionPolicy.shouldPromptAtWorkoutStart(29, enabled = true, askedBefore = false, granted = false))
        assertTrue(StepPermissionPolicy.shouldPromptAtWorkoutStart(36, enabled = true, askedBefore = false, granted = false))
    }

    @Test
    fun `never prompts again after the first ask`() {
        assertFalse(StepPermissionPolicy.shouldPromptAtWorkoutStart(34, enabled = true, askedBefore = true, granted = false))
    }

    @Test
    fun `no prompt when disabled, already granted or below API 29`() {
        assertFalse(StepPermissionPolicy.shouldPromptAtWorkoutStart(34, enabled = false, askedBefore = false, granted = false))
        assertFalse(StepPermissionPolicy.shouldPromptAtWorkoutStart(34, enabled = true, askedBefore = false, granted = true))
        assertFalse(StepPermissionPolicy.shouldPromptAtWorkoutStart(28, enabled = true, askedBefore = false, granted = false))
    }

    @Test
    fun `allowed needs enabled and a permission that is granted or not required`() {
        assertTrue(StepPermissionPolicy.isAllowed(28, enabled = true, granted = false))
        assertTrue(StepPermissionPolicy.isAllowed(29, enabled = true, granted = true))
        assertFalse(StepPermissionPolicy.isAllowed(29, enabled = true, granted = false))
        assertFalse(StepPermissionPolicy.isAllowed(28, enabled = false, granted = true))
    }
}

@RunWith(RobolectricTestRunner::class)
class StepTrackingAccessTest {

    private lateinit var context: Context
    private lateinit var prefs: UserPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = UserPreferences(context)
        prefs.resetToDefaults()
    }

    @Test
    @Config(sdk = [28])
    fun `below API 29 steps are allowed by default without a permission`() {
        assertTrue(StepTrackingAccess.isStepTrackingAllowed(context))
        prefs.stepsForDistanceEnabled = false
        assertFalse(StepTrackingAccess.isStepTrackingAllowed(context))
    }

    @Test
    @Config(sdk = [34])
    fun `on API 29+ the runtime permission is required`() {
        assertFalse(StepTrackingAccess.isStepTrackingAllowed(context))
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        assertTrue(StepTrackingAccess.isStepTrackingAllowed(context))
    }

    @Test
    @Config(sdk = [34])
    fun `showing the prompt stops further prompts but keeps steps enabled`() {
        assertTrue(StepTrackingAccess.shouldPromptAtWorkoutStart(context))
        StepTrackingAccess.markPromptShown(context)
        assertFalse(StepTrackingAccess.shouldPromptAtWorkoutStart(context))
        // No answer yet (e.g. the screen was recreated): granting later still enables steps
        assertTrue(prefs.stepsForDistanceEnabled)
    }

    @Test
    @Config(sdk = [28])
    fun `declining turns steps off`() {
        StepTrackingAccess.decline(context)
        assertFalse(prefs.stepsForDistanceEnabled)
        assertFalse(StepTrackingAccess.isStepTrackingAllowed(context))
    }
}
