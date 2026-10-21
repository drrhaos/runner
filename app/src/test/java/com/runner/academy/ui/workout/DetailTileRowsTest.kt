package com.runner.academy.ui.workout

import com.runner.academy.ui.workout.DetailTile.AVG_SPEED
import com.runner.academy.ui.workout.DetailTile.CADENCE
import com.runner.academy.ui.workout.DetailTile.CALORIES
import com.runner.academy.ui.workout.DetailTile.ELAPSED
import com.runner.academy.ui.workout.DetailTile.ELEVATION_GAIN
import com.runner.academy.ui.workout.DetailTile.ELEVATION_LOSS
import com.runner.academy.ui.workout.DetailTile.HEART_RATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `four tiles are two and two, not three and a lone one`() {
        assertEquals(
            listOf(listOf(AVG_SPEED, HEART_RATE), listOf(CADENCE, CALORIES)),
            DetailTileRows.of(setOf(AVG_SPEED, HEART_RATE, CADENCE, CALORIES))
        )
    }

    @Test
    fun `six tiles are two full rows with gain and loss together`() {
        assertEquals(
            listOf(listOf(AVG_SPEED, CADENCE, CALORIES), listOf(ELAPSED, ELEVATION_GAIN, ELEVATION_LOSS)),
            DetailTileRows.of(setOf(ELEVATION_LOSS, ELEVATION_GAIN, ELAPSED, CALORIES, CADENCE, AVG_SPEED))
        )
    }

    @Test
    fun `all seven are three, two and two`() {
        assertEquals(
            listOf(listOf(AVG_SPEED, HEART_RATE, CADENCE), listOf(CALORIES, ELAPSED), listOf(ELEVATION_GAIN, ELEVATION_LOSS)),
            DetailTileRows.of(DetailTile.entries.toSet())
        )
    }

    @Test
    fun `gain and loss are never split and no row is a lone tile`() {
        val optional = listOf(HEART_RATE, CADENCE, ELAPSED)
        for (mask in 0 until (1 shl optional.size)) {
            for (withElevation in listOf(false, true)) {
                val shown = setOf(AVG_SPEED, CALORIES) +
                    optional.filterIndexed { i, _ -> mask and (1 shl i) != 0 } +
                    (if (withElevation) setOf(ELEVATION_GAIN, ELEVATION_LOSS) else emptySet())
                val rows = DetailTileRows.of(shown)
                assertTrue("$shown → $rows", rows.all { it.size in 2..DetailTileRows.COLUMNS })
                if (withElevation) assertTrue("$shown → $rows", rows.any { it.containsAll(listOf(ELEVATION_GAIN, ELEVATION_LOSS)) })
            }
        }
    }

    @Test
    fun `no tiles is no rows`() {
        assertEquals(emptyList<List<DetailTile>>(), DetailTileRows.of(emptySet()))
    }
}
