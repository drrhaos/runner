package com.runner.academy.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportConflictsTest {

    private val minute = 60_000L

    private fun side(startMs: Long, durationMs: Long, km: Float = 5f) = ConflictSide(startMs, durationMs, km, hasTrack = false)

    private fun span(startMin: Long, durationMin: Long) = ImportConflicts.spanOf(startMin * minute, durationMin * minute)

    @Test
    fun overlap_needsSharedTime() {
        assertTrue(ImportConflicts.overlap(span(0, 30), span(10, 30)))
        assertTrue("one inside the other", ImportConflicts.overlap(span(0, 60), span(10, 5)))
        assertFalse("back to back", ImportConflicts.overlap(span(0, 30), span(30, 30)))
        assertFalse(ImportConflicts.overlap(span(0, 30), span(40, 30)))
    }

    @Test
    fun workoutWithoutTime_stillHasAMoment() {
        assertTrue(ImportConflicts.overlap(span(10, 0), span(10, 0)))
        assertTrue(ImportConflicts.overlap(span(10, 0), span(0, 30)))
    }

    @Test
    fun isSame_toleratesASecondAndOnePercent() {
        val run = side(0, 30 * minute, km = 5f)
        assertTrue(ImportConflicts.isSame(run, run))
        assertTrue(ImportConflicts.isSame(run, side(1000, 30 * minute + 15_000, km = 5.04f)))
        assertFalse("another start", ImportConflicts.isSame(run, side(2000, 30 * minute)))
        assertFalse("another distance", ImportConflicts.isSame(run, side(0, 30 * minute, km = 5.1f)))
        assertFalse("another time", ImportConflicts.isSame(run, side(0, 31 * minute)))
    }

    @Test
    fun isSame_shortWorkoutsHaveAFloor() {
        // 1 % of 100 m is a metre: a rounding of the distance must still be the same row
        assertTrue(ImportConflicts.isSame(side(0, 30_000, km = 0.1f), side(0, 30_500, km = 0.105f)))
    }
}
