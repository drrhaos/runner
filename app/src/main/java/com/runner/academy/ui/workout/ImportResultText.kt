package com.runner.academy.ui.workout

import android.content.Context
import com.runner.academy.R
import com.runner.academy.data.ImportResult

/** What an import did, a line per outcome: the end's message and its notification alike. */
object ImportResultText {

    /**
     * A running import: [done] of [total]; with no total (archives) the units read so far,
     * and nothing yet while it lists.
     */
    fun progress(context: Context, done: Int, total: Int): String = when {
        total > 0 -> context.getString(R.string.workout_list_import_progress_count, done, total)
        done > 0 -> context.getString(R.string.workout_list_import_progress_processed, done)
        else -> context.getString(R.string.workout_list_import_progress)
    }

    fun lines(context: Context, result: ImportResult): List<String> = buildList {
        add(
            when {
                with(result) { imported + pending + duplicates + notRunning + failed + inaccessible == 0 } ->
                    context.getString(R.string.workout_list_import_empty)
                result.failed > 0 -> context.getString(R.string.workout_list_import_partial, result.imported, result.failed)
                else -> context.getString(R.string.workout_list_import_success, result.imported)
            }
        )
        if (result.pending > 0) add(context.getString(R.string.workout_list_import_pending_summary, result.pending))
        if (result.duplicates > 0) add(context.getString(R.string.workout_list_import_duplicates_summary, result.duplicates))
        if (result.notRunning > 0) add(context.getString(R.string.workout_list_import_not_running_summary, result.notRunning))
        if (result.inaccessible > 0) add(context.getString(R.string.workout_list_import_inaccessible, result.inaccessible))
    }
}
