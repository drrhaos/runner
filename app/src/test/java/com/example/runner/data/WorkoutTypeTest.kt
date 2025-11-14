package com.example.runner.data

import org.junit.Test
import org.junit.Assert.*

/**
 * Тесты для WorkoutType
 */
class WorkoutTypeTest {

    @Test
    fun `WorkoutType enum should have correct names`() {
        // Then
        assertEquals("EASY_RUN", WorkoutType.EASY_RUN.name)
        assertEquals("TEMPO_RUN", WorkoutType.TEMPO_RUN.name)
        assertEquals("INTERVAL_TRAINING", WorkoutType.INTERVAL_TRAINING.name)
        assertEquals("LONG_RUN", WorkoutType.LONG_RUN.name)
        assertEquals("RECOVERY_RUN", WorkoutType.RECOVERY_RUN.name)
        assertEquals("RACE", WorkoutType.RACE.name)
    }

    @Test
    fun `WorkoutType enum should have correct ordinal values`() {
        // Then
        assertEquals(0, WorkoutType.EASY_RUN.ordinal)
        assertEquals(1, WorkoutType.TEMPO_RUN.ordinal)
        assertEquals(2, WorkoutType.INTERVAL_TRAINING.ordinal)
        assertEquals(3, WorkoutType.LONG_RUN.ordinal)
        assertEquals(4, WorkoutType.RECOVERY_RUN.ordinal)
        assertEquals(5, WorkoutType.RACE.ordinal)
    }

    @Test
    fun `WorkoutType values should return all enum values`() {
        // When
        val values = WorkoutType.values()

        // Then
        assertEquals(6, values.size)
        assertTrue(values.contains(WorkoutType.EASY_RUN))
        assertTrue(values.contains(WorkoutType.TEMPO_RUN))
        assertTrue(values.contains(WorkoutType.INTERVAL_TRAINING))
        assertTrue(values.contains(WorkoutType.LONG_RUN))
        assertTrue(values.contains(WorkoutType.RECOVERY_RUN))
        assertTrue(values.contains(WorkoutType.RACE))
    }

    @Test
    fun `WorkoutType valueOf should return correct enum`() {
        // Then
        assertEquals(WorkoutType.EASY_RUN, WorkoutType.valueOf("EASY_RUN"))
        assertEquals(WorkoutType.TEMPO_RUN, WorkoutType.valueOf("TEMPO_RUN"))
        assertEquals(WorkoutType.INTERVAL_TRAINING, WorkoutType.valueOf("INTERVAL_TRAINING"))
        assertEquals(WorkoutType.LONG_RUN, WorkoutType.valueOf("LONG_RUN"))
        assertEquals(WorkoutType.RECOVERY_RUN, WorkoutType.valueOf("RECOVERY_RUN"))
        assertEquals(WorkoutType.RACE, WorkoutType.valueOf("RACE"))
    }

    @Test
    fun `WorkoutType valueOf should throw exception for invalid name`() {
        // When & Then
        try {
            WorkoutType.valueOf("INVALID_TYPE")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("INVALID_TYPE") == true)
        }
    }

    @Test
    fun `WorkoutType ordinal should be correct`() {
        // Then
        assertEquals(0, WorkoutType.EASY_RUN.ordinal)
        assertEquals(1, WorkoutType.TEMPO_RUN.ordinal)
        assertEquals(2, WorkoutType.INTERVAL_TRAINING.ordinal)
        assertEquals(3, WorkoutType.LONG_RUN.ordinal)
        assertEquals(4, WorkoutType.RECOVERY_RUN.ordinal)
        assertEquals(5, WorkoutType.RACE.ordinal)
    }

    @Test
    fun `WorkoutType toString should return name`() {
        // Then
        assertEquals("EASY_RUN", WorkoutType.EASY_RUN.toString())
        assertEquals("TEMPO_RUN", WorkoutType.TEMPO_RUN.toString())
        assertEquals("INTERVAL_TRAINING", WorkoutType.INTERVAL_TRAINING.toString())
        assertEquals("LONG_RUN", WorkoutType.LONG_RUN.toString())
        assertEquals("RECOVERY_RUN", WorkoutType.RECOVERY_RUN.toString())
        assertEquals("RACE", WorkoutType.RACE.toString())
    }

    @Test
    fun `WorkoutType should be comparable`() {
        // Then
        assertTrue(WorkoutType.EASY_RUN < WorkoutType.TEMPO_RUN)
        assertTrue(WorkoutType.TEMPO_RUN < WorkoutType.INTERVAL_TRAINING)
        assertTrue(WorkoutType.INTERVAL_TRAINING < WorkoutType.LONG_RUN)
        assertTrue(WorkoutType.LONG_RUN < WorkoutType.RECOVERY_RUN)
        assertTrue(WorkoutType.RECOVERY_RUN < WorkoutType.RACE)
    }

    @Test
    fun `WorkoutType should be equal to itself`() {
        // Then
        assertEquals(WorkoutType.EASY_RUN, WorkoutType.EASY_RUN)
        assertEquals(WorkoutType.TEMPO_RUN, WorkoutType.TEMPO_RUN)
        assertEquals(WorkoutType.INTERVAL_TRAINING, WorkoutType.INTERVAL_TRAINING)
        assertEquals(WorkoutType.LONG_RUN, WorkoutType.LONG_RUN)
        assertEquals(WorkoutType.RECOVERY_RUN, WorkoutType.RECOVERY_RUN)
        assertEquals(WorkoutType.RACE, WorkoutType.RACE)
    }

    @Test
    fun `WorkoutType should not be equal to different types`() {
        // Then
        assertNotEquals(WorkoutType.EASY_RUN, WorkoutType.TEMPO_RUN)
        assertNotEquals(WorkoutType.TEMPO_RUN, WorkoutType.INTERVAL_TRAINING)
        assertNotEquals(WorkoutType.INTERVAL_TRAINING, WorkoutType.LONG_RUN)
        assertNotEquals(WorkoutType.LONG_RUN, WorkoutType.RECOVERY_RUN)
        assertNotEquals(WorkoutType.RECOVERY_RUN, WorkoutType.RACE)
        assertNotEquals(WorkoutType.RACE, WorkoutType.EASY_RUN)
    }
}
