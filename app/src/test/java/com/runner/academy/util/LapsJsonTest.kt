package com.runner.academy.util

import com.runner.academy.data.Lap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LapsJsonTest {

    @Test
    fun laps_roundTrip() {
        val laps = listOf(
            Lap(start = 1_700_000_000_000L, timerMs = 300_000L, distanceM = 1000f, avgHr = 150, maxHr = 165, avgCadence = 172f),
            Lap(start = 1_700_000_300_000L, timerMs = null, distanceM = null, avgHr = null, maxHr = null, avgCadence = null)
        )

        assertEquals(laps, LapsJson.parse(LapsJson.toJson(laps)))
    }

    @Test
    fun noLaps_isNoJson() {
        assertNull(LapsJson.toJson(emptyList()))
    }

    @Test
    fun brokenJson_orLapsWithoutStart_areSkipped() {
        assertTrue(LapsJson.parse("not json").isEmpty())
        assertTrue(LapsJson.parse(null).isEmpty())
        assertEquals(
            listOf(Lap(start = 5L, timerMs = 10L, distanceM = null, avgHr = null, maxHr = null, avgCadence = null)),
            LapsJson.parse("""[{"timerMs":3},{"start":5,"timerMs":10}]""")
        )
    }
}
