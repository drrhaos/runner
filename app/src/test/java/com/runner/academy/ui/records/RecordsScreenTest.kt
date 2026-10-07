package com.runner.academy.ui.records

import com.runner.academy.R
import com.runner.academy.data.BackfillState
import com.runner.academy.data.BestEffort
import com.runner.academy.data.EffortRow
import com.runner.academy.data.RecordBook
import com.runner.academy.data.RecordDistance
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class RecordsScreenTest {

    private fun row(workoutId: Long, distance: RecordDistance, elapsedMs: Long, day: Int, stepsShare: Float = 0f) =
        EffortRow(
            BestEffort(workoutId, distance.meters, elapsedMs, 0L, elapsedMs, stepsShare),
            Date(day * DAY_MS),
            WorkoutType.EASY_RUN
        )

    private val empty = RecordBook.from(emptyList())

    @Test
    fun `five rows in a fixed order, the ones not reached without a record`() {
        val book = RecordBook.from(listOf(row(1, RecordDistance.KM_5, 1_500_000L, 1), row(1, RecordDistance.KM_1, 280_000L, 1)))

        val rows = RecordsScreen.rows(book)

        assertEquals(RecordDistance.entries, rows.map { it.distance })
        assertEquals(280_000L, rows[0].record!!.effort.elapsedMs)
        assertEquals(1_500_000L, rows[1].record!!.effort.elapsedMs)
        assertNull(rows[2].record)
        assertFalse(rows[2].reached)
        assertTrue(rows[2].history.isEmpty())
    }

    @Test
    fun `history is newest first, each with the shown improvement over the one before, the oldest first`() {
        val book = RecordBook.from(
            listOf(
                row(1, RecordDistance.KM_5, 1_598_000L, 1),
                row(2, RecordDistance.KM_5, 1_578_000L, 2),
                row(3, RecordDistance.KM_5, 1_513_200L, 3),
                row(4, RecordDistance.KM_5, 1_471_900L, 4)
            )
        )

        val history = RecordsScreen.rows(book)[1].history

        assertEquals(listOf(4L, 3L, 2L, 1L), history.map { it.row.effort.workoutId })
        // 25:13 → 24:31 as shown: 0:42
        assertEquals(listOf(42_000L, 65_000L, 20_000L, null), history.map { it.improvementMs })
        assertTrue(RecordsScreen.rows(book)[1].showsHistory)
    }

    @Test
    fun `a history of one record is not offered`() {
        val book = RecordBook.from(listOf(row(1, RecordDistance.KM_1, 280_000L, 1)))

        assertFalse(RecordsScreen.rows(book)[0].showsHistory)
    }

    @Test
    fun `steps in the window mark the record approximate`() {
        val book = RecordBook.from(
            listOf(row(1, RecordDistance.KM_1, 280_000L, 1, stepsShare = 0.05f), row(1, RecordDistance.KM_5, 1_500_000L, 1))
        )

        val rows = RecordsScreen.rows(book)

        assertTrue(rows[0].approximate)
        assertFalse(rows[1].approximate)
    }

    @Test
    fun `a running pass shows its progress, a pending one before it starts is indeterminate`() {
        assertEquals(RecordsRecalc.Progress(37, 124), RecordsScreen.recalc(pending = true, progress = BackfillState.Running(37, 124)))
        assertEquals(RecordsRecalc.Progress(0, 5), RecordsScreen.recalc(pending = false, progress = BackfillState.Running(0, 5)))
        assertEquals(RecordsRecalc.Indeterminate, RecordsScreen.recalc(pending = true, progress = BackfillState.Idle))
        assertEquals(
            RecordsRecalc.Indeterminate,
            RecordsScreen.recalc(pending = true, progress = BackfillState.Done(0, afterImport = false))
        )
        assertEquals(RecordsRecalc.None, RecordsScreen.recalc(pending = false, progress = BackfillState.Idle))
        assertEquals(RecordsRecalc.None, RecordsScreen.recalc(pending = false, progress = BackfillState.Done(2, afterImport = true)))
    }

    @Test
    fun `no record and nothing recalculated is the empty state`() {
        val state = RecordsScreen.state(empty, pending = false, progress = BackfillState.Idle, manualOnly = false)

        assertTrue(state.empty)
        assertFalse(state.manualOnly)
        assertEquals(5, state.rows.size)
    }

    @Test
    fun `only manual workouts add their line to the empty state`() {
        val state = RecordsScreen.state(empty, pending = false, progress = BackfillState.Idle, manualOnly = true)

        assertTrue(state.empty)
        assertTrue(state.manualOnly)
    }

    @Test
    fun `no empty state while recalculating`() {
        val running = RecordsScreen.state(empty, pending = true, progress = BackfillState.Running(1, 3), manualOnly = true)
        val waiting = RecordsScreen.state(empty, pending = true, progress = BackfillState.Idle, manualOnly = false)

        assertFalse(running.empty)
        assertFalse(running.manualOnly)
        assertEquals(RecordsRecalc.Progress(1, 3), running.recalc)
        assertFalse(waiting.empty)
        assertEquals(RecordsRecalc.Indeterminate, waiting.recalc)
    }

    @Test
    fun `one record is no empty state, the records stay while recalculating`() {
        val book = RecordBook.from(listOf(row(1, RecordDistance.KM_1, 280_000L, 1)))

        val idle = RecordsScreen.state(book, pending = false, progress = BackfillState.Idle, manualOnly = false)
        val running = RecordsScreen.state(book, pending = true, progress = BackfillState.Running(1, 3), manualOnly = false)

        assertFalse(idle.empty)
        assertEquals(280_000L, running.rows[0].record!!.effort.elapsedMs)
    }

    @Test
    fun `records updated is announced after a backup import that changed some`() {
        assertEquals(2, RecordsAnnouncement.afterBackfill(BackfillState.Done(2, afterImport = true)))
        assertNull(RecordsAnnouncement.afterBackfill(BackfillState.Done(0, afterImport = true)))
        assertNull("the app start or a raised version is no import", RecordsAnnouncement.afterBackfill(BackfillState.Done(3, afterImport = false)))
        assertNull(RecordsAnnouncement.afterBackfill(BackfillState.Running(1, 2)))
        assertNull(RecordsAnnouncement.afterBackfill(BackfillState.Idle))
    }

    @Test
    fun `records updated is announced after a GPX import that changed some`() {
        assertEquals(3, RecordsAnnouncement.afterGpxImport(3))
        assertNull(RecordsAnnouncement.afterGpxImport(0))
    }

    @Test
    fun `open is offered unless the records are shown or a run is being recorded`() {
        assertTrue(RecordsAnnouncement.offersOpen(R.id.nav_workouts, workoutActive = false))
        assertTrue(RecordsAnnouncement.offersOpen(R.id.nav_workouts, workoutActive = true))
        assertTrue("the start screen before a run", RecordsAnnouncement.offersOpen(R.id.nav_tracking, workoutActive = false))
        assertFalse(RecordsAnnouncement.offersOpen(R.id.nav_tracking, workoutActive = true))
        assertFalse(RecordsAnnouncement.offersOpen(R.id.nav_records, workoutActive = false))
        assertFalse(RecordsAnnouncement.offersOpen(R.id.nav_all_records, workoutActive = false))
    }

    private fun point(i: Int, timestamp: Long) =
        TrackPoint(55.0 + i * 0.0001, 37.0, timestamp, 5f, null, null)

    private fun track(points: List<TrackPoint>, timeSynthetic: Boolean? = null) =
        TrackData(points, 0f, 0L, 0f, 0f, 0L, null, timeSynthetic = timeSynthetic)

    @Test
    fun `the exclusion row is shown only with a track`() {
        assertEquals(ExcludeFromRecordsRow.Hidden, ExcludeFromRecordsRow.of(null))
        assertEquals(ExcludeFromRecordsRow.Hidden, ExcludeFromRecordsRow.of(track(emptyList())))

        val recorded = ExcludeFromRecordsRow.of(track(listOf(point(0, 0L), point(1, 4_000L), point(2, 9_000L))))

        assertTrue(recorded.visible)
        assertFalse(recorded.autoExcluded)
    }

    @Test
    fun `a made-up route time explains the automatic exclusion`() {
        val synthetic = ExcludeFromRecordsRow.of(track(listOf(point(0, 0L), point(1, 4_000L)), timeSynthetic = true))

        assertTrue(synthetic.visible)
        assertTrue(synthetic.autoExcluded)
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
