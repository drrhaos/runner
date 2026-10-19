package com.runner.academy.ui.workout

import android.content.Context
import com.runner.academy.R
import com.runner.academy.data.ImportResult

/** What an import did, a line per outcome: the end's message and its notification alike. */
object ImportResultText {

    fun lines(context: Context, result: ImportResult): List<String> = buildList {
        add(
            when {
                with(result) { imported + pending + duplicates + failed + inaccessible == 0 } ->
                    context.getString(R.string.workout_list_import_empty)
                result.failed > 0 -> context.getString(R.string.workout_list_import_partial, result.imported, result.failed)
                else -> context.getString(R.string.workout_list_import_success, result.imported)
            }
        )
        if (result.pending > 0) add(context.getString(R.string.workout_list_import_pending_summary, result.pending))
        if (result.duplicates > 0) add(context.getString(R.string.workout_list_import_duplicates_summary, result.duplicates))
        if (result.inaccessible > 0) add(context.getString(R.string.workout_list_import_inaccessible, result.inaccessible))
    }
}
