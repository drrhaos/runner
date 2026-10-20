package com.runner.academy.ui.workout

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MetricValueTest {

    @Test
    fun `distance is two decimals without a unit`() {
        assertEquals("5.02", MetricValue.distance(5.018f, Locale.US))
        assertEquals("5,02", MetricValue.distance(5.018f, Locale.forLanguageTag("ru")))
    }

    @Test
    fun `pace is minutes and seconds without a unit`() {
        assertEquals("5:30", MetricValue.pace(5.5f))
    }

    @Test
    fun `no pace is a dashed placeholder`() {
        assertEquals("--:--", MetricValue.pace(0f))
        assertEquals("--:--", MetricValue.pace(Float.NaN))
    }

    @Test
    fun `speed is one decimal without a unit`() {
        assertEquals("11.8", MetricValue.speed(11.78f, Locale.US))
    }

    @Test
    fun `whole values are plain numbers`() {
        assertEquals("172", MetricValue.whole(172))
    }
}
