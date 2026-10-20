package com.runner.academy.ui.workout

import com.runner.academy.data.Lap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LapTableTest {

    private fun lap(timerMs: Long?, distanceM: Float?, avgHr: Int? = null) =
        Lap(start = 0L, timerMs = timerMs, distanceM = distanceM, avgHr = avgHr, maxHr = null, avgCadence = null)

    private fun table(vararg laps: Lap) = LapTable.of(laps.toList(), Locale.US)

    @Test
    fun `fewer than two laps show no table`() {
        assertNull(table())
        assertNull(table(lap(300_000L, 1000f)))
    }

    @Test
    fun `a row per lap with number, km, time, pace and heart rate`() {
        val table = table(lap(300_000L, 1000f, 150), lap(330_500L, 1050f, 155))!!

        assertEquals(2, table.rows.size)
        val row = table.rows[1]
        assertEquals("2", row.number)
        assertEquals("1.05", row.km)
        assertEquals("5:30", row.time)
        // 330.5 s over 1.05 km: 314.8 s a km, whole seconds as everywhere
        assertEquals("5:14", row.pace)
        assertEquals("155", row.heartRate)
        assertTrue(table.showsHeartRate)
    }

    @Test
    fun `the heart rate column goes when no lap has one`() {
        val table = table(lap(300_000L, 1000f), lap(300_000L, 1000f))!!

        assertFalse(table.showsHeartRate)
    }

    @Test
    fun `a missing value is a dash, and so is the pace it needs`() {
        val row = table(lap(null, 1000f, 150), lap(300_000L, null))!!.rows

        assertEquals("—", row[0].time)
        assertEquals("—", row[0].pace)
        assertEquals("—", row[1].km)
        assertEquals("—", row[1].pace)
        assertEquals("—", row[1].heartRate)
    }
}
