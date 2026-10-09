package com.runner.academy.ui.workout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.util.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DetailTileTextTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the distance is read in whole kilometres and metres as shown`() {
        assertEquals("Distance 5 kilometers 20 meters", DetailTileText.distanceA11y(context, 5.02f))
        assertEquals("rounded like the two decimals of the tile", "Distance 5 kilometers 10 meters", DetailTileText.distanceA11y(context, 5.0099f))
        assertEquals("Distance 5 kilometers", DetailTileText.distanceA11y(context, 5.0f))
        assertEquals("Distance 350 meters", DetailTileText.distanceA11y(context, 0.35f))
        assertEquals("Distance 0 meters", DetailTileText.distanceA11y(context, 0f))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the distance in russian`() {
        assertEquals("Дистанция 21 километр 100 метров", DetailTileText.distanceA11y(context, 21.1f))
        assertEquals("Дистанция 3 километра", DetailTileText.distanceA11y(context, 3f))
    }

    @Test
    fun `the time next to the pace is the moving time when the elapsed one has its own tile`() {
        assertEquals("Moving time 25 minutes 30 seconds", DetailTileText.durationA11y(context, 1_530_000L, showsElapsed = true))
        assertEquals("Time 25 minutes 30 seconds", DetailTileText.durationA11y(context, 1_530_000L, showsElapsed = false))
        assertEquals("Elapsed time 1 hour 2 minutes", DetailTileText.elapsedA11y(context, 3_720_000L))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the time in russian`() {
        assertEquals("Время в движении 25 минут 30 секунд", DetailTileText.durationA11y(context, 1_530_000L, showsElapsed = true))
        assertEquals("Время 21 минута", DetailTileText.durationA11y(context, 1_260_000L, showsElapsed = false))
        assertEquals("Общее время 28 минут 40 секунд", DetailTileText.elapsedA11y(context, 1_720_000L))
    }

    @Test
    fun `the pace is read in minutes and seconds per kilometre, an unknown one says so`() {
        assertEquals("Average pace 5 minutes 30 seconds per kilometer", DetailTileText.paceA11y(context, 5.5f))
        assertEquals("Average pace 6 minutes per kilometer", DetailTileText.paceA11y(context, 6f))
        assertEquals("Average pace unknown", DetailTileText.paceA11y(context, 0f))
    }

    @Test
    fun `a pace that is not a number is not spoken`() {
        assertEquals("", FormatUtils.formatPaceForTTS(Float.NaN, context))
        assertEquals("", FormatUtils.formatPaceForTTS(Float.POSITIVE_INFINITY, context, metric = false))
        assertEquals("5 minutes per mile", FormatUtils.formatPaceForTTS(5f, context, metric = false))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the pace in russian`() {
        assertEquals("Средний темп 5 минут 30 секунд на километр", DetailTileText.paceA11y(context, 5.5f))
        assertEquals("Средний темп неизвестен", DetailTileText.paceA11y(context, 0f))
    }

    @Test
    fun `the speed is read with the decimal of the tile, a whole one without it`() {
        assertEquals("Average speed 11.8 kilometers per hour", DetailTileText.speedA11y(context, 11.8f))
        assertEquals("Average speed 12 kilometers per hour", DetailTileText.speedA11y(context, 11.97f))
        assertEquals("Average speed 1 kilometer per hour", DetailTileText.speedA11y(context, 1f))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the speed in russian`() {
        assertEquals("Средняя скорость 11,8 километра в час", DetailTileText.speedA11y(context, 11.8f))
        assertEquals("Средняя скорость 21 километр в час", DetailTileText.speedA11y(context, 21f))
        assertEquals("Средняя скорость 12 километров в час", DetailTileText.speedA11y(context, 12f))
    }

    @Test
    fun `the calories are declined`() {
        assertEquals("Calories 300 kilocalories", DetailTileText.caloriesA11y(context, 300))
        assertEquals("Calories 1 kilocalorie", DetailTileText.caloriesA11y(context, 1))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the calories in russian`() {
        assertEquals("Калории 300 килокалорий", DetailTileText.caloriesA11y(context, 300))
        assertEquals("Калории 22 килокалории", DetailTileText.caloriesA11y(context, 22))
        assertEquals("Калории 1 килокалория", DetailTileText.caloriesA11y(context, 1))
    }
}
