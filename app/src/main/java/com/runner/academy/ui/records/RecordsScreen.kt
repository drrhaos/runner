package com.runner.academy.ui.records

import com.runner.academy.R
import com.runner.academy.data.BackfillState
import com.runner.academy.data.EffortRow
import com.runner.academy.data.RecordBook
import com.runner.academy.data.RecordDistance
import com.runner.academy.data.TrackData
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.RecordEligibility

/** The recalculation strip above the records. */
sealed interface RecordsRecalc {
    data object None : RecordsRecalc

    /** Rows wait for the pass, which has not started (or waits for a workout to end). */
    data object Indeterminate : RecordsRecalc

    data class Progress(val done: Int, val total: Int) : RecordsRecalc
}

/** One improvement of a record; [improvementMs] over the one before as shown, null for the first. */
data class RecordHistoryEntry(val row: EffortRow, val improvementMs: Long?)

/** One distance on the records screen; [record] null when no workout covered it. */
data class RecordsRow(
    val distance: RecordDistance,
    val record: EffortRow?,
    /** Newest first. */
    val history: List<RecordHistoryEntry>
) {
    val reached: Boolean get() = record != null

    /** A history of one record is the record itself: nothing to unfold. */
    val showsHistory: Boolean get() = history.size >= 2

    /** Part of the window was bridged by steps ("≈"). */
    val approximate: Boolean get() = (record?.effort?.stepsShare ?: 0f) > 0f
}

data class RecordsScreenState(
    /** Always one per [RecordDistance], in its order. */
    val rows: List<RecordsRow>,
    val recalc: RecordsRecalc,
    /** No record at all and nothing being recalculated. */
    val empty: Boolean,
    /** [empty] with workouts, none of them with a track. */
    val manualOnly: Boolean
)

object RecordsScreen {

    fun state(book: RecordBook, pending: Boolean, progress: BackfillState, manualOnly: Boolean): RecordsScreenState {
        val rows = rows(book)
        val recalc = recalc(pending, progress)
        val empty = recalc == RecordsRecalc.None && rows.none { it.reached }
        return RecordsScreenState(rows, recalc, empty, manualOnly = empty && manualOnly)
    }

    fun rows(book: RecordBook): List<RecordsRow> = RecordDistance.entries.map { distance ->
        val chain = book.history(distance)
        val history = chain.mapIndexed { index, row ->
            val improvement = if (index == 0) null else {
                FormatUtils.recordDeltaMs(chain[index - 1].effort.elapsedMs, row.effort.elapsedMs)
            }
            RecordHistoryEntry(row, improvement)
        }.asReversed()
        RecordsRow(distance, book.current(distance), history)
    }

    /**
     * A running pass shows its progress; rows still waiting for it ([pending]) before it runs
     * show an indeterminate strip.
     */
    fun recalc(pending: Boolean, progress: BackfillState): RecordsRecalc = when {
        progress is BackfillState.Running -> RecordsRecalc.Progress(progress.done, progress.total)
        pending -> RecordsRecalc.Indeterminate
        else -> RecordsRecalc.None
    }
}

/** When "Records updated: N" is shown. */
object RecordsAnnouncement {

    /** N of a finished pass over a backup import, or null when there is nothing to announce. */
    fun afterBackfill(state: BackfillState): Int? =
        (state as? BackfillState.Done)?.takeIf { it.afterImport }?.changedDistances?.takeIf { it > 0 }

    /** N of a GPX import, or null when it changed no record. */
    fun afterGpxImport(changedRecordDistances: Int): Int? = changedRecordDistances.takeIf { it > 0 }

    /**
     * "Open" goes to the records unless they are shown already, and never takes the user off
     * the tracking screen while a run is being recorded.
     */
    fun offersOpen(currentDestination: Int?, workoutActive: Boolean): Boolean = when (currentDestination) {
        R.id.nav_records, R.id.nav_all_records -> false
        R.id.nav_tracking -> !workoutActive
        else -> true
    }
}

/** "Don't count in records" on the details: only with a track, explained when left out automatically. */
data class ExcludeFromRecordsRow(val visible: Boolean, val autoExcluded: Boolean) {
    companion object {
        val Hidden = ExcludeFromRecordsRow(visible = false, autoExcluded = false)

        /** [track]: the shown track of the workout (the one its records are computed from). */
        fun of(track: TrackData?): ExcludeFromRecordsRow {
            if (track == null || track.points.isEmpty()) return Hidden
            return ExcludeFromRecordsRow(visible = true, autoExcluded = !RecordEligibility.isTrusted(track))
        }
    }
}
