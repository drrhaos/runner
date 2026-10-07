package com.runner.academy.ui.records

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.data.BestEffort
import com.runner.academy.data.RecordCard
import com.runner.academy.data.RecordDistance
import com.runner.academy.data.RecordStatus
import com.runner.academy.ui.records.RecordCardText.Tone
import com.runner.academy.util.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordCardTextTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun entry(distance: RecordDistance, elapsedMs: Long, status: RecordStatus, stepsShare: Float = 0f) =
        RecordCard.Entry(distance, BestEffort(1L, distance.meters, elapsedMs, 0L, elapsedMs, stepsShare), status)

    private fun text(vararg entries: RecordCard.Entry) = RecordCardText.of(context, RecordCard(entries.toList()))

    @Test
    fun `one new record is a congratulation with its improvement`() {
        val content = text(entry(RecordDistance.KM_5, 1_471_000L, RecordStatus.New(improvementMs = 42_000L)))

        assertEquals("New personal record!", content.title)
        assertEquals(listOf("5 km — 24:31, 0:42 faster"), content.lines)
        assertEquals(Tone.CONGRATULATION, content.tone)
        assertFalse(content.stepsNote)
    }

    @Test
    fun `two new records take the plural title, a first result beside them its own line`() {
        val content = text(
            entry(RecordDistance.KM_1, 238_000L, RecordStatus.New(improvementMs = 5_000L)),
            entry(RecordDistance.KM_5, 1_471_000L, RecordStatus.New(improvementMs = 42_000L)),
            entry(RecordDistance.KM_10, 3_062_000L, RecordStatus.First)
        )

        assertEquals("New personal records!", content.title)
        assertEquals(
            listOf("1 km — 3:58, 0:05 faster", "5 km — 24:31, 0:42 faster", "First result: 10 km — 51:02"),
            content.lines
        )
        assertEquals(Tone.CONGRATULATION, content.tone)
    }

    @Test
    fun `only first results are no congratulation`() {
        val content = text(
            entry(RecordDistance.KM_1, 238_000L, RecordStatus.First),
            entry(RecordDistance.KM_5, 1_598_000L, RecordStatus.First)
        )

        assertEquals("First result", content.title)
        assertEquals(listOf("First result: 1 km — 3:58", "First result: 5 km — 26:38"), content.lines)
        assertEquals(Tone.NEUTRAL, content.tone)
    }

    @Test
    fun `a record still standing is one badge line listing the distances`() {
        val content = text(
            entry(RecordDistance.KM_1, 238_000L, RecordStatus.Current),
            entry(RecordDistance.KM_5, 1_471_000L, RecordStatus.Current)
        )

        assertEquals("Personal record: 1 km — 3:58, 5 km — 24:31", content.title)
        assertTrue(content.lines.isEmpty())
        assertEquals(Tone.CONGRATULATION, content.tone)
    }

    @Test
    fun `a beaten record names the date it stood until, on a neutral card`() {
        val until = Date(1_790_000_000_000L)

        val content = text(entry(RecordDistance.KM_5, 1_471_000L, RecordStatus.Former(until)))

        assertEquals("Former record: 5 km — 24:31 (until ${FormatUtils.formatDate(until)})", content.title)
        assertTrue(content.lines.isEmpty())
        assertEquals(Tone.NEUTRAL, content.tone)
    }

    @Test
    fun `current and former records are two lines, the current one first`() {
        val until = Date(1_790_000_000_000L)

        val content = text(
            entry(RecordDistance.KM_1, 238_000L, RecordStatus.Former(until)),
            entry(RecordDistance.KM_5, 1_471_000L, RecordStatus.Current)
        )

        assertEquals("Personal record: 5 km — 24:31", content.title)
        assertEquals(listOf("Former record: 1 km — 3:58 (until ${FormatUtils.formatDate(until)})"), content.lines)
        assertEquals(Tone.CONGRATULATION, content.tone)
    }

    @Test
    fun `former records beaten on different dates get a line each`() {
        val first = Date(1_780_000_000_000L)
        val second = Date(1_790_000_000_000L)

        val content = text(
            entry(RecordDistance.KM_1, 238_000L, RecordStatus.Former(second)),
            entry(RecordDistance.KM_5, 1_471_000L, RecordStatus.Former(first)),
            entry(RecordDistance.KM_10, 3_062_000L, RecordStatus.Former(second))
        )

        assertEquals("Former record: 1 km — 3:58, 10 km — 51:02 (until ${FormatUtils.formatDate(second)})", content.title)
        assertEquals(listOf("Former record: 5 km — 24:31 (until ${FormatUtils.formatDate(first)})"), content.lines)
    }

    @Test
    fun `steps in the window mark the time approximate and add the note`() {
        val content = text(
            entry(RecordDistance.KM_1, 238_000L, RecordStatus.Current, stepsShare = 0.04f),
            entry(RecordDistance.KM_5, 1_471_000L, RecordStatus.Current)
        )

        assertEquals("Personal record: 1 km — ≈3:58, 5 km — 24:31", content.title)
        assertTrue(content.stepsNote)
    }

    @Test
    fun `an hour and more shows hours, half marathon by its name`() {
        val content = text(entry(RecordDistance.HALF_MARATHON, 6_727_000L, RecordStatus.Current))

        assertEquals("Personal record: Half marathon — 1:52:07", content.title)
    }

    @Test
    @Config(qualifiers = "ru")
    fun `russian texts`() {
        val content = text(
            entry(RecordDistance.KM_5, 1_471_000L, RecordStatus.New(improvementMs = 42_000L)),
            entry(RecordDistance.KM_10, 3_062_000L, RecordStatus.First)
        )

        assertEquals("Новый личный рекорд!", content.title)
        assertEquals(listOf("5 км — 24:31, на 0:42 быстрее", "Первый результат: 10 км — 51:02"), content.lines)
    }
}
