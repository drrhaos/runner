package com.runner.academy.ui.workout

import com.runner.academy.ui.workout.DetailTile.AVG_SPEED
import com.runner.academy.ui.workout.DetailTile.CADENCE
import com.runner.academy.ui.workout.DetailTile.CALORIES
import com.runner.academy.ui.workout.DetailTile.ELAPSED
import com.runner.academy.ui.workout.DetailTile.ELEVATION_GAIN
import com.runner.academy.ui.workout.DetailTile.ELEVATION_LOSS
import com.runner.academy.ui.workout.DetailTile.HEART_RATE
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailTileRowsTest {

    @Test
    fun `a manual workout is speed and calories in one row`() {
        assertEquals(listOf(listOf(AVG_SPEED, CALORIES)), DetailTileRows.of(setOf(CALORIES, AVG_SPEED)))
    }

    @Test
    fun `heart rate without cadence takes the free cell instead of a row of its own`() {
        assertEquals(
            listOf(listOf(AVG_SPEED, HEART_RATE, CALORIES)),
            DetailTileRows.of(setOf(AVG_SPEED, HEART_RATE, CALORIES))
        )
    }

    @Test
    fun `heart rate and cadence sit next to speed`() {
        assertEquals(
            listOf(listOf(AVG_SPEED, HEART_RATE, CADENCE), listOf(CALORIES, ELAPSED)),
            DetailTileRows.of(setOf(AVG_SPEED, HEART_RATE, CADENCE, CALORIES, ELAPSED))
        )
    }

    @Test
    fun `gain and loss end the last row when both fit`() {
        assertEquals(
            listOf(listOf(AVG_SPEED, CADENCE, CALORIES), listOf(ELAPSED, ELEVATION_GAIN, ELEVATION_LOSS)),
            DetailTileRows.of(setOf(ELEVATION_LOSS, ELEVATION_GAIN, ELAPSED, CALORIES, CADENCE, AVG_SPEED))
        )
    }

    @Test
    fun `gain and loss are not split when they do not fit`() {
        assertEquals(
            listOf(listOf(AVG_SPEED, CALORIES), listOf(ELEVATION_GAIN, ELEVATION_LOSS)),
            DetailTileRows.of(setOf(AVG_SPEED, CALORIES, ELEVATION_GAIN, ELEVATION_LOSS))
        )
    }

    @Test
    fun `no tiles is no rows`() {
        assertEquals(emptyList<List<DetailTile>>(), DetailTileRows.of(emptySet()))
    }
}
