package com.runner.academy.ui.workout

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DetailTileValueTest {

    @Test
    fun `distance is two decimals without a unit`() {
        assertEquals("5.02", DetailTileValue.distance(5.018f, Locale.US))
        assertEquals("5,02", DetailTileValue.distance(5.018f, Locale.forLanguageTag("ru")))
    }

    @Test
    fun `pace is minutes and seconds without a unit`() {
        assertEquals("5:30", DetailTileValue.pace(5.5f))
    }

    @Test
    fun `no pace is a dashed placeholder`() {
        assertEquals("--:--", DetailTileValue.pace(0f))
        assertEquals("--:--", DetailTileValue.pace(Float.NaN))
    }

    @Test
    fun `speed is one decimal without a unit`() {
        assertEquals("11.8", DetailTileValue.speed(11.78f, Locale.US))
    }

    @Test
    fun `whole values are plain numbers`() {
        assertEquals("172", DetailTileValue.whole(172))
    }
}
