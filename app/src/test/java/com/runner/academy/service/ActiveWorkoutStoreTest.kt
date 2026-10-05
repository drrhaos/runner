package com.runner.academy.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActiveWorkoutStoreTest {

    private val store = ActiveWorkoutStore(ApplicationProvider.getApplicationContext<Context>())
    private val now = 1_000_000_000L

    @After
    fun tearDown() = store.clear()

    @Test
    fun noCheckpoint_isNotActive() {
        assertFalse(store.isWorkoutActive(now))
    }

    @Test
    fun runningOrPausedCheckpoint_isActive() {
        store.save(ActiveWorkoutCheckpoint(isTracking = true, savedAt = now - 15_000L))
        assertTrue(store.isWorkoutActive(now))

        store.save(ActiveWorkoutCheckpoint(isTracking = true, isPaused = true, savedAt = now - 60_000L))
        assertTrue(store.isWorkoutActive(now))
    }

    @Test
    fun finishedCheckpoint_isNotActive() {
        store.save(ActiveWorkoutCheckpoint(isTracking = false, isPaused = false, savedAt = now))

        assertFalse(store.isWorkoutActive(now))
    }

    @Test
    fun abandonedCheckpoint_stopsBeingActive() {
        // A restore the system refused keeps the checkpoint with the run's flags until the app opens
        store.save(ActiveWorkoutCheckpoint(isTracking = true, savedAt = now - ActiveWorkoutStore.LIVE_WINDOW_MS - 1L))

        assertFalse(store.isWorkoutActive(now))
    }
}
