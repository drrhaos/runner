package com.runner.academy.ui.workout

import android.content.Context
import com.runner.academy.R
import com.runner.academy.data.ConflictSide
import com.runner.academy.data.PendingConflict
import com.runner.academy.util.FormatUtils
import java.util.Date

/** The text of a waiting workout's card: it and what it overlaps, one line each. */
object ImportConflictText {

    fun message(context: Context, conflict: PendingConflict): String = buildString {
        val incoming = context.getString(R.string.workout_import_conflict_incoming)
        append(conflict.incoming.source?.let { context.getString(R.string.workout_import_conflict_source, incoming, it) } ?: incoming)
        append('\n').append(line(context, conflict.incoming))
        append("\n\n")
        if (conflict.others.isEmpty()) {
            append(context.getString(R.string.workout_import_conflict_none))
            return@buildString
        }
        append(context.getString(R.string.workout_import_conflict_others))
        conflict.others.forEach { other ->
            val where = if (other.existingId != null) {
                context.getString(R.string.workout_import_conflict_saved)
            } else {
                context.getString(R.string.workout_import_conflict_waiting, other.source ?: "")
            }
            append('\n').append(context.getString(R.string.workout_import_conflict_where, line(context, other), where))
        }
    }

    private fun line(context: Context, side: ConflictSide): String = context.getString(
        R.string.workout_import_conflict_line,
        FormatUtils.formatDateTime(Date(side.startMs)),
        FormatUtils.formatDistance(side.distanceKm, context),
        FormatUtils.formatTime(side.durationMs),
        context.getString(if (side.hasTrack) R.string.workout_import_conflict_with_track else R.string.workout_import_conflict_no_track)
    )
}
