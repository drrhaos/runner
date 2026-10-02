package com.runner.academy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GpsDiagnosticsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dayMs = 24 * 60 * 60 * 1000L

    private fun store() = GpsDiagnosticsStore(tmp.root.resolve("gps-diagnostics"))

    @Test
    fun attachToWorkout_movesSessionFileUnderWorkoutId() {
        val store = store()
        store.sessionFile(startTimeMs = 1000L).apply { parentFile!!.mkdirs(); writeText("x\n") }

        assertTrue(store.attachToWorkout(startTimeMs = 1000L, workoutId = 42L))

        assertFalse(store.sessionFile(1000L).exists())
        assertEquals("x\n", store.workoutFile(42L)!!.readText())
    }

    @Test
    fun attachToWorkout_withoutRecordingIsNoOp() {
        assertFalse(store().attachToWorkout(startTimeMs = 1000L, workoutId = 42L))
        assertNull(store().workoutFile(42L))
    }

    @Test
    fun delete_removesWorkoutFile() {
        val store = store()
        store.sessionFile(1000L).apply { parentFile!!.mkdirs(); writeText("x") }
        store.attachToWorkout(1000L, 7L)

        store.delete(7L)

        assertNull(store.workoutFile(7L))
    }

    @Test
    fun cleanupOrphans_dropsOldUnsavedSessionsOnly() {
        val store = store()
        val now = 100 * dayMs
        val old = store.sessionFile(1L).apply { parentFile!!.mkdirs(); writeText("old"); setLastModified(now - 2 * dayMs) }
        val fresh = store.sessionFile(2L).apply { writeText("fresh"); setLastModified(now - 60_000L) }
        store.sessionFile(3L).apply { writeText("saved") }
        store.attachToWorkout(3L, 9L)
        store.workoutFile(9L)!!.setLastModified(now - 30 * dayMs)

        store.cleanupOrphans(nowMs = now)

        assertFalse(old.exists())
        assertTrue(fresh.exists()) // may still be recording or about to be saved
        assertTrue(store.workoutFile(9L)!!.exists()) // saved workouts are never cleaned up
    }

    @Test
    fun cleanupOrphans_onMissingDirectoryIsSafe() {
        store().cleanupOrphans(nowMs = dayMs)
    }
}
