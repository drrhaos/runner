package com.runner.academy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

/** Records and their history read from best efforts (ticket 06, cases 5–6). */
class RecordBookTest {

    private fun row(workoutId: Long, day: Int, elapsedMs: Long, distance: RecordDistance = RecordDistance.KM_5) =
        EffortRow(
            BestEffort(workoutId, distance.meters, elapsedMs, 0L, elapsedMs, 0f),
            Date(day * DAY_MS),
            WorkoutType.EASY_RUN
        )

    private fun min(minutes: Int, seconds: Int) = (minutes * 60L + seconds) * 1_000L

    /** 26:38, a slower 27:05, 26:18, 25:13, a slower 26:30 — by date, given out of order. */
    private val rows = listOf(
        row(4, day = 40, elapsedMs = min(25, 13)),
        row(1, day = 10, elapsedMs = min(26, 38)),
        row(5, day = 50, elapsedMs = min(26, 30)),
        row(2, day = 15, elapsedMs = min(27, 5)),
        row(3, day = 20, elapsedMs = min(26, 18))
    )

    private fun RecordBook.historyIds(distance: RecordDistance = RecordDistance.KM_5) =
        history(distance).map { it.effort.workoutId }

    @Test
    fun `5 the history is the chain of improvements by workout date`() {
        val book = RecordBook.from(rows)

        assertEquals(listOf(1L, 3L, 4L), book.historyIds())
        assertEquals(4L, book.current(RecordDistance.KM_5)!!.effort.workoutId)
        assertNull(book.current(RecordDistance.KM_10))
        assertEquals(emptyList<EffortRow>(), book.history(RecordDistance.KM_10))
    }

    @Test
    fun `5 deleting or excluding the record falls back to the next best`() {
        // An excluded workout is filtered by the query, so the book sees it as gone too
        val book = RecordBook.from(rows.filter { it.effort.workoutId != 4L })

        assertEquals(min(26, 18), book.current(RecordDistance.KM_5)!!.effort.elapsedMs)
        assertEquals(listOf(1L, 3L), book.historyIds())
    }

    @Test
    fun `5 a later date moves a workout along the history`() {
        val redated = rows.map { if (it.effort.workoutId == 1L) it.copy(date = Date(60 * DAY_MS)) else it }

        assertEquals(listOf(2L, 3L, 4L), RecordBook.from(redated).historyIds())
    }

    @Test
    fun `an equal time keeps the record with the earlier date`() {
        val book = RecordBook.from(listOf(row(2, day = 20, elapsedMs = 1_500_000L), row(1, day = 10, elapsedMs = 1_500_000L)))

        assertEquals(listOf(1L), book.historyIds())
        assertEquals(1L, book.current(RecordDistance.KM_5)!!.effort.workoutId)
    }

    @Test
    fun `distances are separate and unknown ones are ignored`() {
        val unknown = EffortRow(BestEffort(9, 3_000, 1L, 0L, 1L, 0f), Date(0L), WorkoutType.EASY_RUN)
        val book = RecordBook.from(rows + row(1, day = 10, elapsedMs = min(4, 50), distance = RecordDistance.KM_1) + unknown)

        assertEquals(listOf(1L), book.historyIds(RecordDistance.KM_1))
        assertEquals(listOf(1L, 3L, 4L), book.historyIds())
    }

    @Test
    fun `6 the card of a saved run - first, new, then former`() {
        val first = row(1, day = 10, elapsedMs = min(25, 13))
        assertEquals(
            RecordCard(listOf(RecordCard.Entry(RecordDistance.KM_5, first.effort, RecordStatus.First))),
            RecordBook.from(listOf(first)).cardFor(1, justSaved = true)
        )

        val faster = row(2, day = 20, elapsedMs = min(24, 31))
        val afterFaster = RecordBook.from(listOf(first, faster))
        assertEquals(
            RecordCard(listOf(RecordCard.Entry(RecordDistance.KM_5, faster.effort, RecordStatus.New(improvementMs = 42_000L)))),
            afterFaster.cardFor(2, justSaved = true)
        )
        // Opened later from the list: no congratulation, the record is simply current
        assertEquals(RecordStatus.Current, afterFaster.cardFor(2, justSaved = false)!!.entries.single().status)

        val beaten = RecordBook.from(listOf(first, faster, row(3, day = 30, elapsedMs = min(24, 0))))
        assertEquals(
            RecordStatus.Former(until = Date(30 * DAY_MS)),
            beaten.cardFor(2, justSaved = false)!!.entries.single().status
        )
        assertEquals(RecordStatus.Former(until = Date(20 * DAY_MS)), beaten.cardFor(1, justSaved = true)!!.entries.single().status)
    }

    @Test
    fun `a run that is no record has no card`() {
        val book = RecordBook.from(listOf(row(1, day = 10, elapsedMs = min(25, 0)), row(2, day = 20, elapsedMs = min(26, 0))))

        assertNull(book.cardFor(2, justSaved = true))
        assertNull(book.cardFor(3, justSaved = true))
    }

    @Test
    fun `several records of one run go on one card, shortest distance first`() {
        val book = RecordBook.from(
            listOf(
                row(1, day = 10, elapsedMs = min(26, 0)),
                row(2, day = 20, elapsedMs = min(25, 0)),
                row(2, day = 20, elapsedMs = min(4, 40), distance = RecordDistance.KM_1)
            )
        )

        val card = book.cardFor(2, justSaved = true)!!

        assertEquals(listOf(RecordDistance.KM_1, RecordDistance.KM_5), card.entries.map { it.distance })
        assertEquals(listOf(RecordStatus.First, RecordStatus.New(60_000L)), card.entries.map { it.status })
    }

    @Test
    fun `distances held count the current records of a batch of workouts`() {
        val book = RecordBook.from(
            rows +
                row(6, day = 60, elapsedMs = min(4, 0), distance = RecordDistance.KM_1) +
                row(7, day = 70, elapsedMs = min(60, 0), distance = RecordDistance.KM_10) +
                // Slower than the current 5 km
                row(8, day = 80, elapsedMs = min(30, 0))
        )

        assertEquals(2, book.distancesHeldBy(setOf(6L, 7L, 8L)))
        assertEquals(1, book.distancesHeldBy(setOf(4L)))
        assertEquals(0, book.distancesHeldBy(setOf(1L, 3L, 8L)))
        assertEquals(0, RecordBook.from(emptyList()).distancesHeldBy(setOf(1L)))
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
