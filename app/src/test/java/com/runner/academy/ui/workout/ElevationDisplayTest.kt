package com.runner.academy.ui.workout

import com.runner.academy.R
import com.runner.academy.data.ElevationSource
import com.runner.academy.util.WorkoutDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElevationDisplayTest {

    private val current = WorkoutDerivation.CURRENT_METRICS_VERSION

    @Test
    fun `stored gain and loss are shown rounded with their source, whatever the version`() {
        assertEquals(
            ElevationDisplay.Value(124, 118, ElevationSource.GPS),
            ElevationDisplay.of(123.6f, 118.2f, ElevationSource.GPS, current, trackHasAltitude = true)
        )
        assertEquals(
            ElevationDisplay.Value(0, 0, ElevationSource.FILE),
            ElevationDisplay.of(0f, 0f, ElevationSource.FILE, 0, trackHasAltitude = false)
        )
    }

    @Test
    fun `not computed yet with altitudes in the track is pending`() {
        assertEquals(ElevationDisplay.Pending, ElevationDisplay.of(null, null, null, current - 1, trackHasAltitude = true))
        assertEquals(ElevationDisplay.Pending, ElevationDisplay.of(null, null, null, 0, trackHasAltitude = true))
    }

    @Test
    fun `GPS and a file get a note and an explanation, the barometer none`() {
        assertEquals(R.string.workout_elevation_source_gps, ElevationText.sourceTexts(ElevationSource.GPS)!!.note)
        assertEquals(R.string.workout_elevation_source_file_short, ElevationText.sourceTexts(ElevationSource.FILE)!!.chartSubtitle)
        assertNull(ElevationText.sourceTexts(ElevationSource.BAROMETER))
        assertNull(ElevationText.sourceTexts(ElevationSource.NONE))
    }

    @Test
    fun `no altitudes, or computed without them, is hidden`() {
        assertEquals(ElevationDisplay.None, ElevationDisplay.of(null, null, ElevationSource.NONE, current, trackHasAltitude = true))
        assertEquals(ElevationDisplay.None, ElevationDisplay.of(null, null, null, 0, trackHasAltitude = false))
        assertEquals(ElevationDisplay.None, ElevationDisplay.of(12f, 10f, ElevationSource.NONE, current, trackHasAltitude = true))
        assertEquals(ElevationDisplay.None, ElevationDisplay.of(Float.NaN, 10f, ElevationSource.GPS, current, trackHasAltitude = true))
    }
}
