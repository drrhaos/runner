package com.runner.academy.ui.workout

import com.runner.academy.data.WorkoutMonth
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The months of the workout list in list order (newest first) with where each starts: the
 * fast scroller names the month of any position, loaded or not.
 */
class HistoryIndex(val months: List<WorkoutMonth>) {

    /** Position of the first workout of each month. */
    private val starts: IntArray = IntArray(months.size).also { starts ->
        var position = 0
        months.forEachIndexed { i, month ->
            starts[i] = position
            position += month.count
        }
    }

    /** Workouts in all months: the length of the list. */
    val total: Int = months.sumOf { it.count }

    /** The month [position] falls in, clamped to the list; null for an empty list. */
    fun monthAt(position: Int): WorkoutMonth? {
        if (months.isEmpty()) return null
        val clamped = position.coerceIn(0, (total - 1).coerceAtLeast(0))
        // The last month whose start is not after the position
        var low = 0
        var high = months.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= clamped) low = mid else high = mid - 1
        }
        return months[low]
    }

    companion object {
        val EMPTY = HistoryIndex(emptyList())
    }
}

/** "Октябрь 2026", "October 2026": the month name as it stands alone, with the year. */
object MonthLabel {

    fun of(month: WorkoutMonth, locale: Locale = Locale.getDefault()): String {
        val calendar = Calendar.getInstance(locale).apply {
            clear()
            set(month.year, month.month - 1, 1)
        }
        // "L": the stand-alone month form (nominative in Russian); month first in both locales
        val text = SimpleDateFormat("LLLL yyyy", locale).format(calendar.time)
        return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}
